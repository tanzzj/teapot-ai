import { Alert, Dropdown, Empty } from 'antd';
import { Button, Form, Input, InputNumber, Popconfirm, Select, Switch } from '@agentscope-ai/design';
import type { FormInstance } from 'antd';
import { SparkDeleteLine, SparkDownArrowLine, SparkPlusLine } from '@agentscope-ai/icons';
import { linesToMap, mapToLines } from './MCPConfigForm';
import type { MiddlewareFieldSchema, MiddlewareSchemaView } from '../types';

/**
 * feature 驱动中间件分区（SPEC §29.23/§29.25）：一开始是空的，点「装配中间件」从平台可选项里挑，
 * 挑中的才出现配置卡，卡片右上角可移除 —— 平台装的中间件再多，界面也只摆这个 Agent 真正用到的那几个。
 *
 * <p>「装配位」是 React state 而不是表单值：它表达的是「要不要在 feature 里写这个命名空间」，
 * 不是某个配置字段。配置项本身由后端内省中间件配置类产出（schema），界面只按 type 渲染控件。
 */

/** 该中间件当前值保存后会落进 feature 的配置对象；空对象 = 后端判「未配置」= 不装配 */
function namespaceOf(view: MiddlewareSchemaView, row: Record<string, unknown> | undefined): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const field of view.fields) {
    const raw = row?.[field.name];
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
  return out;
}

/**
 * 回显种子：已存值优先，缺字段回落 schema 的 defaultValue，
 * 保证界面摆出来的值与后端实际解析到的值是同一份（后端也按同一规则回填）。
 */
export function seedMiddleware(views: MiddlewareSchemaView[]): {
  values: Record<string, unknown>;
  attached: Record<string, boolean>;
} {
  const values: Record<string, Record<string, unknown>> = {};
  const attached: Record<string, boolean> = {};
  for (const view of views) {
    const stored = view.config && !Array.isArray(view.config)
      ? (view.config as Record<string, unknown>)
      : undefined;
    attached[view.key] = view.config != null;
    const row: Record<string, unknown> = {};
    for (const field of view.fields) {
      const raw = stored?.[field.name];
      row[field.name] = field.type === 'STRING_MAP'
        ? mapToLines(raw as Record<string, string> | undefined)
        : raw ?? field.defaultValue ?? undefined;
    }
    values[view.key] = row;
  }
  return { values, attached };
}

/** 表单值 → feature 命名空间（原地改 feature）：没装配的删掉，装配的只写非空字段 */
export function applyMiddlewareFeature(
  feature: Record<string, unknown>,
  views: MiddlewareSchemaView[],
  values: unknown,
  attached: Record<string, boolean>,
): void {
  if (values === undefined) {
    return;
  }
  const rows = values as Record<string, Record<string, unknown>>;
  for (const view of views) {
    if (view.array) {
      // 数组形态不在界面编辑，靠已加载的原始命名空间原样带走
      continue;
    }
    if (attached[view.key] !== true) {
      delete feature[view.featureKey];
      continue;
    }
    feature[view.featureKey] = namespaceOf(view, rows[view.key]);
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

export function MiddlewareConfigSection({ form, views, attached, onAdd, onRemove }: {
  form: FormInstance;
  views: MiddlewareSchemaView[];
  attached: Record<string, boolean>;
  onAdd: (key: string) => void;
  onRemove: (key: string) => void;
}) {
  const rows = Form.useWatch('mw', form) as Record<string, Record<string, unknown>> | undefined;
  // 数组形态配置界面填不了，不进「可装配」清单（存量数组配置仍照常显示，只是不能新增）
  const available = views.filter((view) => !view.array && attached[view.key] !== true);
  const mounted = views.filter((view) => attached[view.key] === true);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Dropdown
          trigger={['click']}
          disabled={available.length === 0}
          menu={{
            items: available.map((view) => ({ key: view.key, label: view.key })),
            onClick: ({ key }) => onAdd(String(key)),
          }}
        >
          <Button type="primary" icon={<SparkPlusLine />}>
            装配中间件 <SparkDownArrowLine size={12} />
          </Button>
        </Dropdown>
      </div>

      {mounted.length === 0 ? (
        <div className="glass-card" style={{ padding: 24 }}>
          <Empty description="尚未装配中间件" />
        </div>
      ) : mounted.map((view) => {
        const emptyNamespace = !view.array
          && Object.keys(namespaceOf(view, rows?.[view.key])).length === 0;
        return (
          <div className="glass-card" key={view.key} style={{ padding: 24 }}>
            <Form form={form} layout="vertical">
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <div style={{ fontSize: 15, fontWeight: 600 }}>{view.key}</div>
                <div style={{ flex: 1 }} />
                <Popconfirm
                  title="移除该中间件？"
                  description="保存后这个 Agent 不再装配它"
                  okText="移除"
                  cancelText="取消"
                  onConfirm={() => onRemove(view.key)}
                >
                  <Button type="text" size="small" icon={<SparkDeleteLine />} />
                </Popconfirm>
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
              ) : (
                <>
                  {view.fields.map((field) => (
                    <MiddlewareField key={field.name} viewKey={view.key} field={field} />
                  ))}
                  {emptyNamespace && (
                    <div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.55)' }}>
                      一项没填且没有默认值 —— 保存后这张卡不会留下。
                    </div>
                  )}
                </>
              )}
            </Form>
          </div>
        );
      })}
    </div>
  );
}
