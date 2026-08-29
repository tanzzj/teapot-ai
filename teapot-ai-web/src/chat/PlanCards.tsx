import { useMemo, useState } from 'react';
import { Markdown } from '@agentscope-ai/chat';
import { SparkPlanningLine } from '@agentscope-ai/icons';
import { LoadingOutlined } from '@ant-design/icons';

/**
 * 计划模式自定义工具渲染（SPEC §25）：
 * 经 AgentScopeRuntimeWebUI options.customToolRenderConfig 按工具名挂载，
 * 替代默认的 ToolCall 折叠面板（OperateCard + CodeMirror）。
 *
 * 数据来源：aguiBridge 把 AG-UI TOOL_CALL_* 事件翻译为 tool_call 消息，
 * content[0].data = { name, call_id, arguments }（JSON 字符串，流式拼接中可能不完整），
 * 渲染期 mergeToolMessages 会把 tool_call_output 的 output 合并进 content[1].data。
 * 因此解析必须容错不完整 JSON（流式中途），并对 output 形态（无 arguments）做区分。
 */

interface ToolData {
  name?: string;
  call_id?: string;
  arguments?: string;
  output?: string;
}

interface RuntimeMessageLike {
  status?: string;
  content?: { data?: ToolData }[];
}

/** content[0] 携带 arguments（工具调用本体）；content[1] 只有 output（结果合并消息） */
function sliceOf(data: RuntimeMessageLike): { args: string; output?: string } {
  const args = data.content?.[0]?.data?.arguments;
  return { args: args ?? '', output: data.content?.[1]?.data?.output };
}

/** 流式容错提取 plan_write 的 content 字段（完整 JSON 优先，未完成时截取字符串字面量） */
function extractPlanContent(argsStr: string): string {
  if (!argsStr) return '';
  try {
    const obj = JSON.parse(argsStr) as { content?: unknown };
    if (typeof obj?.content === 'string') return obj.content;
  } catch {
    // 流式未完成：先试完整字面量，再退化为「直到结尾」的未闭合字面量
    const complete = /"content"\s*:\s*"((?:\\.|[^"\\])*)"/.exec(argsStr);
    const candidate = complete?.[1] ?? /"content"\s*:\s*"((?:\\.|[^"\\])*)$/.exec(argsStr)?.[1];
    if (candidate !== undefined) {
      try {
        return JSON.parse(`"${candidate}"`) as string;
      } catch {
        return candidate;
      }
    }
  }
  return '';
}

interface TodoItem {
  content: string;
  status: 'pending' | 'in_progress' | 'completed';
}

/** 流式容错提取 todo_write 的 todos 列表：逐个匹配扁平对象，尾部不完整项自然跳过 */
function extractTodos(argsStr: string): TodoItem[] {
  if (!argsStr) return [];
  try {
    const obj = JSON.parse(argsStr) as { todos?: TodoItem[] };
    if (Array.isArray(obj?.todos)) {
      return obj.todos
        .filter((t) => typeof t?.content === 'string' && t.content.trim())
        .map((t) => ({
          content: t.content,
          status: t.status === 'in_progress' || t.status === 'completed' ? t.status : 'pending',
        }));
    }
  } catch {
    const items: TodoItem[] = [];
    const itemRe = /\{[^{}]*\}/g;
    let m: RegExpExecArray | null;
    while ((m = itemRe.exec(argsStr)) !== null) {
      const seg = m[0];
      const content = /"content"\s*:\s*"((?:\\.|[^"\\])*)"/.exec(seg);
      if (!content) continue;
      let text: string;
      try {
        text = JSON.parse(`"${content[1]}"`) as string;
      } catch {
        text = content[1];
      }
      if (!text.trim()) continue;
      const status = /"status"\s*:\s*"(in_progress|completed)"/.exec(seg)?.[1] ?? 'pending';
      items.push({ content: text, status: status as TodoItem['status'] });
    }
    return items;
  }
  return [];
}

/** 计划卡片外壳（与聊天区玻璃拟态风格一致） */
export function CardShell(props: { header: React.ReactNode; children: React.ReactNode }) {
  return (
    <div
      style={{
        margin: '2px 0',
        padding: '10px 14px',
        maxWidth: 640,
        borderRadius: 12,
        border: '1px solid rgba(26, 26, 29, 0.08)',
        background: 'rgba(255, 255, 255, 0.72)',
        backdropFilter: 'blur(12px)',
        boxShadow: '0 1px 6px rgba(26, 26, 29, 0.04)',
        fontSize: 13,
      }}
    >
      {props.header}
      {props.children}
    </div>
  );
}

export function headerRow(icon: React.ReactNode, title: string, extra?: React.ReactNode) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 6,
        fontWeight: 600,
        fontSize: 13,
        color: 'rgba(26, 26, 29, 0.88)',
        marginBottom: 6,
      }}
    >
      <span style={{ display: 'inline-flex', fontSize: 15 }}>{icon}</span>
      {title}
      {extra ? <span style={{ marginLeft: 'auto', fontWeight: 400, fontSize: 12 }}>{extra}</span> : null}
    </div>
  );
}

