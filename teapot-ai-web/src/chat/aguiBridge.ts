import { ACCESS_TOKEN_KEY } from '../api/http';

/**
 * AG-UI ↔ AgentScope Runtime WebUI 协议桥接（参照 sparkdesign chat template）。
 *
 * AgentScopeRuntimeWebUI 的消费协议（AgentScopeRuntimeResponseBuilder）：
 *   - { object: 'response', ... }  全量/增量响应（status / usage / output 按 id 合并）
 *   - { object: 'message', ... }   一条消息（id 去重，content 为空时保留已有 content）
 *   - { object: 'content', ... }   某个消息的内容增量（msg_id 寻址，delta:true 时与上一段拼接）
 *   - 其他                          视为错误（进入 failed 态并渲染错误卡片）
 *
 * 我们后端是 AG-UI SSE：POST /agui/run/{agentKey}，事件 TEXT_MESSAGE_CONTENT /
 * REASONING_MESSAGE_CONTENT / TOOL_CALL_* / RUN_FINISHED / RUN_ERROR 等。
 * 本模块把每个 AG-UI 事件翻译成 Builder 可消费的对象。
 */

const ANSWER_MSG_ID = 'asst-answer';
const REASONING_MSG_ID = 'asst-reasoning';

interface ToolState {
  name: string;
  args: string;
  result?: string;
}

/** 单次 run 的累积状态（每个新请求重建） */
interface RunState {
  answerStarted: boolean;
  answerText: string;
  reasoningStarted: boolean;
  reasoningText: string;
  tools: Map<string, ToolState>;
  toolOrder: string[];
  usage?: Record<string, unknown>;
}

type RuntimeChunk = Record<string, unknown>;

function heartbeat(): RuntimeChunk {
  // Builder 对 heartbeat 消息直接忽略（返回当前累积），用于「无需 UI 更新」的事件
  return {
    object: 'message',
    type: 'heartbeat',
    id: 'hb',
    role: 'assistant',
    status: 'in_progress',
    content: [],
  };
}

function textContent(msgId: string, text: string, done: boolean) {
  return {
    object: 'content',
    type: 'text',
    text,
    delta: !done,
    status: done ? 'completed' : 'in_progress',
    msg_id: msgId,
  };
}

