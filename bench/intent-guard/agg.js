// node agg.js <results.jsonl>
const fs = require('fs');
const rows = fs.readFileSync(process.argv[2], 'utf8').split('\n').filter(Boolean).map(JSON.parse);

const pct = (x, n) => ((x * 100) / n).toFixed(1) + '%';
function stats(arr) {
  if (!arr.length) return null;
  const s = [...arr].sort((a, b) => a - b);
  const q = (p) => s[Math.min(s.length - 1, Math.floor(p * s.length))];
  return {
    n: s.length,
    avg: Math.round(s.reduce((a, b) => a + b, 0) / s.length),
    p50: q(0.5), p95: q(0.95), min: s[0], max: s[s.length - 1],
  };
}

function side(name) {
  let ok = 0, err = 0, unparsed = 0;
  let tp = 0, fp = 0, tn = 0, fn = 0; // REJECT = positive class (拦截)
  const lat = [], warm = [];
  const mism = [];
  for (const r of rows) {
    const x = r[name];
    if (x.err) { err++; /* production failOpen -> ACCEPT(pass) */ }
    const dec = x.dec || 'ACCEPT'; // failOpen: error/unparsed => pass
    if (x.err) { unparsed; }
    else if (name === 'llm' && dec === 'UNPARSED') { /* counted as pass below */ }
    const passed = !x.err && dec !== 'UNPARSED' ? dec !== 'REJECT' : true;
    const expectedPass = r.expect === 'ACCEPT';
    const correct = passed === expectedPass;
    if (correct) ok++; else mism.push({ id: r.id, expect: r.expect, dec: x.err ? 'ERR:' + x.err.slice(0, 40) : dec, noul: x.noul, out: x.out });
    const rejected = !passed;
    if (rejected && !expectedPass) tn += 0, tp++;
    else if (rejected && expectedPass) fp++;
    else if (!rejected && !expectedPass) fn++;
    else tn++;
    lat.push(x.ms);
    if (x.tls > 0) warm.push(x.ms - x.tls);
  }
  return { name, acc: pct(ok, rows.length), ok, err, mism, tp, fp, tn, fn, lat: stats(lat), warm: stats(warm) };
}

const jev = side('jev');
const llm = side('llm');

// noul distribution
const buckets = {};
for (const r of rows) if (r.jev.noul !== undefined) {
  const b = Math.floor(r.jev.noul * 10) / 10;
  const k = b.toFixed(1);
  buckets[k] = buckets[k] || { ACCEPT: 0, REJECT: 0 };
  buckets[k][r.expect]++;
}

// threshold sweep for JEV
const sweep = [];
for (let t = 0.1; t <= 0.9; t += 0.1) {
  let ok = 0;
  for (const r of rows) {
    if (r.jev.noul === undefined) { ok += r.expect === 'ACCEPT' ? 1 : 0; continue; }
    const passed = !(r.jev.noul < t);
    if (passed === (r.expect === 'ACCEPT')) ok++;
  }
  sweep.push(`threshold=${t.toFixed(1)} acc=${pct(ok, rows.length)}`);
}

for (const s of [jev, llm]) {
  console.log(`\n== ${s.name} ==`);
  console.log(`accuracy=${s.acc} (ok ${s.ok}/${rows.length}) errors=${s.err}`);
  console.log(`混淆: TP(正确拦截)=${s.tp} FP(误拦)=${s.fp} FN(漏拦)=${s.fn} TN(正确放行)=${s.tn}`);
  console.log(`latency(ms)   ${JSON.stringify(s.lat)}`);
  console.log(`warm(ms,去TLS) ${JSON.stringify(s.warm)}`);
  console.log(`mismatches: ${JSON.stringify(s.mism, null, 1)}`);
}
console.log('\n== JEV noul 分布 (bucket: expect) ==');
console.log(JSON.stringify(buckets, null, 1));
console.log('\n== JEV 阈值敏感性 ==');
console.log(sweep.join('\n'));
const total = rows.length;
console.log(`\ncases=${total} accept=${rows.filter(r => r.expect === 'ACCEPT').length} reject=${rows.filter(r => r.expect === 'REJECT').length}`);
