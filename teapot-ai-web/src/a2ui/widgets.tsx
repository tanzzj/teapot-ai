import { useState } from 'react';
import { Markdown } from '@agentscope-ai/chat';
import type { A2uiComponent } from './envelope';
import { bool, num, str, strList } from './envelope';

/**
 * basic catalog（agentscope.io:a2ui/basic，22 组件）前端渲染注册表。
 * 表单类组件通过 FormCtx 双向绑定到 surface 级 values（键 = props.name），
 * Button(action=submit) 触发 onSubmit 由外层收集/校验/派发。
 */

export interface FormCtx {
  values: Record<string, unknown>;
  setValue: (name: string, v: unknown) => void;
  disabled: boolean;
}

const labelStyle: React.CSSProperties = {
  display: 'block',
  fontSize: 12,
  color: 'rgba(26, 26, 29, 0.65)',
  margin: '2px 0 4px',
};
const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '6px 10px',
  borderRadius: 8,
  border: '1px solid rgba(26, 26, 29, 0.15)',
  fontSize: 13,
  background: '#fff',
  color: 'rgba(26, 26, 29, 0.9)',
  boxSizing: 'border-box',
};

const ALERT_TONE: Record<string, { bg: string; border: string }> = {
  info: { bg: 'rgba(22, 119, 255, 0.06)', border: '#1677ff' },
  success: { bg: 'rgba(82, 196, 26, 0.08)', border: '#52c41a' },
  warning: { bg: 'rgba(250, 173, 20, 0.08)', border: '#faad14' },
  error: { bg: 'rgba(255, 77, 79, 0.06)', border: '#ff4d4f' },
};

const BADGE_TONE: Record<string, { fg: string; bg: string }> = {
  neutral: { fg: 'rgba(26, 26, 29, 0.65)', bg: 'rgba(26, 26, 29, 0.06)' },
  info: { fg: '#1677ff', bg: 'rgba(22, 119, 255, 0.1)' },
  success: { fg: '#389e0d', bg: 'rgba(82, 196, 26, 0.12)' },
  warning: { fg: '#d48806', bg: 'rgba(250, 173, 20, 0.14)' },
  error: { fg: '#cf1322', bg: 'rgba(255, 77, 79, 0.1)' },
};

/** Tabs 需要局部页签状态，独立成组件 */
function TabsWidget({ comp }: { comp: A2uiComponent }) {
  const tabs = (Array.isArray(comp.props?.tabs) ? comp.props.tabs : []) as {
    label?: string;
    content?: string;
  }[];
  const [active, setActive] = useState(0);
  if (!tabs.length) return null;
  const cur = tabs[Math.min(active, tabs.length - 1)];
  return (
    <div>
      <div style={{ display: 'flex', gap: 2, borderBottom: '1px solid rgba(26,26,29,0.1)' }}>
        {tabs.map((t, i) => (
          <span
            key={i}
            onClick={() => setActive(i)}
            style={{
              padding: '5px 12px',
              fontSize: 13,
              cursor: 'pointer',
              borderBottom: i === active ? '2px solid #1a1a1d' : '2px solid transparent',
              fontWeight: i === active ? 600 : 400,
              color: i === active ? 'rgba(26,26,29,0.9)' : 'rgba(26,26,29,0.5)',
            }}
          >
            {t.label || `Tab ${i + 1}`}
          </span>
        ))}
      </div>
      <div style={{ paddingTop: 8 }}>
        <Markdown content={cur.content || ''} />
      </div>
    </div>
  );
}

/** FileDrop：MVP 只登记文件名作为答案值（真实上传走聊天附件链路） */
function FileDropWidget({ comp, ctx }: { comp: A2uiComponent; ctx: FormCtx }) {
  const name = str(comp.props?.name);
  if (!name) return null;
  const files = str(ctx.values[name]) ?? '';
  return (
    <label style={{ display: 'block' }}>
      <span style={labelStyle}>
        {(str(comp.props?.label) ?? '上传文件')}：当前 {files || '未选择'}
      </span>
      <input
        type="file"
        accept={str(comp.props?.accept)}
        disabled={ctx.disabled}
        onChange={(e) => ctx.setValue(name, e.target.files?.[0]?.name ?? '')}
        style={{ fontSize: 12 }}
      />
    </label>
  );
}

