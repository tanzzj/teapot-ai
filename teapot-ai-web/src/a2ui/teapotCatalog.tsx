import { createContext, useContext, type CSSProperties } from 'react';
import { z } from 'zod';
import {
  BASIC_FUNCTIONS,
  Catalog,
  CheckRuleSchema,
  DynamicNumberSchema,
  DynamicStringSchema,
} from '@a2ui/web_core/v1_0';
import { createComponentImplementation } from '@a2ui/react';
import { num, str } from './values';
import { TakeoutStoreList, takeoutStoreListFromProps } from './takeout/TakeoutStoreList';
import { TakeoutOrderCard, takeoutOrderCardFromProps } from './takeout/TakeoutOrderCard';

/**
 * teapot 自定义 catalog 的前端注册，与后端 teapot-catalog.json 的 7 个组件一一对应；
 * basic 18 个组件走官方 basicCatalog，信封按组件盖的 catalogId 分流到两个 catalog。
 * 不带 catalogId 的函数调用按 surface 默认 catalog 解析，所以这里也注册 BASIC_FUNCTIONS。
 * 属性 schema 只描述绑定行为（动态值 / 校验规则），字面量形状由后端按官方 JSON Schema 把关。
 */

export interface A2uiHost {
  disabled: boolean;
  /** 展示型卡片的按钮派发（进店逛逛 / 去支付），由外层接到消息发送 */
  onAction?: (text: string) => void;
}

/** 官方渲染器没有全局 disabled/onAction，宿主用 context 下发给自定义组件 */
export const A2uiHostContext = createContext<A2uiHost>({ disabled: false });

const weightSchema = z.number().optional();

/** React 宿主元素是 display:contents，组件根元素才是 flex item，weight 要自己落上去 */
const flexOf = (weight?: number): CSSProperties =>
  weight === undefined ? {} : { flex: String(weight) };

const labelStyle: CSSProperties = {
  display: 'block',
  fontSize: 12,
  color: 'rgba(26, 26, 29, 0.65)',
  margin: '2px 0 4px',
};

const errorStyle: CSSProperties = { display: 'block', fontSize: 12, color: '#ff4d4f', marginTop: 4 };

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

