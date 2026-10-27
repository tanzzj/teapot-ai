import { useMemo } from 'react';
import { message } from '@agentscope-ai/design';
import { SparkCopyLine, SparkSmartTerminalLine } from '@agentscope-ai/icons';
import { CardShell, headerRow } from './PlanCards';

/**
 * shell 工具（execute）的终端样式卡（SPEC §29.28）：替代默认折叠面板的「Input=整坨 JSON +
 * Output=纯文本」，改成 `$ <command>` 一行命令 + 下方终端输出块，退出码提到头部当状态徽标。
 *
 * <p>退出码为什么在正文里：harness 的两条执行路径都把它拼进工具输出文本 —— 沙箱路径
 * （ShellExecuteTool）是前导 `Exit code: N`，本地路径（LocalFilesystemWithShell）只在非 0 时
 * 追加尾随 `Exit code: N`。两种形态都在这里剥掉，所以正文不会再出现那行红点文本。
 *
 * <p>数据来源与计划卡相同（aguiBridge 的 TOOL_CALL_* → tool_call 消息），arguments 是流式
 * 拼接中的 JSON 字符串，解析必须容忍不完整。
 */

interface RuntimeMessageLike {
  status?: string;
  content?: { data?: { arguments?: string; output?: string } }[];
}

const TERMINAL: React.CSSProperties = {
  background: 'rgba(26, 26, 29, 0.92)',
  color: '#e9e9ec',
  borderRadius: 8,
  padding: '8px 10px',
  margin: '6px 0 0',
  fontFamily: 'Menlo, Consolas, monospace',
  fontSize: 12,
  lineHeight: 1.6,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
  maxHeight: 320,
  overflowY: 'auto',
};

/** 流式容错取字符串字段：完整 JSON 优先，未完成时按字面量截取 */
function stringField(args: string, key: string): string {
  if (!args) return '';
  try {
    const parsed = JSON.parse(args) as Record<string, unknown>;
    if (typeof parsed?.[key] === 'string') return parsed[key] as string;
  } catch {
    // arguments 还在流式拼接中，走下面的截取
  }
  const matched = new RegExp('"' + key + '"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)').exec(args);
  if (!matched) return '';
  try {
    return JSON.parse('"' + matched[1] + '"') as string;
  } catch {
    return matched[1];
  }
}

const EXIT_LINE = /(?:^|\n)Exit code:\s*(-?\d+)[ \t]*(?=\n|$)/;

/** 剥掉前导/尾随的退出码行，返回退出码与纯输出 */
function splitExitCode(raw: string | undefined): { exit: number | null; body: string } {
  if (!raw) return { exit: null, body: '' };
  const matched = EXIT_LINE.exec(raw);
  const exit = matched ? Number(matched[1]) : null;
  const body = matched
    ? raw.slice(0, matched.index) + raw.slice(matched.index + matched[0].length)
    : raw;
  return { exit, body: body.replace(/^\s*\n+/, '').replace(/\s+$/, '') };
}

function chipStyle(exit: number | null, loading: boolean): React.CSSProperties {
  const failed = exit !== null && exit !== 0;
  const base = {
    padding: '1px 8px',
    borderRadius: 999,
    fontSize: 11.5,
    fontFamily: 'Menlo, Consolas, monospace',
  };
  if (exit === null) {
    return { ...base, background: 'rgba(26,26,29,0.06)', color: 'rgba(26,26,29,0.55)' };
  }
  return failed
    ? { ...base, background: 'rgba(209,68,68,0.12)', color: '#b1382f' }
    : {
        ...base,
        background: loading ? 'rgba(26,26,29,0.06)' : 'rgba(24,121,78,0.12)',
        color: loading ? 'rgba(26,26,29,0.55)' : '#17663f',
      };
}

export function ShellCard({ data }: { data: RuntimeMessageLike }) {
  const loading = data?.status === 'in_progress';
  const args = (data?.content?.[0]?.data as { arguments?: string } | undefined)?.arguments ?? '';
  const output = (data?.content?.[1]?.data as { output?: string } | undefined)?.output;
  const command = useMemo(() => stringField(args, 'command'), [args]);
  const cwd = useMemo(() => stringField(args, 'working_directory'), [args]);
  const { exit, body } = useMemo(() => splitExitCode(output), [output]);

  if (!command && !body && !loading) {
    return null;
  }
  const label = loading && exit === null ? '执行中' : exit === null ? '无退出码' : `exit ${exit}`;

  return (
    <CardShell
      header={headerRow(
        <SparkSmartTerminalLine />,
        'execute',
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <span style={chipStyle(exit, loading)}>{label}</span>
          {command && (
            <span
              role="button"
              title="复制命令"
              onClick={() => {
                navigator.clipboard
                  ?.writeText(command)
                  .then(() => message.success('命令已复制'))
                  .catch(() => message.error('复制失败，请手动选取'));
              }}
              style={{ display: 'inline-flex', cursor: 'pointer', color: 'rgba(26,26,29,0.45)' }}
            >
              <SparkCopyLine size={14} />
            </span>
          )}
        </span>,
      )}
    >
      <pre style={{ ...TERMINAL, marginTop: 0 }}>
        <span style={{ color: '#7ee787', marginRight: 6 }}>$</span>
        {command || '…'}
      </pre>
      {cwd && (
        <div style={{ marginTop: 4, fontSize: 11.5, color: 'rgba(26,26,29,0.45)' }}>
          工作目录 <code style={{ fontFamily: 'Menlo, Consolas, monospace' }}>{cwd}</code>
        </div>
      )}
      {body ? (
        <pre style={TERMINAL}>{body}</pre>
      ) : loading ? (
        <pre style={TERMINAL}>
          <span style={{ color: 'rgba(233,233,236,0.55)' }}>等待输出…</span>
        </pre>
      ) : null}
    </CardShell>
  );
}
