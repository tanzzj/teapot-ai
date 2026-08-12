import { useCallback, useRef, useState } from 'react';
import { ACCESS_TOKEN_KEY } from '../api/http';

/** AG-UI 事件类型（与后端 agentscope-agui starter 对齐，SPEC §12.3） */
export interface AguiEvent {
  type: string;
  [key: string]: unknown;
}

/** 发给 /agui/run 的消息（AguiMessage：content 支持纯字符串） */
export interface AguiMessageInput {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
}

export interface ToolCallState {
  toolCallId: string;
  name: string;
  args: string;
  result?: string;
}

export interface AguiUiState {
  answer: string;
  reasoning: string;
  toolCalls: ToolCallState[];
  error?: string;
  finished: boolean;
  tokenUsage?: Record<string, unknown>;
}

const INITIAL_STATE: AguiUiState = {
  answer: '',
  reasoning: '',
  toolCalls: [],
  finished: false,
};

/**
 * 自研 AG-UI SSE 消费 hook（SPEC §12.1 / §12.3）：
 * POST /agui/run/{agentId}，body = RunAgentInput{threadId,runId,messages}
 */
export function useAguiRun() {
  const [ui, setUi] = useState<AguiUiState>(INITIAL_STATE);
  const abortRef = useRef<AbortController | null>(null);

  const run = useCallback(async (agentKey: string, threadId: string, messages: AguiMessageInput[]) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setUi({ ...INITIAL_STATE });

    const runId = `run-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    try {
      const resp = await fetch(`/agui/run/${encodeURIComponent(agentKey)}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem(ACCESS_TOKEN_KEY) || ''}`,
          'X-Agent-Id': agentKey,
        },
        body: JSON.stringify({ threadId, runId, messages }),
        signal: controller.signal,
      });
      if (!resp.ok || !resp.body) {
        setUi((s) => ({ ...s, error: `请求失败（HTTP ${resp.status}）`, finished: true }));
        return;
      }
      const reader = resp.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let idx = buffer.indexOf('\n');
        while (idx >= 0) {
          const rawLine = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 1);
          const line = rawLine.replace(/\r$/, '');
          if (line.startsWith('data:')) {
            applyEvent(line.slice(5).trim(), setUi);
          }
          idx = buffer.indexOf('\n');
        }
      }
      setUi((s) => (s.finished ? s : { ...s, finished: true }));
    } catch (e) {
      if ((e as Error).name === 'AbortError') {
        setUi((s) => ({ ...s, finished: true }));
      } else {
        setUi((s) => ({ ...s, error: (e as Error).message || '网络错误', finished: true }));
      }
    }
  }, []);

  const abort = useCallback(() => abortRef.current?.abort(), []);
  const reset = useCallback(() => setUi(INITIAL_STATE), []);

  return { ui, run, abort, reset };
}

function applyEvent(payload: string, setUi: React.Dispatch<React.SetStateAction<AguiUiState>>) {
  if (!payload || payload === '[DONE]') return;
  let raw: AguiEvent;
  try {
    raw = JSON.parse(payload);
  } catch {
    return;
  }
  // 后端事件信封：{type, threadId, runId, event?...}；业务字段可能在顶层也可能在 event 子载荷里
  const ev: AguiEvent = {
    ...(typeof raw.event === 'object' && raw.event !== null ? (raw.event as AguiEvent) : {}),
    ...raw,
  };
  const delta = () => String(ev.delta ?? '');
  switch (ev.type) {
    case 'TEXT_MESSAGE_CONTENT':
    case 'TEXT_MESSAGE_CHUNK':
      setUi((s) => ({ ...s, answer: s.answer + delta() }));
      break;
    case 'REASONING_MESSAGE_CONTENT':
    case 'REASONING_MESSAGE_CHUNK':
      setUi((s) => ({ ...s, reasoning: s.reasoning + delta() }));
      break;
    case 'TOOL_CALL_START':
      setUi((s) => ({
        ...s,
        toolCalls: [
          ...s.toolCalls,
          { toolCallId: String(ev.toolCallId ?? ''), name: String(ev.toolCallName ?? ''), args: '' },
        ],
      }));
      break;
    case 'TOOL_CALL_ARGS':
    case 'TOOL_CALL_CHUNK':
      setUi((s) => ({
        ...s,
        toolCalls: s.toolCalls.map((t) =>
          t.toolCallId === ev.toolCallId ? { ...t, args: t.args + delta() } : t,
        ),
      }));
      break;
    case 'TOOL_CALL_RESULT':
      setUi((s) => ({
        ...s,
        toolCalls: s.toolCalls.map((t) =>
          t.toolCallId === ev.toolCallId ? { ...t, result: String(ev.content ?? '') } : t,
        ),
      }));
      break;
    case 'RUN_ERROR':
      setUi((s) => ({ ...s, error: String(ev.message ?? ev.error ?? '执行失败'), finished: true }));
      break;
    case 'RUN_FINISHED':
      setUi((s) => ({ ...s, finished: true }));
      break;
    case 'CUSTOM':
      if (ev.name === 'token_usage' && ev.value) {
        setUi((s) => ({ ...s, tokenUsage: ev.value as Record<string, unknown> }));
      }
      break;
    case 'RAW': {
      // 未被适配器转换的原生事件：error 提示直接展示，其余忽略
      const err = ev.error ?? (ev.event as AguiEvent | undefined)?.error;
      if (err) {
        setUi((s) => ({ ...s, error: String(err), finished: true }));
      }
      break;
    }
    default:
      break;
  }
}
