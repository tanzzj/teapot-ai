import { ACCESS_TOKEN_KEY } from '../api/http';
import { consumeResume, registerInterrupts, resetForRun } from './askUserStore';

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

interface ToolState {
  name: string;
  args: string;
  result?: string;
}

/** 单个 thinking block（一次 run 内按顺序产生多个，逐个独立渲染，不再合并） */
interface ReasoningBlock {
  id: string;
  text: string;
}

/** 单次 run 的累积状态（每个新请求重建） */
interface RunState {
  answerStarted: boolean;
  answerText: string;
  /** 按顺序累积的全部 thinking block */
  reasoningBlocks: ReasoningBlock[];
  /** 当前正在流式输出的 block（null = 下一段 CONTENT 开新块） */
  reasoningActive: ReasoningBlock | null;
  /** 块序号：本地生成唯一消息 id（不复用后端 messageId，避免同 msg 多块冲突） */
  reasoningSeq: number;
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
    reasoningBlocks: [],
    reasoningActive: null,
    reasoningSeq: 0,
    tools: new Map(),
    toolOrder: [],
    usage: undefined,
  };

  /** 关闭当前活跃 thinking block：返回全量 completed 内容更新（无活跃块返回 null） */
  function closeActiveReasoning(): RuntimeChunk | null {
    const prev = state.reasoningActive;
    state.reasoningActive = null;
    if (!prev || prev.text === '') return null;
    return textContent(prev.id, prev.text, true);
  }

  /** 新开一个 thinking block：本地序号生成唯一消息 id，按序登记 */
  function openReasoningBlock(): ReasoningBlock {
    const block: ReasoningBlock = { id: `asst-reasoning-${state.reasoningSeq++}`, text: '' };
    state.reasoningBlocks.push(block);
    state.reasoningActive = block;
    return block;
  }

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

      case 'REASONING_MESSAGE_START': {
        // 新 thinking block 开始：先收尾上一块（正常链路上一块已有 END，此处为容错）
        return closeActiveReasoning() ?? heartbeat();
      }

      case 'REASONING_MESSAGE_CONTENT':
      case 'REASONING_MESSAGE_CHUNK': {
        // 无活跃块 = 新的 thinking block（START 缺失或上一块已 END）：按序开新消息
        const block = state.reasoningActive ?? openReasoningBlock();
        const isNew = block.text === '';
        block.text += delta;
        if (isNew) {
          // 块首段：先建立消息本体，后续 content 增量按 msg_id 寻址
          return {
            object: 'message',
            id: block.id,
            role: 'assistant',
            type: 'reasoning',
            status: 'in_progress',
            content: [textContent(block.id, block.text, false)],
          };
        }
        return textContent(block.id, delta, false);
      }

      case 'REASONING_MESSAGE_END': {
        return closeActiveReasoning() ?? heartbeat();
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
        for (const block of state.reasoningBlocks) {
          output.push({
            object: 'message',
            id: block.id,
            role: 'assistant',
            type: 'reasoning',
            status: 'completed',
            content: [textContent(block.id, block.text, true)],
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
        // 中断登记（ask_user_question 等工具挂起）：outcome={type:'interrupt', interrupts:[...]}；
        // 全量登记——AguiResumeCoordinator 要求下一次请求的 resume[] 精确覆盖所有未决中断
        resetForRun();
        const outcome = ev.outcome as { type?: string; interrupts?: Record<string, unknown>[] } | undefined;
        if (outcome?.type === 'interrupt' && Array.isArray(outcome.interrupts)) {
          registerInterrupts(outcome.interrupts.map((i) => ({
            interruptId: String(i.id ?? ''),
            toolCallId: String(i.toolCallId ?? ''),
            toolName: String((i.metadata as Record<string, unknown> | undefined)?.toolName ?? ''),
          })));
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
        // 无 object 字段 → Builder 进入 failed 分支并渲染错误卡片；
        // 合约冲突重试预算耗尽后落到这里，给中文可读提示（SPEC §22.6）
        const rawCode = String(ev.code ?? 'RUN_ERROR');
        const rawMsg = String(ev.message ?? ev.error ?? '执行失败');
        if (CONTRACT_ERROR_PATTERN.test(rawCode) || CONTRACT_ERROR_PATTERN.test(rawMsg)) {
          return {
            code: rawCode,
            message: '上一轮回复仍在后台收尾（记忆整理/沙箱快照），等待超时。请稍等几秒后重新发送。',
          };
        }
        return { code: rawCode, message: rawMsg };
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
  /**
   * 请求级记忆模式开关（SPEC §25）：经 RunAgentInput.forwardedProps.memoryMode 传给后端，
   * 覆盖 Agent 配置；返回 undefined = 不传，跟随 Agent 配置。
   */
  getMemoryMode?: () => boolean | undefined;
  /**
   * 请求级计划模式开关（SPEC §25）：经 RunAgentInput.forwardedProps.planMode 传给后端，
   * 覆盖 Agent 配置；返回 undefined = 不传，跟随 Agent 配置。
   */
  getPlanMode?: () => boolean | undefined;
  /**
   * 请求级权限模式开关：经 RunAgentInput.forwardedProps.permissionMode 传给后端，
   * 优先级高于 Agent 配置；返回 undefined = 不传，跟随 Agent 配置。
   */
  getPermissionMode?: () => string | undefined;
}

/**
 * AG-UI 合约冲突（AGUI_INTERRUPT_CONTRACT_ERROR）：同 thread 已有活跃 run 时新请求被拒。
 * 成因：后端回复文本流式结束后，harness 尾部后置处理（记忆 flush/整合、会话持久化、
 * 沙箱快照）仍在执行，finishRun 挂接在事件流 doFinally，最长可达数分钟；此间用户已看到
 * 完整回复并发送下一条 → 被合约拦截。尾部终会完成，故此处静默退避重试，
 * 对 UI 表现为持续的「思考中」，预算耗尽才落错误卡片（SPEC §22.6）。
 */
const CONTRACT_ERROR_PATTERN = /AGUI_INTERRUPT_CONTRACT_ERROR|already has an active run/i;
/** 合约重试总预算：覆盖实测最长约 3.6min 的尾部后置处理 */
const CONTRACT_RETRY_BUDGET_MS = 5 * 60 * 1000;
/** 首次重试等待，后续按 1.6 倍递增封顶 30s */
const CONTRACT_RETRY_INITIAL_DELAY_MS = 3000;

/** 可被 AbortSignal 打断的等待；abort 时以 AbortError 拒绝（模板按取消处理） */
function sleepInterruptible(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason ?? new DOMException('Aborted', 'AbortError'));
      return;
    }
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener('abort', () => {
      clearTimeout(timer);
      reject(signal.reason ?? new DOMException('Aborted', 'AbortError'));
    }, { once: true });
  });
}

