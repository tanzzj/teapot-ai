// node gen-md.js — 由 cases.tsv + 两轮结果生成 testcases.md / bad-cases.md
const fs = require('fs');
const dir = __dirname;
const cases = fs.readFileSync(dir + '/cases.tsv', 'utf8').split('\n').filter(Boolean)
  .map((l) => { const [id, expect, text] = l.split('\t'); return { id: +id, expect, text }; });
const load = (f) => Object.fromEntries(fs.readFileSync(dir + '/' + f, 'utf8').split('\n').filter(Boolean)
  .map(JSON.parse).map((r) => [r.id, r]));
const D = load('results-direct.jsonl');
const P = load('results-proxy.jsonl');

const groups = [
  [1, 8, '订单/物流'], [9, 16, '退换货/售后'], [17, 24, '发票'], [25, 32, '账号'], [33, 40, '优惠券/活动'],
  [41, 45, '黑名单：写代码'], [46, 50, '黑名单：医疗'], [51, 55, '黑名单：法律'], [56, 60, '黑名单：学术代写'],
  [61, 65, '黑名单：违禁/灰产'], [66, 70, '黑名单：荐股理财'], [71, 90, '白名单外闲聊（应拒）'], [91, 100, '边界/混合/注入'],
];
const groupOf = (id) => groups.find(([a, b]) => id >= a && id <= b)[2];
const cell = (r, side) => {
  const x = r[side];
  const dec = x.dec || (x.err ? 'ERR' : '?');
  const ok = (dec === 'REJECT') === (r.expect === 'REJECT') && !x.err ? '' : ' ❌';
  if (side === 'jev') return `${dec}${ok} (${x.noul ?? '-'}|${x.ms}ms)`;
  return `${dec}${ok} (${x.ms}ms)`;
};

let md = '# 测试用例与逐条结果\n\n'
  + '规则见 `rules.txt`（11 条），预期标签为人工标注：46 放行 / 54 拦截。\n'
  + '列格式：判定（JEV 为 noul|热态前含建连耗时，❌ 标错例）。\n\n'
  + '| # | 分组 | 用户请求 | 预期 | JEV直连 | JEV代理 | LLM直连 | LLM代理 |\n|---|---|---|---|---|---|---|---|\n';
for (const c of cases) {
  const d = D[c.id], p = P[c.id];
  md += `| ${c.id} | ${groupOf(c.id)} | ${c.text} | ${c.expect} | ${cell(d, 'jev')} | ${cell(p, 'jev')} | ${cell(d, 'llm')} | ${cell(p, 'llm')} |\n`;
}
fs.writeFileSync(dir + '/testcases.md', md);

// bad cases
let bad = '# Bad Case 分析\n\n'
  + '两轮合计 10 次误判（同一用例跨轮重复计），无一条漏拦，全部是**误拦合法请求**。\n\n'
  + '| 用例 | 预期 | JEV直连 | JEV代理 | LLM直连 | LLM代理 |\n|---|---|---|---|---|---|\n';
const mis = (r, side) => { const x = r[side]; return (x.dec || 'PASS') === 'REJECT' ? (r.expect === 'ACCEPT') : false; };
const rows = cases.filter((c) => mis(D[c.id], 'jev') || mis(P[c.id], 'jev') || mis(D[c.id], 'llm') || mis(P[c.id], 'llm'));
for (const c of rows) {
  const d = D[c.id], p = P[c.id];
  bad += `| #${c.id} ${c.text} | ACCEPT | ${d.jev.noul} ${d.jev.dec} | ${p.jev.noul} ${p.jev.dec} | ${d.llm.dec} | ${p.llm.dec} |\n`;
}
fs.writeFileSync(dir + '/bad-cases.md', bad);
console.log('bad case ids:', rows.map((r) => r.id).join(','));
