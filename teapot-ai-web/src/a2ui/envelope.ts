/**
 * A2UI「1.0」信封协议解析（与后端 agentscope-extensions-a2ui 契约对齐）：
 * 工具结果文本就是信封 JSON（protocolVersion/messageType/surfaceId/catalogId/components），
 * 校验失败时是 `Error: ` 前缀的纯文本。components 为扁平列表（无嵌套 children），
 * Row/Column/Divider 通过流式排版组合（见 flowLayout）。
 */

export interface A2uiComponent {
  id?: string;
  component: string;
  props?: Record<string, unknown>;
}

export interface A2uiEnvelope {
  protocolVersion?: string;
  /** createSurface = 本轮首个信封；updateComponents = 同轮后续更新（surfaceId 复用） */
  messageType?: string;
  surfaceId?: string;
  catalogId?: string;
  components: A2uiComponent[];
}

/** 严格解析信封（用于 TOOL_CALL_RESULT / interrupt message 等完整文本） */
export function parseEnvelope(text?: string): A2uiEnvelope | null {
  if (!text) return null;
  const t = text.trim();
  if (!t.startsWith('{')) return null;
  try {
    const obj = JSON.parse(t) as A2uiEnvelope;
    if (obj?.protocolVersion && Array.isArray(obj.components)) return obj;
  } catch {
    // 非 JSON 或不是信封
  }
  return null;
}

/** 工具错误结果（"Error: Invalid A2UI: ..." 等），非信封文本才检查 */
export function toolErrorText(output?: string): string | null {
  if (typeof output === 'string' && output.trimStart().startsWith('Error')) return output.trim();
  return null;
}

/** 定位 `"key":[ ... ]` 的数组体（闭合取到 ] 前；流式未闭合取到结尾） */
function arraySlice(src: string, key: string): string | null {
  const m = new RegExp(`"${key}"\\s*:\\s*\\[`).exec(src);
  if (!m) return null;
  let depth = 1;
  let inStr = false;
  let esc = false;
  const start = m.index + m[0].length;
  for (let i = start; i < src.length; i++) {
    const ch = src[i];
    if (inStr) {
      if (esc) esc = false;
      else if (ch === '\\') esc = true;
      else if (ch === '"') inStr = false;
      continue;
    }
    if (ch === '"') inStr = true;
    else if (ch === '[' || ch === '{') depth++;
    else if (ch === ']' || ch === '}') {
      depth--;
      if (depth === 0) return src.slice(start, i);
    }
  }
  return src.slice(start);
}

/**
 * 流式容错提取顶层对象数组（arguments 中途不完整时逐个截取配平的 {...}）：
 * 先整段 JSON.parse，失败退化为扫描——与 AskUserCard 的容错思路一致。
 */
export function extractObjects(src: string, key: string): Record<string, unknown>[] {
  if (!src) return [];
  try {
    const obj = JSON.parse(src) as Record<string, unknown>;
    const arr = obj?.[key];
    if (Array.isArray(arr)) {
      return arr.filter((x): x is Record<string, unknown> => !!x && typeof x === 'object');
    }
  } catch {
    // 流式未完成，走截取路径
  }
  const slice = arraySlice(src, key);
  if (slice === null) return [];
  const items: string[] = [];
  let depth = 0;
  let inStr = false;
  let esc = false;
  let start = 0;
  for (let i = 0; i < slice.length; i++) {
    const ch = slice[i];
    if (inStr) {
      if (esc) esc = false;
      else if (ch === '\\') esc = true;
      else if (ch === '"') inStr = false;
      continue;
    }
    if (ch === '"') inStr = true;
    else if (ch === '{' || ch === '[') {
      if (depth === 0) start = i;
      depth++;
    } else if (ch === '}' || ch === ']') {
      depth--;
      if (depth === 0 && i > start) items.push(slice.slice(start, i + 1));
    }
  }
  const out: Record<string, unknown>[] = [];
  for (const it of items) {
    try {
      const o = JSON.parse(it) as unknown;
      if (o && typeof o === 'object') out.push(o as Record<string, unknown>);
    } catch {
      // 尾部未闭合的对象，流式中途自然跳过
    }
  }
  return out;
}

export const str = (v: unknown): string | undefined => (typeof v === 'string' ? v : undefined);
export const bool = (v: unknown): boolean => v === true;
export const num = (v: unknown, d: number): number =>
  typeof v === 'number' && Number.isFinite(v) ? v : d;
export const strList = (v: unknown): string[] =>
  Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];