/** 创建一次 run 的有状态解析器：输入一行 SSE data（JSON 字符串），输出 Builder 可消费对象 */
function createRunParser() {
  const state: RunState = {
    answerStarted: false,
    answerText: '',
    reasoningStarted: false,
    reasoningText: '',
    tools: new Map(),
    toolOrder: [],
    usage: undefined,
  };

  return function parse(rawLine: string): RuntimeChunk {
    if (!rawLine || rawLine === '[DONE]') return heartbeat();
    let raw: Record<string, unknown>;
    try {
      raw = JSON.parse(rawLine);
    } catch {
      return heartbeat();
    }
    // 后端事件信封：业务字段可能在顶层，也可能在 event 子载荷里
    const nested = typeof raw.event === 'object' && raw.event !== null
      ? (raw.event as Record<string, unknown>)
      : {};
    const ev: Record<string, unknown> = { ...nested, ...raw };
    const type = String(ev.type ?? '');
    const delta = String(ev.delta ?? '');

    switch (type) {
      case 'TEXT_MESSAGE_CONTENT':
      case 'TEXT_MESSAGE_CHUNK': {
        state.answerText += delta;
        if (!state.answerStarted) {
          state.answerStarted = true;
          // 首段：必须先建立消息本体，后续 content 增量才能按 msg_id 寻址
          return {
            object: 'message',
            id: ANSWER_MSG_ID,
            role: 'assistant',
            type: 'message',
            status: 'in_progress',
            content: [textContent(ANSWER_MSG_ID, state.answerText, false)],
          };
        }
        return textContent(ANSWER_MSG_ID, delta, false);
      }

      case 'REASONING_MESSAGE_CONTENT':
      case 'REASONING_MESSAGE_CHUNK': {
        state.reasoningText += delta;
        if (!state.reasoningStarted) {
          state.reasoningStarted = true;
          return {
            object: 'message',
            id: REASONING_MSG_ID,
            role: 'assistant',
            type: 'reasoning',
            status: 'in_progress',
            content: [textContent(REASONING_MSG_ID, state.reasoningText, false)],
          };
        }
        return textContent(REASONING_MSG_ID, delta, false);
      }

      case 'TOOL_CALL_START': {
        const callId = String(ev.toolCallId ?? `tool-${Date.now()}`);
        const name = String(ev.toolCallName ?? 'tool');
        state.tools.set(callId, { name, args: '' });
        state.toolOrder.push(callId);
        return {
          object: 'message',
          id: callId,
          role: 'assistant',
          type: 'tool_call',
          status: 'in_progress',
          content: [{
            object: 'content',
            type: 'data',
            delta: true,
            msg_id: callId,
            status: 'in_progress',
            data: { name, call_id: callId, arguments: '' },
          }],
        };
      }

      case 'TOOL_CALL_ARGS':
      case 'TOOL_CALL_CHUNK': {
        const callId = String(ev.toolCallId ?? '');
        const tool = state.tools.get(callId);
        if (!tool) return heartbeat();
        tool.args += delta;
        // Builder 对 tool_call 消息的 DATA 增量做同名字段字符串拼接
        return {
          object: 'content',
          type: 'data',
          delta: true,
          msg_id: callId,
          status: 'in_progress',
          data: { arguments: delta },
        };
      }

      case 'TOOL_CALL_RESULT': {
        const callId = String(ev.toolCallId ?? '');
        const tool = state.tools.get(callId);
        const result = String(ev.content ?? '');
        if (tool) tool.result = result;
        // 渲染期 mergeToolMessages 按 call_id 把 output 合并进 input 消息的 content[1]
        return {
          object: 'message',
          id: `${callId}-output`,
          role: 'assistant',
          type: 'tool_call_output',
          status: 'completed',
          content: [{
            object: 'content',
            type: 'data',
            msg_id: `${callId}-output`,
            status: 'completed',
            data: { name: tool?.name ?? 'tool', call_id: callId, output: result },
          }],
        };
      }

      case 'CUSTOM': {
        if (ev.name === 'token_usage' && ev.value) {
          state.usage = ev.value as Record<string, unknown>;
        }
        return heartbeat();
      }

      case 'RUN_FINISHED': {
        // 收尾：回传完整 output（全部置 completed），按 id 合并修正所有中间态
        const output: RuntimeChunk[] = [];
        if (state.reasoningStarted) {
          output.push({
            object: 'message',
            id: REASONING_MSG_ID,
            role: 'assistant',
            type: 'reasoning',
            status: 'completed',
            content: [textContent(REASONING_MSG_ID, state.reasoningText, true)],
          });
        }
        for (const callId of state.toolOrder) {
          const tool = state.tools.get(callId)!;
          output.push({
            object: 'message',
            id: callId,
            role: 'assistant',
            type: 'tool_call',
            status: 'completed',
            content: [{
              object: 'content',
              type: 'data',
              msg_id: callId,
              status: 'completed',
              data: { name: tool.name, call_id: callId, arguments: tool.args },
            }],
          });
          if (tool.result !== undefined) {
            output.push({
              object: 'message',
              id: `${callId}-output`,
              role: 'assistant',
              type: 'tool_call_output',
              status: 'completed',
              content: [{
                object: 'content',
                type: 'data',
                msg_id: `${callId}-output`,
                status: 'completed',
                data: { name: tool.name, call_id: callId, output: tool.result },
              }],
            });
          }
        }
        if (state.answerStarted) {
          output.push({
            object: 'message',
            id: ANSWER_MSG_ID,
            role: 'assistant',
            type: 'message',
            status: 'completed',
            content: [textContent(ANSWER_MSG_ID, state.answerText, true)],
          });
        }
        return {
          object: 'response',
          id: `resp-${Date.now()}`,
          status: 'completed',
          created_at: Math.floor(Date.now() / 1000),
          output,
          usage: state.usage,
        };
      }

      case 'RUN_ERROR': {
        // 无 object 字段 → Builder 进入 failed 分支并渲染错误卡片
        return {
          code: String(ev.code ?? 'RUN_ERROR'),
          message: String(ev.message ?? ev.error ?? '执行失败'),
        };
      }

      case 'RAW': {
        const err = ev.error ?? (nested as Record<string, unknown>).error;
        if (err) {
          return { code: 'RAW', message: String(err) };
        }
        return heartbeat();
      }

      default:
        return heartbeat();
    }
  };
}

