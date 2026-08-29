import { useMemo } from 'react';
import { QuestionCircleOutlined } from '@ant-design/icons';
import { answerInterrupt, getAnsweredSelection, isPendingToolCall } from './askUserStore';
import { CardShell, headerRow } from './PlanCards';

/**
 * ask_user_question 问题卡片：工具挂起时渲染问题与选项，点击选项即作答
 * （经 askUserStore 程序化提交，下一次 run 携带 resume[] 恢复执行）。
 * 数据形态与 PlanCards 一致：content[0].data = { name, call_id, arguments }。
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

/** 流式容错解析 {question, options[]}：先整段 JSON，未完成时逐字段正则兜底 */
function parseQuestion(argsStr: string): { question: string; options: string[] } {
  if (!argsStr) return { question: '', options: [] };
  try {
    const obj = JSON.parse(argsStr) as { question?: unknown; options?: unknown };
    const options = Array.isArray(obj?.options)
      ? obj.options.filter((o): o is string => typeof o === 'string' && o.trim() !== '')
      : [];
    return { question: typeof obj?.question === 'string' ? obj.question : '', options };
  } catch {
    const q = /"question"\s*:\s*"((?:\\.|[^"\\])*)"/.exec(argsStr);
    let question = '';
    if (q) {
      try {
        question = JSON.parse(`"${q[1]}"`) as string;
      } catch {
        question = q[1];
      }
    }
    const options: string[] = [];
    const list = /"options"\s*:\s*\[([^\]]*)/.exec(argsStr);
    if (list) {
      const itemRe = /"((?:\\.|[^"\\])*)"/g;
      let m: RegExpExecArray | null;
      while ((m = itemRe.exec(list[1])) !== null) {
        try {
          const text = JSON.parse(`"${m[1]}"`) as string;
          if (text.trim()) options.push(text);
        } catch {
          // 流式中途的未闭合字面量，跳过
        }
      }
    }
    return { question, options };
  }
}

export function AskUserCard({ data }: { data: RuntimeMessageLike }) {
  const args = data?.content?.[0]?.data?.arguments ?? '';
  const callId = data?.content?.[0]?.data?.call_id ?? '';
  const output = data?.content?.[1]?.data?.output;
  const { question, options } = useMemo(() => parseQuestion(args), [args]);

  const pending = isPendingToolCall(callId);
  const clicked = getAnsweredSelection(callId);
  const answered = clicked ?? (typeof output === 'string' && output ? output : undefined);

  if (!question && !options.length) return null;

  const chip = answered
    ? <span style={{ color: '#52c41a' }}>已回答</span>
    : pending
      ? <span style={{ color: 'rgba(26, 26, 29, 0.45)' }}>请选择一项</span>
      : <span style={{ color: 'rgba(26, 26, 29, 0.35)' }}>已跳过</span>;

  return (
    <CardShell header={headerRow(<QuestionCircleOutlined />, question || '请确认', chip)}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {options.map((opt) => {
          const selected = answered === opt;
          const clickable = pending && !answered;
          return (
            <div
              key={opt}
              onClick={() => {
                if (clickable) answerInterrupt(callId, opt);
              }}
              style={{
                padding: '6px 12px',
                borderRadius: 8,
                fontSize: 13,
                cursor: clickable ? 'pointer' : 'default',
                border: selected
                  ? '1px solid #1a1a1d'
                  : '1px solid rgba(26, 26, 29, 0.12)',
                background: selected ? 'rgba(26, 26, 29, 0.06)' : 'rgba(255, 255, 255, 0.6)',
                color: selected ? 'rgba(26, 26, 29, 0.95)' : 'rgba(26, 26, 29, 0.8)',
                fontWeight: selected ? 600 : 400,
                transition: 'all 0.15s ease',
                wordBreak: 'break-word',
              }}
            >
              {opt}
            </div>
          );
        })}
      </div>
    </CardShell>
  );
}