/**
 * 探测响应是否为合约冲突：合约错误流固定为 RUN_STARTED → RUN_ERROR → RUN_FINISHED 三个短事件（约 1KB），
 * 首事件不含特征，需扫到特征串或流结束；累计超阈值仍未命中则必是正常业务流，提前放行。
 * 非合约路径通过 ReadableStream 回放已消费字节，调用方拿到与原响应等价的对象。
 */
async function peekContractError(resp: Response): Promise<{ retry: boolean; response: Response; firstData: string }> {
  const body = resp.body;
  if (!body) return { retry: false, response: resp, firstData: '' };
  const reader = body.getReader();
  const decoder = new TextDecoder();
  const chunks: Uint8Array[] = [];
  let buffer = '';
  let firstData = '';
  let verdict: 'retry' | 'pass' | null = null;
  while (!verdict) {
    const { done, value } = await reader.read();
    if (value) {
      chunks.push(value);
      buffer += decoder.decode(value, { stream: true });
    }
    if (CONTRACT_ERROR_PATTERN.test(buffer)) {
      verdict = 'retry';
      break;
    }
    // 合约错误流仅约 1KB（三个短事件）；累计超过阈值仍未命中 → 必是正常业务流，提前放行减少缓存
    if (buffer.length > 8192) {
      verdict = 'pass';
      break;
    }
    if (done) {
      verdict = 'pass';
      break;
    }
  }
  // 预算耗尽重建错误响应时取含特征的 RUN_ERROR 行（首行是 RUN_STARTED，不含错误信息）
  const errLine = buffer.split('\n').find(l => l.startsWith('data:') && CONTRACT_ERROR_PATTERN.test(l));
  if (errLine) firstData = errLine.slice(5).trim();
  const retry = verdict === 'retry';
  const replayStream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const c of chunks) controller.enqueue(c);
    },
    async pull(controller) {
      const { done, value } = await reader.read();
      if (done) {
        controller.close();
        return;
      }
      if (value) controller.enqueue(value);
    },
    cancel(reason) {
      return reader.cancel(reason);
    },
  });
  const response = new Response(replayStream, {
    status: resp.status,
    statusText: resp.statusText,
    headers: resp.headers,
  });
  if (retry) {
    // 重试路径下原响应不再使用，主动释放连接（预算耗尽时用 firstData 重建响应，不依赖回放）
    void reader.cancel().catch(() => {});
  }
  return { retry, response, firstData };
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
      content?: { type?: string; text?: string; image_url?: string; video_url?: string }[];
    } | undefined;
    // 按 AG-UI InputContent 协议转 parts（SPEC §19 多模态）：
    //   text 段照旧；图片/视频转 {type:'image'|'video', source:{type:'data'|'url', ...}}
    // 模板 RequestBuilder 产出的附件 part 形如 {type:'image', image_url} / {type:'video', video_url}，
    // url 为前端本地压缩后的 data URL（base64 链路）或 OSS 直链（oss 链路，云模型可回访问）
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
      } else if (c?.type === 'video' && c.video_url) {
        const dataUrl = /^data:(video\/[a-z0-9.+-]+);base64,(.+)$/i.exec(c.video_url);
        if (dataUrl) {
          parts.push({
            type: 'video',
            source: { type: 'data', mimeType: dataUrl[1], value: dataUrl[2] },
          });
        } else {
          parts.push({ type: 'video', source: { type: 'url', value: c.video_url } });
        }
      }
    }
    if (parts.length === 0) {
      parts.push({ type: 'text', text: '' });
    }

    const threadId = opts.getSessionId() || `thread-${Date.now()}`;
    const memoryMode = opts.getMemoryMode?.();
    const planMode = opts.getPlanMode?.();
    const permissionMode = opts.getPermissionMode?.();
    const url = `/agui/run/${encodeURIComponent(opts.agentKey)}`;
    // 未决中断恢复（ask_user_question）：点选答案携带 resolved；用户直接打字则全部 cancelled 兜底。
    // 在重试循环外消费一次，合约冲突重试复用同一份，避免重复消费
    const resume = consumeResume();
    const deadline = Date.now() + CONTRACT_RETRY_BUDGET_MS;
    let delay = CONTRACT_RETRY_INITIAL_DELAY_MS;
    for (;;) {
      const runId = `run-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      const resp = await fetch(url, {
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
          // 未决中断恢复：携带则后端按 resume 契约恢复被挂起的工具（如 ask_user_question）
          ...(resume ? { resume } : {}),
          // 请求级开关（SPEC §25）：仅显式选择时才携带，后端缺失 = 跟随 Agent 配置
          ...((memoryMode === undefined && planMode === undefined && !permissionMode)
            ? {}
            : { forwardedProps: {
                ...(memoryMode !== undefined ? { memoryMode } : {}),
                ...(planMode !== undefined ? { planMode } : {}),
                ...(permissionMode ? { permissionMode } : {}),
              } }),
        }),
        signal,
      });
      // 非 2xx 或无流：维持原行为交由上层处理（合约冲突走 200 + SSE 错误事件）
      if (!resp.ok || !resp.body) return resp;
      const peek = await peekContractError(resp);
      if (!peek.retry) return peek.response;
      if (Date.now() + delay > deadline) {
        // 预算耗尽：用捕获的合约错误事件重建 SSE 响应 → RUN_ERROR 分支渲染中文提示卡片
        return contractErrorResponse(peek.firstData);
      }
      console.warn(`[aguiBridge] 上一轮 run 仍在收尾，${Math.round(delay / 1000)}s 后重试 threadId=${threadId}`);
      await sleepInterruptible(delay, signal);
      delay = Math.min(Math.round(delay * 1.6), 30000);
    }
  };
}

/** 预算耗尽时用捕获的合约错误事件构造 SSE 响应（原连接已释放，不可回放） */
function contractErrorResponse(firstData: string): Response {
  const payload = firstData || '{"type":"RUN_ERROR","code":"AGUI_INTERRUPT_CONTRACT_ERROR","message":"previous run still finishing"}';
  return new Response(`data:${payload}\n\n`, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  });
}
