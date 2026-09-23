import { useMemo, useState } from 'react';
import { AppstoreOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import type { A2uiComponent } from '../a2ui/envelope';
import { extractObjects, parseEnvelope, toolErrorText } from '../a2ui/envelope';
import { extractQuestions, questionsToComponents } from '../a2ui/askForm';
import { A2uiSurfaceView, collectFields } from '../a2ui/A2uiSurfaceView';
import {
  getAnsweredPayload,
  getAnsweredSelection,
  getInterruptMessage,
  isPendingToolCall,
  resolveInterrupt,
  submitMessage,
} from './askUserStore';
import { CardShell, headerRow } from './PlanCards';

/**
 * A2UI surface 渲染卡片（挂在 customToolRenderConfig 的三个工具名上）：
 * - a2ui_render / a2ui_present：信封 = TOOL_CALL_RESULT 文本（content[1].data.output），
 *   流式中途从 arguments.components 容错预览；
 * - a2ui_ask_user_question：工具挂起无 result，信封 = interrupt message（askUserStore），
 *   历史回放拿不到 message 时从入参 questions 前端重建表单；
 *   提交 → resume payload = {字段name: 答案} 对象（后端 JSON 序列化为工具结果）。
 * 提交派发：ask 未决走 resolveInterrupt（带 resume 恢复）；展示卡走普通消息发送。
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

const TOOL_ASK = 'a2ui_ask_user_question';

/** 恢复工具结果 = 答案 JSON 文本（历史回放）；信封之外的 object 才认 */
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
  return String(v ?? '');
}

export function A2uiCard({ data, readOnly }: { data: RuntimeMessageLike; readOnly?: boolean }) {
  const name = data?.content?.[0]?.data?.name ?? '';
  const callId = data?.content?.[0]?.data?.call_id ?? '';
  const args = data?.content?.[0]?.data?.arguments ?? '';
  const output = data?.content?.[1]?.data?.output;
  const loading = data?.status === 'in_progress' && output === undefined;

  const isAsk = name === TOOL_ASK;
  const pending = isPendingToolCall(callId);
  const answeredPayload = getAnsweredPayload(callId);
  const answeredText = getAnsweredSelection(callId);
  const env = parseEnvelope(output);
  const interruptEnv = isAsk && pending ? parseEnvelope(getInterruptMessage(callId)) : null;

  const components: A2uiComponent[] = useMemo(() => {
    if (isAsk) {
      return interruptEnv?.components ?? questionsToComponents(extractQuestions(args));
    }
    return env?.components ?? (extractObjects(args, 'components').filter(
      (c) => typeof c.component === 'string',
    ) as unknown as A2uiComponent[]);
  }, [isAsk, interruptEnv, env, args]);

  const fields = useMemo(() => collectFields(components), [components]);

  const answered = answeredPayload ?? parseAnswersJson(output) ?? undefined;
  const interactive =
    !readOnly && (isAsk ? pending && !answeredPayload : !loading);

  const [values, setValues] = useState<Record<string, unknown>>({});
  const [submitError, setSubmitError] = useState('');

  const errorText = !env && !interruptEnv ? toolErrorText(output) : null;

  if (errorText) {
    return (
      <CardShell header={headerRow(<AppstoreOutlined />, 'A2UI', <span style={{ color: '#ff4d4f' }}>渲染失败</span>)}>
        <div style={{ fontSize: 12.5, color: 'rgba(26, 26, 29, 0.65)', wordBreak: 'break-word' }}>{errorText}</div>
      </CardShell>
    );
  }
  if (!components.length) return null;

  const onSubmit = () => {
    if (!interactive) return;
    const answers: Record<string, unknown> = {};
    const lines: string[] = [];
    for (const f of fields) {
      const v = values[f.name];
      const empty = v === undefined || v === '' || (Array.isArray(v) && !v.length);
      if (f.required && empty) {
        setSubmitError(`请填写「${f.label}」`);
        return;
      }
      if (empty) continue;
      answers[f.name] = v;
      lines.push(`${f.label}: ${fmtAnswer(v)}`);
    }
    setSubmitError('');
    const displayText = lines.join('\n') || '已提交';
    if (isAsk) {
      resolveInterrupt(callId, answers, displayText);
    } else if (fields.length) {
      submitMessage(displayText);
    }
  };

  const ctx = {
    values: answered && !interactive ? (answeredPayload ?? parseAnswersJson(output) ?? {}) : values,
    setValue: (n: string, v: unknown) => {
      setSubmitError('');
      setValues((prev) => ({ ...prev, [n]: v }));
    },
    disabled: !interactive,
  };

  const chip = isAsk
    ? answeredText !== undefined || answered
      ? <span style={{ color: '#52c41a' }}>已回答</span>
      : pending
        ? <span style={{ color: 'rgba(26, 26, 29, 0.45)' }}>请填写表单</span>
        : <span style={{ color: 'rgba(26, 26, 29, 0.35)' }}>已跳过</span>
    : loading
      ? <span style={{ color: 'rgba(26, 26, 29, 0.45)' }}>界面生成中…</span>
      : (env?.messageType === 'updateComponents'
        ? <span style={{ color: 'rgba(26, 26, 29, 0.45)' }}>界面更新</span>
        : <span style={{ color: 'rgba(26, 26, 29, 0.35)' }}>surface {env?.surfaceId ?? ''}</span>);

  return (
    <CardShell
      header={headerRow(
        isAsk ? <QuestionCircleOutlined /> : <AppstoreOutlined />,
        isAsk ? '请确认' : 'A2UI 界面',
        chip,
      )}
    >
      <A2uiSurfaceView components={components} ctx={ctx} onSubmit={onSubmit} />
      {submitError ? (
        <div style={{ fontSize: 12, color: '#ff4d4f', marginTop: 6 }}>{submitError}</div>
      ) : null}
    </CardShell>
  );
}

/** 历史回放挂载：只读（禁交互，但已回答表单仍回显答案） */
export function A2uiCardReadOnly(props: { data: RuntimeMessageLike }) {
  return <A2uiCard {...props} readOnly />;
}
