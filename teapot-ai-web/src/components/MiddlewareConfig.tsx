import { Alert, Empty } from 'antd';
import { Form, Input, InputNumber, Select, Switch } from '@agentscope-ai/design';
import type { FormInstance } from 'antd';
import { linesToMap, mapToLines } from './MCPConfigForm';
import type { MiddlewareFieldSchema, MiddlewareSchemaView } from '../types';

/**
 * feature 驱动中间件分区（SPEC §29.23）：配置项由后端内省中间件配置类产出，界面按 schema 渲染，
 * 平台新增一个可配中间件不必改本页。
 *
 * <p>开关位不进表单值 —— 它表达的是「要不要在 feature 里写这个命名空间」，不是某个配置字段。
 */

/**
 * 回显种子：已存值优先，缺字段回落 schema 的 defaultValue，
 * 保证界面摆出来的值与后端实际解析到的值是同一份（后端也按同一规则回填）。
 */
export function seedMiddleware(views: MiddlewareSchemaView[]): {
  values: Record<string, unknown>;
  on: Record<string, boolean>;
} {
  const values: Record<string, Record<string, unknown>> = {};
  const on: Record<string, boolean> = {};
  for (const view of views) {
    const stored = view.config && !Array.isArray(view.config)
      ? (view.config as Record<string, unknown>)
      : undefined;
    on[view.key] = view.config != null;
    const row: Record<string, unknown> = {};
    for (const field of view.fields) {
      const raw = stored?.[field.name];
      row[field.name] = field.type === 'STRING_MAP'
        ? mapToLines(raw as Record<string, string> | undefined)
        : raw ?? field.defaultValue ?? undefined;
    }
    values[view.key] = row;
  }
  return { values, on };
}

/**
 * 表单值 → feature 命名空间（原地改 feature）：
 * 关 = 删掉整个命名空间（后端据此判「未配置」），开 = 只写非空字段；
 * 数组形态不在界面编辑，靠已加载的原始命名空间原样带走。
 */
export function applyMiddlewareFeature(
  feature: Record<string, unknown>,
  views: MiddlewareSchemaView[],
  values: unknown,
  on: Record<string, boolean>,
): void {
  if (values === undefined) {
    return;
  }
  const rows = values as Record<string, Record<string, unknown>>;
  for (const view of views) {
    if (view.array) {
      continue;
    }
    if (on[view.key] !== true) {
      delete feature[view.featureKey];
      continue;
    }
    const row = rows[view.key] || {};
    const out: Record<string, unknown> = {};
    for (const field of view.fields) {
      const raw = row[field.name];
      if (field.type === 'STRING_MAP') {
        const map = linesToMap(String(raw ?? ''));
        if (Object.keys(map).length > 0) {
          out[field.name] = map;
        }
        continue;
      }
      if (raw === undefined || raw === null || raw === '') {
        continue;
      }
      out[field.name] = raw;
    }
    feature[view.featureKey] = out;
  }
}

/** 配置字段的控件：type → 控件的唯一映射，标签/说明/必填/默认值/可选值全部来自后端 schema */
function MiddlewareField({ viewKey, field }: { viewKey: string; field: MiddlewareFieldSchema }) {
  const placeholder = field.defaultValue == null ? undefined : `默认 ${field.defaultValue}`;
  const common = {
    name: ['mw', viewKey, field.name],
    label: field.label,
    tooltip: field.description || undefined,
    rules: field.required ? [{ required: true, message: `${field.label} 必填` }] : undefined,
  };
  switch (field.type) {
    case 'BOOLEAN':
      return (
        <Form.Item {...common} valuePropName="checked">
          <Switch />
        </Form.Item>
      );
    case 'INTEGER':
    case 'NUMBER':
      return (
        <Form.Item {...common}>
          <InputNumber
            style={{ width: '100%' }}
            step={field.type === 'INTEGER' ? 1 : 0.1}
            placeholder={placeholder}
          />
        </Form.Item>
      );
    case 'ENUM':
      return (
        <Form.Item {...common}>
          <Select
            options={(field.options || []).map((option) => ({ value: option, label: option }))}
            placeholder={placeholder}
          />
        </Form.Item>
      );
    case 'STRING_MAP':
      return (
        <Form.Item {...common}>
          <Input.TextArea rows={3} placeholder="每行一条 key=value" />
        </Form.Item>
      );
    default:
      return (
        <Form.Item {...common}>
          <Input placeholder={placeholder} />
        </Form.Item>
      );
  }
}

export function MiddlewareConfigSection({ form, views, on, onToggle }: {
  form: FormInstance;
  views: MiddlewareSchemaView[];
  on: Record<string, boolean>;
  onToggle: (key: string, checked: boolean) => void;
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {views.length === 0 ? (
        <div className="glass-card" style={{ padding: 24 }}>
          <Empty description="平台暂未提供可配置的中间件" />
        </div>
      ) : views.map((view) => (
        <div className="glass-card" key={view.key} style={{ padding: 24 }}>
          <Form form={form} layout="vertical">
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <div style={{ fontSize: 15, fontWeight: 600 }}>{view.key}</div>
              <div style={{ flex: 1 }} />
              {!view.array && (
                <Switch checked={on[view.key] === true} onChange={(checked) => onToggle(view.key, checked)} />
              )}
            </div>
            <div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.55)', marginBottom: 12 }}>
              {view.description}
            </div>
            {view.configError && (
              <Alert
                type="error"
                showIcon
                style={{ marginBottom: 12 }}
                message="已存配置不会生效"
                description={view.configError}
              />
            )}
            {view.array ? (
              <div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.55)' }}>
                该中间件的配置形态是数组，本界面只支持对象配置；已存的数组配置在本页保存时原样保留。
              </div>
            ) : on[view.key] ? (
              view.fields.map((field) => (
                <MiddlewareField key={field.name} viewKey={view.key} field={field} />
              ))
            ) : (
              <div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.55)' }}>
                未启用。开启后至少填一项 —— 全部留空等于没写这个配置，保存后会回到关闭。
              </div>
            )}
          </Form>
        </div>
      ))}
    </div>
  );
}
