/**
 * A2UI 属性取值的宽松 coercion：自定义 catalog 组件拿到的是渲染器解析后的字面量
 * （数据绑定与函数调用已由官方渲染器求值），模型仍可能给出 number/boolean 混用，
 * 因此统一在这里收口，避免每个组件各写一套判空。
 */

export const str = (v: unknown): string | undefined => (typeof v === 'string' ? v : undefined);
export const bool = (v: unknown): boolean => v === true;
export const num = (v: unknown, d: number): number =>
  typeof v === 'number' && Number.isFinite(v) ? v : d;
export const strList = (v: unknown): string[] =>
  Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];