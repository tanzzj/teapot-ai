import { useMemo, useState } from 'react';
import { QuestionCircleOutlined } from '@ant-design/icons';
import { parseEnvelope, str, bool, strList } from '../a2ui/envelope';
import { extractQuestions } from '../a2ui/askForm';
import { A2uiCard } from './A2uiCard';
import {
  getAnsweredPayload,
  getAnsweredSelection,
  getInterruptMessage,
  isPendingToolCall,
  resolveInterrupt,
} from './askUserStore';
import { CardShell, headerRow } from './PlanCards';

/**
 * ask_user_question（框架 ClarificationMiddleware 纯文本档）问题卡片：
 * 挂起时渲染 questions[]（text/select/multi_select/confirm，与框架工具 schema 一致）为
 * 纯文本问题卡，提交 → resolveInterrupt（payload = {id: 答案}，恢复工具结果）。
 * a2ui 开启时服务端注册的是表单档（interrupt message = A2UI 信封），由挂载分发器
 * AskQuestionCard 分流到 A2uiCard 渲染表单，本文件不再消费旧 {question, options} 形态。
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

interface Question {
  id: string;
  text: string;
  type: string;
  options: string[];
  required: boolean;
}

/** 恢复工具结果 = 答案 JSON 文本（历史回放）；非 object 不认 */
function parseAnswersJson(text?: string): Record<string, unknown> | null {
  if (!text || text.trimStart().startsWith('Error')) return null;
  try {
    const obj = JSON.parse(text) as unknown;
    if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
      return obj as Record<string, unknown>;
    }
  } catch {
    // 非 JSON（如错误提示）不认
  }
  return null;
}

function fmtAnswer(v: unknown): string {
  if (Array.isArray(v)) return v.join('、');
  if (typeof v === 'boolean') return v ? '是' : '否';
  if (v === 'yes') return '是';
  if (v === 'no') return '否';
  return String(v ?? '');
}

function toQuestions(raw: Record<string, unknown>[]): Question[] {
  return raw.map((q) => {
    const type = str(q.type) ?? 'text';
    const options = strList(q.options);
    return {
      id: str(q.id)!,
      text: str(q.question) ?? '',
      type,
      options: type === 'confirm' && !options.length ? ['yes', 'no'] : options,
      required: bool(q.required),
    };
  });
}

const chipStyle = (selected: boolean, clickable: boolean): React.CSSProperties => ({
  padding: '4px 12px',
  borderRadius: 8,
  fontSize: 13,
  cursor: clickable ? 'pointer' : 'default',
  border: selected ? '1px solid #1a1a1d' : '1px solid rgba(26, 26, 29, 0.12)',
  background: selected ? 'rgba(26, 26, 29, 0.06)' : 'rgba(255, 255, 255, 0.6)',
  color: selected ? 'rgba(26, 26, 29, 0.95)' : 'rgba(26, 26, 29, 0.8)',
  fontWeight: selected ? 600 : 400,
  transition: 'all 0.15s ease',
  wordBreak: 'break-word',
});

