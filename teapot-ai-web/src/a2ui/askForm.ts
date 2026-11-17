import { str } from './values';

/**
 * ask_user_question 的入参（声明式表单 spec）解析。两档共用同一份入参：
 * a2ui 档由后端编译成官方 v1.0 表单信封，前端只渲染信封；纯文本档与历史回放
 * 没有信封，由 AskUserCard 直接按这里的 questions/frame 画问题卡并回显答案。
 */

export interface AskFormFrame {
  title?: string;
  description?: string;
  submitText?: string;
}

export function extractQuestions(args: string): Record<string, unknown>[] {
  return extractObjects(args, 'questions').filter((q) => !!str(q.id) && !!str(q.question));
}

/** 表单级字段（title/description/submitText）：整段可解析时取 JSON，流式中途退化成正则取串 */
export function extractFormFrame(args: string): AskFormFrame {
  return {
    title: extractStringArg(args, 'title'),
    description: extractStringArg(args, 'description'),
    submitText: extractStringArg(args, 'submitText'),
  };
}

function extractStringArg(src: string, key: string): string | undefined {
  if (!src) return undefined;
  try {
    return str((JSON.parse(src) as Record<string, unknown>)?.[key]);
  } catch {
    // 流式未完成：直接找已闭合的那个字符串字面量
  }
  const m = new RegExp(`"${key}"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"`).exec(src);
  if (!m) return undefined;
  try {
    return JSON.parse(`"${m[1]}"`) as string;
  } catch {
    return undefined;
  }
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
 * 先整段 JSON.parse，失败退化为扫描。
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