/** Form：显式字段列表（平铺输入组件的替代形态），字段与 values 同名绑定 */
function FormWidget({ comp, ctx, onSubmit }: { comp: A2uiComponent; ctx: FormCtx; onSubmit: () => void }) {
  const fields = (Array.isArray(comp.props?.fields) ? comp.props.fields : []) as {
    name?: string;
    label?: string;
    type?: string;
    options?: string[];
    required?: boolean;
  }[];
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        padding: '10px 12px',
        borderRadius: 10,
        border: '1px solid rgba(26,26,29,0.1)',
        background: 'rgba(26,26,29,0.02)',
      }}
    >
      {fields.map((f, i) => {
        if (!f?.name) return null;
        const pseudo: A2uiComponent = {
          id: `f_${f.name}_${i}`,
          component:
            f.type === 'textarea' ? 'TextArea'
            : f.type === 'select' ? 'Select'
            : f.type === 'checkbox' ? 'Checkbox'
            : f.type === 'radio' ? 'RadioGroup'
            : 'TextInput',
          props: {
            name: f.name,
            label: f.label,
            options: f.options,
            required: f.required,
          },
        };
        return <A2uiWidget key={i} comp={pseudo} ctx={ctx} onSubmit={onSubmit} />;
      })}
      <div>
        <button type="button" disabled={ctx.disabled} onClick={onSubmit} style={buttonStyle}>
          {str(comp.props?.submitText) || 'Submit'}
        </button>
      </div>
    </div>
  );
}

const buttonStyle: React.CSSProperties = {
  padding: '6px 16px',
  borderRadius: 8,
  border: '1px solid #1a1a1d',
  background: '#1a1a1d',
  color: '#fff',
  fontSize: 13,
  cursor: 'pointer',
};