export function AskUserCard({ data, readOnly }: { data: RuntimeMessageLike; readOnly?: boolean }) {
  const args = data?.content?.[0]?.data?.arguments ?? '';
  const callId = data?.content?.[0]?.data?.call_id ?? '';
  const output = data?.content?.[1]?.data?.output;

  const questions = useMemo(() => toQuestions(extractQuestions(args)), [args]);
  const pending = isPendingToolCall(callId);
  const answeredText = getAnsweredSelection(callId);
  const answeredPayload = getAnsweredPayload(callId);
  const answered = answeredPayload ?? parseAnswersJson(output) ?? undefined;

  const [values, setValues] = useState<Record<string, string | string[]>>({});
  const [error, setError] = useState('');

  if (!questions.length) return null;

  const interactive = !readOnly && pending && !answeredPayload;

  const chip = answeredText !== undefined || answered
    ? <span style={{ color: '#52c41a' }}>已回答</span>
    : pending
      ? <span style={{ color: 'rgba(26, 26, 29, 0.45)' }}>请作答</span>
      : <span style={{ color: 'rgba(26, 26, 29, 0.35)' }}>已跳过</span>;

  const submit = () => {
    if (!interactive) return;
    const answers: Record<string, unknown> = {};
    const lines: string[] = [];
    for (const q of questions) {
      const v = values[q.id];
      const empty = v === undefined || v === '' || (Array.isArray(v) && !v.length);
      if (q.required && empty) {
        setError(`请回答「${q.text}」`);
        return;
      }
      if (empty) continue;
      answers[q.id] = v;
      lines.push(`${q.text}: ${fmtAnswer(v)}`);
    }
    setError('');
    resolveInterrupt(callId, answers, lines.join('\n') || '已提交');
  };

  const pick = (q: Question, opt: string) => {
    setError('');
    if (q.type === 'multi_select') {
      setValues((prev) => {
        const cur = Array.isArray(prev[q.id]) ? (prev[q.id] as string[]) : [];
        return {
          ...prev,
          [q.id]: cur.includes(opt) ? cur.filter((x) => x !== opt) : [...cur, opt],
        };
      });
    } else {
      setValues((prev) => ({ ...prev, [q.id]: opt }));
    }
  };

  return (
    <CardShell header={headerRow(<QuestionCircleOutlined />, '请确认', chip)}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {questions.map((q) => {
          // 已作答/历史回放：直接展示「问题: 答案」行
          if (!interactive) {
            const a = answered ? answered[q.id] : undefined;
            return (
              <div key={q.id} style={{ fontSize: 13, wordBreak: 'break-word' }}>
                <span style={{ fontWeight: 600 }}>{q.text}</span>
                <span style={{ color: a === undefined ? 'rgba(26, 26, 29, 0.35)' : 'rgba(26, 26, 29, 0.85)' }}>
                  ：{a === undefined ? '未作答' : fmtAnswer(a)}
                </span>
              </div>
            );
          }
          const v = values[q.id];
          return (
            <div key={q.id} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div style={{ fontSize: 13, fontWeight: 600 }}>
                {q.text}
                {q.required ? <span style={{ color: '#ff4d4f', marginLeft: 2 }}>*</span> : null}
              </div>
              {q.type === 'text' ? (
                <input
                  value={typeof v === 'string' ? v : ''}
                  onChange={(e) => {
                    setError('');
                    setValues((prev) => ({ ...prev, [q.id]: e.target.value }));
                  }}
                  style={{
                    padding: '6px 12px',
                    borderRadius: 8,
                    fontSize: 13,
                    border: '1px solid rgba(26, 26, 29, 0.12)',
                    background: 'rgba(255, 255, 255, 0.6)',
                    outline: 'none',
                  }}
                />
              ) : (
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                  {q.options.map((opt) => {
                    const selected =
                      q.type === 'multi_select'
                        ? Array.isArray(v) && v.includes(opt)
                        : v === opt;
                    return (
                      <div key={opt} onClick={() => pick(q, opt)} style={chipStyle(selected, true)}>
                        {fmtAnswer(opt)}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
        {interactive ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <div
              onClick={submit}
              style={{
                ...chipStyle(false, true),
                fontWeight: 600,
                border: '1px solid #1a1a1d',
                background: '#1a1a1d',
                color: '#fff',
              }}
            >
              提交
            </div>
            {error ? <span style={{ fontSize: 12, color: '#ff4d4f' }}>{error}</span> : null}
          </div>
        ) : null}
      </div>
    </CardShell>
  );
}

/**
 * ask_user_question 统一挂载分发：中断 message 可解析为 A2UI 信封（a2ui 开启时的表单档）
 * → A2uiCard 表单；否则（纯文本澄清档、历史回放）→ AskUserCard 纯文本问题卡。
 */
export function AskQuestionCard({
  data,
  readOnly,
}: {
  data: RuntimeMessageLike;
  readOnly?: boolean;
}) {
  const callId = data?.content?.[0]?.data?.call_id ?? '';
  const envelope = isPendingToolCall(callId)
    ? parseEnvelope(getInterruptMessage(callId))
    : null;
  if (envelope) return <A2uiCard data={data} readOnly={readOnly} />;
  return <AskUserCard data={data} readOnly={readOnly} />;
}

export function AskQuestionCardReadOnly(props: { data: RuntimeMessageLike }) {
  return <AskQuestionCard {...props} readOnly />;
}