const Table = createComponentImplementation(
  {
    name: 'Table',
    schema: z.object({
      columns: z.array(z.string()),
      rows: z.array(z.array(z.union([z.string(), z.number()]))),
      caption: DynamicStringSchema.optional(),
      weight: weightSchema,
    }),
  },
  ({ props }) => {
    const columns = props.columns ?? [];
    const rows = props.rows ?? [];
    return (
      <div style={{ overflowX: 'auto', ...flexOf(props.weight) }}>
        <table style={{ borderCollapse: 'collapse', fontSize: 12.5, width: '100%' }}>
          <thead>
            <tr>
              {columns.map((c, i) => (
                <th
                  key={i}
                  style={{
                    textAlign: 'left',
                    padding: '5px 10px',
                    borderBottom: '1.5px solid rgba(26,26,29,0.2)',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {c}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r, ri) => (
              <tr key={ri}>
                {columns.map((_, ci) => (
                  <td
                    key={ci}
                    style={{
                      padding: '4px 10px',
                      borderBottom: '1px solid rgba(26,26,29,0.08)',
                      wordBreak: 'break-word',
                    }}
                  >
                    {r?.[ci] == null ? '' : String(r[ci])}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        {props.caption ? (
          <div style={{ fontSize: 11.5, color: 'rgba(26,26,29,0.45)', marginTop: 4 }}>
            {props.caption}
          </div>
        ) : null}
      </div>
    );
  },
);

const Alert = createComponentImplementation(
  {
    name: 'Alert',
    schema: z.object({
      text: DynamicStringSchema,
      kind: z.enum(['info', 'success', 'warning', 'error']).optional(),
      weight: weightSchema,
    }),
  },
  ({ props }) => {
    const tone = ALERT_TONE[props.kind ?? 'info'] ?? ALERT_TONE.info;
    return (
      <div
        style={{
          padding: '8px 12px',
          borderRadius: 8,
          borderLeft: `3px solid ${tone.border}`,
          background: tone.bg,
          fontSize: 13,
          wordBreak: 'break-word',
          ...flexOf(props.weight),
        }}
      >
        {props.text ?? ''}
      </div>
    );
  },
);

const Badge = createComponentImplementation(
  {
    name: 'Badge',
    schema: z.object({
      text: DynamicStringSchema,
      tone: z.enum(['neutral', 'info', 'success', 'warning', 'error']).optional(),
      weight: weightSchema,
    }),
  },
  ({ props }) => {
    const tone = BADGE_TONE[props.tone ?? 'neutral'] ?? BADGE_TONE.neutral;
    return (
      <span
        style={{
          display: 'inline-block',
          padding: '1px 8px',
          borderRadius: 10,
          fontSize: 12,
          fontWeight: 500,
          color: tone.fg,
          background: tone.bg,
          ...flexOf(props.weight),
        }}
      >
        {props.text ?? ''}
      </span>
    );
  },
);

const ProgressBar = createComponentImplementation(
  {
    name: 'ProgressBar',
    schema: z.object({
      value: DynamicNumberSchema,
      max: z.number().optional(),
      label: DynamicStringSchema.optional(),
      weight: weightSchema,
    }),
  },
  ({ props }) => {
    const max = num(props.max, 100) || 100;
    const pct = Math.max(0, Math.min(100, (num(props.value, 0) / max) * 100));
    return (
      <div style={flexOf(props.weight)}>
        {props.label ? <span style={labelStyle}>{props.label}</span> : null}
        <div
          style={{
            height: 6,
            borderRadius: 3,
            background: 'rgba(26,26,29,0.08)',
            overflow: 'hidden',
          }}
        >
          <div
            style={{
              height: '100%',
              width: `${pct}%`,
              background: '#1a1a1d',
              borderRadius: 3,
              transition: 'width .3s',
            }}
          />
        </div>
      </div>
    );
  },
);

/** 只登记文件名作为答案值（真实上传走聊天附件链路），写回绑定路径由渲染器的 setValue 完成 */
const FileDrop = createComponentImplementation(
  {
    name: 'FileDrop',
    schema: z.object({
      value: DynamicStringSchema.optional(),
      label: DynamicStringSchema.optional(),
      accept: z.string().optional(),
      checks: z.array(CheckRuleSchema).optional(),
      weight: weightSchema,
    }),
  },
  ({ props }) => {
    const { disabled } = useContext(A2uiHostContext);
    const files = str(props.value) ?? '';
    return (
      <label style={{ display: 'block', ...flexOf(props.weight) }}>
        <span style={labelStyle}>
          {props.label ?? '上传文件'}：当前 {files || '未选择'}
        </span>
        <input
          type="file"
          accept={props.accept}
          disabled={disabled}
          onChange={(e) => props.setValue(e.target.files?.[0]?.name ?? '')}
          style={{ fontSize: 12 }}
        />
        {props.validationErrors?.length ? (
          <span style={errorStyle}>{props.validationErrors.join('；')}</span>
        ) : null}
      </label>
    );
  },
);

const StoreList = createComponentImplementation(
  {
    name: 'TakeoutStoreList',
    schema: z.object({
      title: DynamicStringSchema.optional(),
      stores: z.array(z.record(z.unknown())).optional(),
      weight: weightSchema,
    }),
  },
  ({ props }) => {
    const host = useContext(A2uiHostContext);
    return (
      <div style={flexOf(props.weight)}>
        <TakeoutStoreList
          {...takeoutStoreListFromProps(props)}
          disabled={host.disabled}
          onAction={host.onAction}
        />
      </div>
    );
  },
);

const OrderCard = createComponentImplementation(
  {
    name: 'TakeoutOrderCard',
    schema: z.object({
      shop: z.record(z.unknown()).optional(),
      items: z.array(z.record(z.unknown())).optional(),
      rows: z.array(z.record(z.unknown())).optional(),
      actions: z.array(z.record(z.unknown())).optional(),
      weight: weightSchema,
    }),
  },
  ({ props }) => {
    const host = useContext(A2uiHostContext);
    return (
      <div style={flexOf(props.weight)}>
        <TakeoutOrderCard
          {...takeoutOrderCardFromProps(props)}
          disabled={host.disabled}
          onAction={host.onAction}
        />
      </div>
    );
  },
);

export const teapotCatalog = new Catalog(
  'teamer:a2ui/teapot',
  '1.0',
  [Table, Alert, Badge, ProgressBar, FileDrop, StoreList, OrderCard],
  BASIC_FUNCTIONS,
);