export function A2uiWidget({
  comp,
  ctx,
  onSubmit,
}: {
  comp: A2uiComponent;
  ctx: FormCtx;
  onSubmit: () => void;
}) {
  const p = comp.props ?? {};
  switch (comp.component) {
    case 'Heading': {
      const size = [18, 16, 14][Math.min(Math.max(num(p.level, 2), 1), 3) - 1];
      return (
        <div style={{ fontSize: size, fontWeight: 600, color: 'rgba(26,26,29,0.92)' }}>
          {str(p.text) ?? ''}
        </div>
      );
    }
    case 'Text':
      return (
        <div style={{ fontSize: 13, color: bool(p.muted) ? 'rgba(26,26,29,0.45)' : 'rgba(26,26,29,0.85)', wordBreak: 'break-word' }}>
          {str(p.text) ?? ''}
        </div>
      );
    case 'Markdown':
      return <Markdown content={str(p.content) ?? ''} />;
    case 'Table': {
      const cols = strList(p.columns);
      const rows = (Array.isArray(p.rows) ? p.rows : []) as unknown[][];
      return (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ borderCollapse: 'collapse', fontSize: 12.5, width: '100%' }}>
            <thead>
              <tr>
                {cols.map((c, i) => (
                  <th key={i} style={{ textAlign: 'left', padding: '5px 10px', borderBottom: '1.5px solid rgba(26,26,29,0.2)', whiteSpace: 'nowrap' }}>
                    {c}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((r, ri) => (
                <tr key={ri}>
                  {cols.map((_, ci) => (
                    <td key={ci} style={{ padding: '4px 10px', borderBottom: '1px solid rgba(26,26,29,0.08)', wordBreak: 'break-word' }}>
                      {r?.[ci] == null ? '' : String(r[ci])}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          {str(p.caption) ? (
            <div style={{ fontSize: 11.5, color: 'rgba(26,26,29,0.45)', marginTop: 4 }}>{str(p.caption)}</div>
          ) : null}
        </div>
      );
    }
    case 'List': {
      const items = strList(p.items);
      const Tag = bool(p.ordered) ? 'ol' : 'ul';
      return (
        <Tag style={{ margin: 0, paddingLeft: 20, fontSize: 13, color: 'rgba(26,26,29,0.85)' }}>
          {items.map((t, i) => (
            <li key={i} style={{ wordBreak: 'break-word' }}>{t}</li>
          ))}
        </Tag>
      );
    }
    case 'Image':
      return (
        <img
          src={str(p.src)}
          alt={str(p.alt) ?? ''}
          style={{ maxWidth: '100%', maxHeight: 320, borderRadius: 8 }}
        />
      );
    case 'Alert': {
      const tone = ALERT_TONE[str(p.kind) ?? 'info'] ?? ALERT_TONE.info;
      return (
        <div style={{ padding: '8px 12px', borderRadius: 8, borderLeft: `3px solid ${tone.border}`, background: tone.bg, fontSize: 13, wordBreak: 'break-word' }}>
          {str(p.text) ?? ''}
        </div>
      );
    }
    case 'Badge': {
      const tone = BADGE_TONE[str(p.tone) ?? 'neutral'] ?? BADGE_TONE.neutral;
      return (
        <span style={{ display: 'inline-block', padding: '1px 8px', borderRadius: 10, fontSize: 12, fontWeight: 500, color: tone.fg, background: tone.bg }}>
          {str(p.text) ?? ''}
        </span>
      );
    }
    case 'Card':
      return (
        <div style={{ padding: '10px 12px', borderRadius: 10, border: '1px solid rgba(26,26,29,0.1)', background: 'rgba(255,255,255,0.5)' }}>
          {str(p.title) ? (
            <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 4 }}>{str(p.title)}</div>
          ) : null}
        </div>
      );
    case 'Divider':
      return <hr style={{ border: 'none', borderTop: '1px solid rgba(26,26,29,0.1)', margin: '4px 0' }} />;
    case 'Tabs':
      return <TabsWidget comp={comp} />;
    case 'ProgressBar': {
      const max = num(p.max, 100) || 100;
      const pct = Math.max(0, Math.min(100, (num(p.value, 0) / max) * 100));
      return (
        <div>
          {str(p.label) ? <span style={labelStyle}>{str(p.label)}</span> : null}
          <div style={{ height: 6, borderRadius: 3, background: 'rgba(26,26,29,0.08)', overflow: 'hidden' }}>
            <div style={{ height: '100%', width: `${pct}%`, background: '#1a1a1d', borderRadius: 3, transition: 'width .3s' }} />
          </div>
        </div>
      );
    }
    case 'TextInput': {
      const name = str(p.name);
      if (!name) return null;
      return (
        <label style={{ display: 'block' }}>
          <span style={labelStyle}>
            {str(p.label) ?? name}
            {bool(p.required) ? <span style={{ color: '#ff4d4f' }}> *</span> : null}
          </span>
          <input
            style={inputStyle}
            value={str(ctx.values[name]) ?? ''}
            placeholder={str(p.placeholder)}
            disabled={ctx.disabled}
            onChange={(e) => ctx.setValue(name, e.target.value)}
          />
        </label>
      );
    }
    case 'TextArea': {
      const name = str(p.name);
      if (!name) return null;
      return (
        <label style={{ display: 'block' }}>
          <span style={labelStyle}>{str(p.label) ?? name}</span>
          <textarea
            style={{ ...inputStyle, resize: 'vertical' }}
            rows={num(p.rows, 3)}
            value={str(ctx.values[name]) ?? ''}
            disabled={ctx.disabled}
            onChange={(e) => ctx.setValue(name, e.target.value)}
          />
        </label>
      );
    }
    case 'Select': {
      const name = str(p.name);
      if (!name) return null;
      const options = strList(p.options);
      const multiple = bool(p.multiple);
      const val = ctx.values[name];
      return (
        <label style={{ display: 'block' }}>
          <span style={labelStyle}>{str(p.label) ?? name}</span>
          <select
            style={inputStyle}
            multiple={multiple}
            disabled={ctx.disabled}
            value={multiple ? (Array.isArray(val) ? val as string[] : []) : (str(val) ?? '')}
            onChange={(e) => {
              if (multiple) {
                ctx.setValue(name, Array.from(e.target.selectedOptions).map((o) => o.value));
              } else {
                ctx.setValue(name, e.target.value);
              }
            }}
          >
            {!multiple && <option value="">（请选择）</option>}
            {options.map((o) => (
              <option key={o} value={o}>{o}</option>
            ))}
          </select>
        </label>
      );
    }
    case 'Checkbox': {
      const name = str(p.name);
      if (!name) return null;
      return (
        <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, cursor: ctx.disabled ? 'default' : 'pointer' }}>
          <input
            type="checkbox"
            checked={bool(ctx.values[name]) || bool(p.checked) && ctx.values[name] === undefined}
            disabled={ctx.disabled}
            onChange={(e) => ctx.setValue(name, e.target.checked)}
          />
          {str(p.label) ?? name}
        </label>
      );
    }
    case 'RadioGroup': {
      const name = str(p.name);
      if (!name) return null;
      const options = strList(p.options);
      return (
        <div>
          <span style={labelStyle}>{str(p.label) ?? name}</span>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 14px' }}>
            {options.map((o) => (
              <label key={o} style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 13, cursor: ctx.disabled ? 'default' : 'pointer' }}>
                <input
                  type="radio"
                  name={`${comp.id ?? 'radio'}_${name}`}
                  checked={ctx.values[name] === o}
                  disabled={ctx.disabled}
                  onChange={() => ctx.setValue(name, o)}
                />
                {o}
              </label>
            ))}
          </div>
        </div>
      );
    }
    case 'Button': {
      if (str(p.action) === 'link') {
        return (
          <a href={str(p.href) ?? '#'} target="_blank" rel="noreferrer" style={{ fontSize: 13, color: '#1677ff' }}>
            {str(p.text) ?? '链接'}
          </a>
        );
      }
      return (
        <button type="button" disabled={ctx.disabled} onClick={onSubmit} style={{ ...buttonStyle, cursor: ctx.disabled ? 'default' : 'pointer', opacity: ctx.disabled ? 0.5 : 1 }}>
          {str(p.text) ?? 'Submit'}
        </button>
      );
    }
    case 'Form':
      return <FormWidget comp={comp} ctx={ctx} onSubmit={onSubmit} />;
    case 'FileDrop':
      return <FileDropWidget comp={comp} ctx={ctx} />;
    case 'Row':
    case 'Column':
      // 流式排版容器由 layoutFlow 消费；单独出现（如未闭合流）按透传渲染
      return null;
    default:
      return (
        <div style={{ fontSize: 12, color: 'rgba(26,26,29,0.45)', padding: '4px 8px', border: '1px dashed rgba(26,26,29,0.15)', borderRadius: 6 }}>
          不支持的组件：{comp.component}
        </div>
      );
  }
}
