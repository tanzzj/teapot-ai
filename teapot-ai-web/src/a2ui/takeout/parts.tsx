import { useState } from 'react';
import { str } from '../envelope';

/** 千问外卖卡共用视觉件：配色、金额格式化、缩略图、胶囊按钮 */

export const C = {
  fg: 'rgba(26, 26, 29, 0.92)',
  fg2: 'rgba(26, 26, 29, 0.65)',
  fg3: 'rgba(26, 26, 29, 0.45)',
  line: 'rgba(26, 26, 29, 0.08)',
  price: '#ff5000',
  blue: '#1677ff',
  blueBg: 'rgba(22, 119, 255, 0.08)',
  green: '#00a870',
  greenBg: 'rgba(0, 168, 112, 0.1)',
  chipBg: 'rgba(26, 26, 29, 0.05)',
};

/** props 里的对象数组（信封来自 JSON，非对象项直接丢弃） */
export const objs = (v: unknown): Record<string, unknown>[] =>
  Array.isArray(v)
    ? v.filter((x): x is Record<string, unknown> => !!x && typeof x === 'object' && !Array.isArray(x))
    : [];

/** 数字或字符串金额 → `¥29.8` / `¥41.80`；无法解析时原样返回字符串 */
export function money(v: unknown): string {
  const n = typeof v === 'number' ? v : Number(str(v)?.trim());
  if (Number.isFinite(n)) return `¥${Number.isInteger(n) ? n : n.toFixed(2)}`;
  return str(v) ?? '';
}

/** 信封里既可能是 JSON number 也可能是 string 的展示值（评分、时长、数量） */
export const numOrStr = (v: unknown): string | undefined =>
  typeof v === 'number' && Number.isFinite(v) ? String(v) : str(v);

/** 商品/店铺图：URL 走 <img>，否则把值当 emoji 展示；加载失败回落占位 */
export function Thumb({ src, size }: { src?: string; size: number }) {
  const [failed, setFailed] = useState(false);
  const isUrl = !!src && /^(https?:)?\/\/|^data:|^\//.test(src);
  const box: React.CSSProperties = {
    width: size,
    height: size,
    flex: '0 0 auto',
    borderRadius: Math.min(8, size / 6),
    background: C.chipBg,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  };
  if (!isUrl || failed) {
    return (
      <div style={{ ...box, fontSize: size * 0.45 }}>
        {src && !isUrl ? src : <span style={{ fontSize: size * 0.35, opacity: 0.35 }}>🍽</span>}
      </div>
    );
  }
  return <img src={src} alt="" onError={() => setFailed(true)} style={{ ...box, objectFit: 'cover' }} />;
}

export function PillButton({
  text,
  kind = 'ghost',
  icon,
  disabled,
  onClick,
  block,
}: {
  text: string;
  kind?: 'primary' | 'ghost' | 'soft';
  icon?: React.ReactNode;
  disabled?: boolean;
  onClick?: () => void;
  block?: boolean;
}) {
  const tone =
    kind === 'primary'
      ? { background: C.blueBg, color: C.blue }
      : kind === 'soft'
        ? { background: C.blueBg, color: C.blue }
        : { background: C.chipBg, color: C.fg };
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 5,
        width: block ? '100%' : undefined,
        padding: '8px 14px',
        borderRadius: 8,
        border: 'none',
        fontSize: 13.5,
        fontWeight: 500,
        cursor: disabled ? 'default' : 'pointer',
        opacity: disabled ? 0.5 : 1,
        ...tone,
      }}
    >
      {icon}
      {text}
    </button>
  );
}