/** 当前活跃请求的解析器（模板同一时刻只有一个活跃 SSE，cancel/新提交会先 abort 旧的） */
let activeParser: ((raw: string) => RuntimeChunk) | null = null;

/** 提供给 AgentScopeRuntimeWebUI options.api.responseParser（实际入参是每行 SSE data 字符串） */
export function aguiResponseParser(raw: string): RuntimeChunk {
  if (!activeParser) {
    activeParser = createRunParser();
  }
  return activeParser(raw);
}

export interface AguiFetchOptions {
  agentKey: string;
  /** 取当前会话 id（作 AG-UI threadId，多轮上下文由后端 StateStore 保证） */
  getSessionId: () => string | undefined;
}

/**
 * 提供给 AgentScopeRuntimeWebUI options.api.fetch：
 * 把模板的历史消息（AgentScope Runtime 格式）转成 AG-UI RunAgentInput 发起 SSE 请求。
 * 后端有会话状态持久化，只需携带最后一条用户消息（与模板 enableHistoryMessages=false 一致）。
 */
export function createAguiFetch(opts: AguiFetchOptions) {
  return async ({ input, signal }: { input: unknown[]; signal?: AbortSignal }): Promise<Response> => {
    // 每个新请求重置解析器状态
    activeParser = createRunParser();

    const last = input[input.length - 1] as {
      role?: string;
      content?: { type?: string; text?: string; image_url?: string }[];
    } | undefined;
    // 按 AG-UI InputContent 协议转 parts（SPEC §19 多模态）：
    //   text 段照旧；图片转 {type:'image', source:{type:'data'|'url', ...}}
    // 模板 RequestBuilder 产出的图片 part 形如 {type:'image', image_url}，
    // image_url 为前端本地压缩后的 data URL（云模型无法回访问内网，故不用 url 源）
    const parts: Record<string, unknown>[] = [];
    for (const c of last?.content ?? []) {
      if (c?.type === 'text') {
        parts.push({ type: 'text', text: String(c.text ?? '') });
      } else if (c?.type === 'image' && c.image_url) {
        const dataUrl = /^data:(image\/[a-z0-9.+-]+);base64,(.+)$/i.exec(c.image_url);
        if (dataUrl) {
          parts.push({
            type: 'image',
            source: { type: 'data', mimeType: dataUrl[1], value: dataUrl[2] },
          });
        } else {
          parts.push({ type: 'image', source: { type: 'url', value: c.image_url } });
        }
      }
    }
    if (parts.length === 0) {
      parts.push({ type: 'text', text: '' });
    }

    const runId = `run-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const threadId = opts.getSessionId() || `thread-${Date.now()}`;

    return fetch(`/agui/run/${encodeURIComponent(opts.agentKey)}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${localStorage.getItem(ACCESS_TOKEN_KEY) || ''}`,
        'X-Agent-Id': opts.agentKey,
      },
      body: JSON.stringify({
        threadId,
        runId,
        messages: [{ id: `msg-${Date.now()}`, role: 'user', content: parts }],
      }),
      signal,
    });
  };
}
