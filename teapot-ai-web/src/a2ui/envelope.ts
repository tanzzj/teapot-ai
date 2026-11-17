import { str } from './values';

/**
 * 官方 A2UI v1.0 信封：工具结果文本就是 `{"messages":[{"version":"1.0","<operation>":{…}}, …]}`。
 * 外壳与每条消息都是 strict（additionalProperties:false），所以这里只认官方操作名与 "1.0" 版本串；
 * 旧的单层信封（顶层 protocolVersion/messageType/components）一律解析失败，由调用方回落默认
 * ToolCall 块——历史卡片显示原始 JSON，不做兼容转换。
 */

export const A2UI_OPERATIONS = [
  'createSurface',
  'updateComponents',
  'updateDataModel',
  'deleteSurface',
  'callRendererFunction',
  'agentFunctionResponse',
] as const;

export type A2uiOperation = (typeof A2UI_OPERATIONS)[number];

export interface A2uiEnvelope {
  messages: Record<string, unknown>[];
}

function operationOf(message: Record<string, unknown>): A2uiOperation | undefined {
  return A2UI_OPERATIONS.find((op) => !!message[op] && typeof message[op] === 'object');
}

/** 严格解析信封（TOOL_CALL_RESULT / interrupt message 的完整文本）；非官方形态返回 null */
export function parseEnvelope(text?: string): A2uiEnvelope | null {
  if (!text) return null;
  const t = text.trim();
  if (!t.startsWith('{')) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(t);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== 'object') return null;
  const messages = (parsed as { messages?: unknown }).messages;
  if (!Array.isArray(messages) || !messages.length) return null;
  for (const message of messages) {
    if (!message || typeof message !== 'object') return null;
    const msg = message as Record<string, unknown>;
    if (msg.version !== '1.0' || !operationOf(msg)) return null;
  }
  return { messages: messages as Record<string, unknown>[] };
}

/** 首条消息的操作名：createSurface = 新卡，updateComponents = 同轮更新 */
export function envelopeOperation(env: A2uiEnvelope): A2uiOperation | undefined {
  return operationOf(env.messages[0]);
}

export function envelopeSurfaceId(env: A2uiEnvelope): string | undefined {
  const op = envelopeOperation(env);
  if (!op) return undefined;
  return str((env.messages[0][op] as Record<string, unknown>).surfaceId);
}

/**
 * 后端盖在 root 组件上的告知性备注（metadata.extensions.agentscopeNote）。
 * 目前唯一来源是同轮幂等：模型重复调用 a2ui_render 时后端不再渲染，
 * 原样退回已交付的信封并盖这条备注——所以带备注的信封是重复调用，界面已在上一张卡上。
 */
export function envelopeNote(env: A2uiEnvelope): string | undefined {
  const op = envelopeOperation(env);
  if (!op) return undefined;
  const components = (env.messages[0][op] as Record<string, unknown>).components;
  if (!Array.isArray(components)) return undefined;
  for (const component of components) {
    if (!component || typeof component !== 'object') continue;
    const c = component as Record<string, unknown>;
    if (c.id !== 'root' || !c.metadata || typeof c.metadata !== 'object') continue;
    const extensions = (c.metadata as Record<string, unknown>).extensions;
    if (!extensions || typeof extensions !== 'object') continue;
    return str((extensions as Record<string, unknown>).agentscopeNote);
  }
  return undefined;
}

/** 工具错误结果（"Error: Invalid A2UI: ..." 等），非信封文本才检查 */
export function toolErrorText(output?: string): string | null {
  if (typeof output === 'string' && output.trimStart().startsWith('Error')) return output.trim();
  return null;
}