/** plan_write：计划全文卡片（markdown 渲染，流式可见；完成后默认收起可展开） */
export function PlanWriteCard({ data }: { data: RuntimeMessageLike }) {
  const loading = data?.status === 'in_progress';
  const { args } = sliceOf(data ?? {});
  const content = useMemo(() => extractPlanContent(args), [args]);
  const [openOverride, setOpenOverride] = useState<boolean | null>(null);
  const open = openOverride ?? loading;
  if (!content && !loading) return null;
  return (
    <CardShell
      header={
        <div
          style={{ cursor: loading ? 'default' : 'pointer', userSelect: 'none' }}
          onClick={() => {
            if (!loading) setOpenOverride(!open);
          }}
        >
          {headerRow(
            <SparkPlanningLine />,
            '执行计划',
            loading ? (
              <span style={{ color: 'rgba(26, 26, 29, 0.45)' }}>
                <LoadingOutlined /> 草拟中…
              </span>
            ) : (
              <span style={{ color: 'rgba(26, 26, 29, 0.35)' }}>{open ? '收起 ▴' : '展开 ▾'}</span>
            ),
          )}
        </div>
      }
    >
      {open ? (
        <div style={{ color: 'rgba(26, 26, 29, 0.8)' }}>
          <Markdown content={content || '（计划生成中…）'} />
        </div>
      ) : null}
    </CardShell>
  );
}

/** plan_enter：进入计划模式的轻量提示 */
export function PlanEnterCard({ data }: { data: RuntimeMessageLike }) {
  const { args } = sliceOf(data ?? {});
  // output 合并消息没有 arguments → 不重复渲染
  if (!args) return null;
  return (
    <CardShell header={headerRow(<SparkPlanningLine />, '已进入计划模式',
      <span style={{ color: 'rgba(26, 26, 29, 0.35)' }}>只读 · 产出计划后请求批准</span>)} >
      {null}
    </CardShell>
  );
}

/** plan_exit：计划完成、等待批准卡片 */
export function PlanExitCard({ data }: { data: RuntimeMessageLike }) {
  const { args, output } = sliceOf(data ?? {});
  if (!args) return null;
  let summary = '';
  try {
    const obj = JSON.parse(args) as { summary?: unknown };
    if (typeof obj?.summary === 'string') summary = obj.summary;
  } catch {
    summary = '';
  }
  const approved = typeof output === 'string' && /approve|build/i.test(output);
  return (
    <CardShell
      header={headerRow(
        <SparkPlanningLine />,
        '计划已就绪',
        <span style={{ color: approved ? '#52c41a' : 'rgba(26, 26, 29, 0.45)' }}>
          {approved ? '已批准，开始执行' : '等待批准'}
        </span>,
      )}
    >
      {summary ? <div style={{ color: 'rgba(26, 26, 29, 0.65)' }}>{summary}</div> : null}
    </CardShell>
  );
}

const TODO_DOT: Record<TodoItem['status'], React.ReactNode> = {
  pending: (
    <span style={{ width: 12, height: 12, borderRadius: '50%', border: '1.5px solid rgba(26, 26, 29, 0.25)', boxSizing: 'border-box', flexShrink: 0 }} />
  ),
  in_progress: <LoadingOutlined style={{ fontSize: 12, color: '#1677ff', flexShrink: 0 }} />,
  completed: (
    <span style={{ width: 12, height: 12, borderRadius: '50%', background: '#52c41a', color: '#fff', fontSize: 9, lineHeight: '12px', textAlign: 'center', flexShrink: 0 }}>✓</span>
  ),
};

/** todo_write：执行进度清单（SDK 规定同一时刻恰好一个 in_progress） */
export function TodoWriteCard({ data }: { data: RuntimeMessageLike }) {
  const loading = data?.status === 'in_progress';
  const { args } = sliceOf(data ?? {});
  const todos = useMemo(() => extractTodos(args), [args]);
  if (!todos.length && !loading) return null;
  const done = todos.filter((t) => t.status === 'completed').length;
  const pct = todos.length ? Math.round((done / todos.length) * 100) : 0;
  return (
    <CardShell
      header={headerRow(
        <SparkPlanningLine />,
        '执行进度',
        <span style={{ color: 'rgba(26, 26, 29, 0.45)' }}>
          {done}/{todos.length}{loading ? ' · 更新中…' : ''}
        </span>,
      )}
    >
      <div style={{ height: 4, borderRadius: 2, background: 'rgba(26, 26, 29, 0.06)', marginBottom: 8, overflow: 'hidden' }}>
        <div style={{ height: '100%', width: `${pct}%`, background: '#52c41a', borderRadius: 2, transition: 'width .3s' }} />
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
        {todos.map((t, i) => (
          <div key={i} style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
            <span style={{ marginTop: 3, display: 'inline-flex' }}>{TODO_DOT[t.status]}</span>
            <span
              style={{
                color: t.status === 'completed' ? 'rgba(26, 26, 29, 0.35)' : 'rgba(26, 26, 29, 0.85)',
                textDecoration: t.status === 'completed' ? 'line-through' : 'none',
                fontWeight: t.status === 'in_progress' ? 600 : 400,
                wordBreak: 'break-word',
              }}
            >
              {t.content}
            </span>
          </div>
        ))}
      </div>
    </CardShell>
  );
}
