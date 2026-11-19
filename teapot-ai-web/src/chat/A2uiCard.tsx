import { Suspense, lazy, useMemo, useState } from 'react';
import { AppstoreOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import { Markdown, ToolCall } from '@agentscope-ai/chat';
import type { ActionPayload } from '@a2ui/web_core/v1_0';
import type { MarkdownComponentProps } from '@a2ui/react';
import {
  envelopeNote,
  envelopeOperation,
  envelopeSurfaceId,
  parseEnvelope,
  toolErrorText,
} from '../a2ui/envelope';
import { extractQuestions } from '../a2ui/askForm';
import { str, strList } from '../a2ui/values';
import {
  getAnsweredPayload,
  getInterruptMessage,
  isPendingToolCall,
  resolveInterrupt,
  submitMessage,
} from './askUserStore';
import { CardShell, headerRow } from './PlanCards';

/**
 * A2UI surface 渲染卡片（挂在 customToolRenderConfig 的 a2ui_render 与 ask 分发器上）：
 * - a2ui_render：surface 只渲染工具返回的官方 v1.0 信封
 *   （content[1].data.output = 信封 JSON）；未拿到信封（执行中的 toolcall、错误文本、
 *   旧版单层信封）一律回落到默认 ToolCall 块；入参只有自然语言 description
 *   （组件树由 SDK 内部的渲染子 Agent 生成）；带 agentscopeNote 的信封是同轮幂等退回的
 *   重复调用（界面与上一张卡逐字节相同），默认折成一行细条，可展开；
 * - ask_user_question 表单档（a2ui 开启时框架注册）：工具挂起无 result，
 *   信封 = interrupt message（askUserStore），由 AskQuestionCard 分流到这里；
 *   提交动作 → resume payload = {问题id: 答案} 对象（后端 JSON 序列化为工具结果）。
 * 组件渲染交给官方渲染器（懒加载，只有出现信封卡片时才拉 @a2ui/* 依赖），
 * 本文件只负责卡片外壳、状态 chip 与动作派发；标准 ToolCall 块（默认折叠，点开可见
 * 入参与原始信封 JSON）恒在上方，surface 卡渲染在它下方；同轮更新信封不建卡，增量应用到原卡。
 */

const A2uiRenderer = lazy(() => import('../a2ui/A2uiRenderer'));

const A2uiMarkdown = ({ markdown }: MarkdownComponentProps) => <Markdown content={markdown} />;

interface ToolData {
  name?: string;
  call_id?: string;
  server_label?: string;
  arguments?: string;
  output?: string;
}

interface RuntimeMessageLike {
  status?: string;
  content?: { data?: ToolData }[];
}

const TOOL_ASK = 'ask_user_question';

function fmtAnswer(v: unknown): string {
  if (Array.isArray(v)) return v.map(fmtAnswer).join('、');
  if (typeof v === 'boolean') return v ? '是' : '否';
  if (v === 'yes') return '是';
  if (v === 'no') return '否';
  return String(v ?? '');
}

export function A2uiCard({ data, readOnly }: { data: RuntimeMessageLike; readOnly?: boolean }) {
  const name = data?.content?.[0]?.data?.name ?? '';
  const callId = data?.content?.[0]?.data?.call_id ?? '';
  const args = data?.content?.[0]?.data?.arguments ?? '';
  const serverLabel = data?.content?.[0]?.data?.server_label;
  const output = data?.content?.[1]?.data?.output;
  const loading = data?.status === 'in_progress' && output === undefined;

  const isAsk = name === TOOL_ASK;
  const pending = isPendingToolCall(callId);
  const answeredPayload = getAnsweredPayload(callId);
  const env = useMemo(() => parseEnvelope(output), [output]);
  const interruptEnv = useMemo(
    () => (isAsk && pending ? parseEnvelope(getInterruptMessage(callId)) : null),
    [isAsk, pending, callId],
  );
  const envelope = isAsk ? interruptEnv : env;
  const note = envelope ? envelopeNote(envelope) : undefined;
  const [dupOpen, setDupOpen] = useState(false);
  // 提交动作只带回答键，气泡里的「问题: 答案」文本要回入参取标签
  const labels = useMemo(() => {
    const map = new Map<string, string>();
    for (const q of extractQuestions(args)) {
      const id = str(q.id);
      if (id) map.set(id, str(q.question) ?? id);
    }
    return map;
  }, [args]);

  const interactive = !readOnly && (isAsk ? pending && !answeredPayload : !loading);
  const errorText = !envelope ? toolErrorText(output) : null;

  // 工具调用的入参与原始结果始终随卡展示：界面渲染成功不等于调用细节可以藏起来，
  // 排查「渲染不完整」时要能直接对着 param/result 看。ask 挂起期没有 result，用表单信封顶上。
  const rawResult = output ?? (isAsk ? getInterruptMessage(callId) : undefined) ?? '';
  const toolCall = (
    <ToolCall
      title={serverLabel ? `${serverLabel} / ${name}` : name}
      loading={loading}
      defaultOpen={false}
      input={args}
      output={rawResult}
    />
  );

  // 非信封的 render/present 结果（执行中的 toolcall、错误文本、旧版信封）→ 默认 ToolCall 块：
  // toolcall 就按 toolcall 展示，surface 只在信封结果到达后渲染
  if (!isAsk && !env) {
    return toolCall;
  }

  if (errorText) {
    return (
      <>
        {toolCall}
        <CardShell header={headerRow(<AppstoreOutlined />, 'A2UI', <span style={{ color: '#ff4d4f' }}>渲染失败</span>)}>
          <div style={{ fontSize: 12.5, color: 'rgba(26, 26, 29, 0.65)', wordBreak: 'break-word' }}>{errorText}</div>
        </CardShell>
      </>
    );
  }
  if (!envelope) return null;

  // 同轮更新信封（force 重渲染产出 updateComponents/updateDataModel）：surface 在上一张卡上，
  // 这里把增量应用到共享 processor、只留一行痕迹，不另建卡——新 processor 找不到 surface
  // 会报 Surface not found。入参/原始结果仍随下面的 ToolCall 展示。
  if (!envelope.messages.some((m) => !!m.createSurface)) {
    return (
      <>
        {toolCall}
        <Suspense
          fallback={<div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.45)' }}>界面更新应用中…</div>}
        >
          <A2uiRenderer messages={envelope.messages} markdown={A2uiMarkdown} disabled />
        </Suspense>
      </>
    );
  }

  const onAction = (action: ActionPayload) => {
    if (!interactive) return;
    if (!isAsk) {
      const text = action.userMessage || action.name;
      if (text) submitMessage(text);
      return;
    }
    if (action.name !== 'submit') return;
    const raw = action.context?.answers;
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return;
    const multi = new Set(strList(action.context?.multiSelect));
    const answers: Record<string, unknown> = {};
    const lines: string[] = [];
    for (const [id, value] of Object.entries(raw as Record<string, unknown>)) {
      // 单选 picker 在数据模型里也是一元素数组，恢复载荷按 id→标量 读，只有 multiSelect 保留数组
      const v = !multi.has(id) && Array.isArray(value) && value.length === 1 ? value[0] : value;
      if (v === undefined || v === '' || (Array.isArray(v) && !v.length)) continue;
      answers[id] = v;
      lines.push(`${labels.get(id) ?? id}: ${fmtAnswer(v)}`);
    }
    resolveInterrupt(callId, answers, lines.join('\n') || '已提交');
  };

  // 同轮重复调用：界面已在上一张卡上，这里只留一行痕迹，展开才重建 surface
  if (note && !dupOpen) {
    return (
      <div
        onClick={() => setDupOpen(true)}
        title={note}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          margin: '2px 0',
          padding: '5px 12px',
          maxWidth: 640,
          borderRadius: 10,
          border: '1px dashed rgba(26, 26, 29, 0.14)',
          background: 'rgba(26, 26, 29, 0.02)',
          fontSize: 12,
          color: 'rgba(26, 26, 29, 0.45)',
          cursor: 'pointer',
          userSelect: 'none',
        }}
      >
        <AppstoreOutlined />
        重复调用已合并
        <span style={{ color: 'rgba(26, 26, 29, 0.3)' }}>
          surface {envelopeSurfaceId(envelope) ?? ''}
        </span>
        <span style={{ marginLeft: 'auto' }}>展开</span>
      </div>
    );
  }

  const chip = isAsk
    ? <span style={{ color: 'rgba(26, 26, 29, 0.45)' }}>请填写表单</span>
    : loading
      ? <span style={{ color: 'rgba(26, 26, 29, 0.45)' }}>界面生成中…</span>
      : note
        ? (
          <span
            onClick={() => setDupOpen(false)}
            style={{ color: 'rgba(26, 26, 29, 0.45)', cursor: 'pointer' }}
          >
            重复调用 · 收起
          </span>
        )
        : envelopeOperation(envelope) === 'updateComponents'
          ? <span style={{ color: 'rgba(26, 26, 29, 0.45)' }}>界面更新</span>
          : <span style={{ color: 'rgba(26, 26, 29, 0.35)' }}>surface {envelopeSurfaceId(envelope) ?? ''}</span>;

  return (
    <>
      {toolCall}
      <CardShell
        header={headerRow(
          isAsk ? <QuestionCircleOutlined /> : <AppstoreOutlined />,
          isAsk ? '请确认' : '',
          chip,
        )}
      >
        <Suspense
          fallback={<div style={{ fontSize: 12.5, color: 'rgba(26, 26, 29, 0.45)' }}>界面加载中…</div>}
        >
          <A2uiRenderer
            messages={envelope.messages}
            markdown={A2uiMarkdown}
            disabled={!interactive}
            onAction={onAction}
            onMessage={
              isAsk
                ? undefined
                : (text) => {
                    if (interactive && text) submitMessage(text);
                  }
            }
          />
        </Suspense>
      </CardShell>
    </>
  );
}

/** 历史回放挂载：只读（禁交互，但已回答表单仍回显答案） */
export function A2uiCardReadOnly(props: { data: RuntimeMessageLike }) {
  return <A2uiCard {...props} readOnly />;
}
