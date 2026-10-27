import { useEffect, useState } from 'react';
import { Alert, Empty } from 'antd';
import { Button, Form, Input, InputNumber, Popconfirm, Select, Switch } from '@agentscope-ai/design';
import { SparkDeleteLine, SparkEditLine, SparkPlusLine } from '@agentscope-ai/icons';
import { ResponsiveModal } from './ResponsiveModal';
import { linesToMap, mapToLines } from './MCPConfigForm';
import type { MiddlewareFieldSchema, MiddlewareSchemaView } from '../types';

/**
 * feature 驱动中间件分区（SPEC §29.23/§29.26/§29.27）：一开始是空的，点「装配中间件」开弹窗，
 * 弹窗里选一个中间件 → 按它的 schema 渲染配置项 → 填好保存才进列表；列表里的卡片只展示与编辑，
 * 编辑也走同一个弹窗。平台装的中间件再多，界面也只摆这个 Agent 真正用到的那几个。
 *
 * <p>配置值与装配位都由调用方（页面）持有，本组件不碰页面那份表单：弹窗用**自己**的 Form 实例。
 * 之前把字段挂进页面表单，卡片与弹窗会同时挂载同名 `mw.<key>.<field>`，弹窗关闭时那份值被注销掉
 * ——填好的值直接丢，所以改成「弹窗是唯一编辑入口，值存在页面 state 里」。
 */

/** 一个中间件的配置值（键 = schema 里的字段名；STRING_MAP 存的是多行文本形态） */
export type MiddlewareValues = Record<string, Record<string, unknown>>;

/** 该中间件保存后会落进 feature 的配置对象；空对象 = 后端判「未配置」= 不装配 */
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
  values: MiddlewareValues;
  attached: Record<string, boolean>;
} {
  const values: MiddlewareValues = {};
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
  values: MiddlewareValues | undefined,
  attached: Record<string, boolean>,
): void {
  if (values === undefined) {
    return;
  }
  for (const view of views) {
    if (view.array) {
      // 数组形态不在界面编辑，靠已加载的原始命名空间原样带走
      continue;
    }
    if (attached[view.key] !== true) {
      delete feature[view.featureKey];
      continue;
    }
    feature[view.featureKey] = namespaceOf(view, values[view.key]);
  }
}

/** 卡片上展示一行配置：值来自弹窗里填的那份，没填则说明会用后端默认值 */
function displayValue(field: MiddlewareFieldSchema, raw: unknown): string {
  if (field.type === 'STRING_MAP') {
    const keys = Object.keys(linesToMap(String(raw ?? '')));
    return keys.length > 0 ? keys.map((k) => `${k}=${linesToMap(String(raw ?? ''))[k]}`).join('，') : '未填';
  }
  if (field.type === 'BOOLEAN') {
    if (raw === undefined || raw === null) {
      return field.defaultValue == null ? '未填' : `默认 ${field.defaultValue}`;
    }
    return raw ? '开' : '关';
  }
  if (raw === undefined || raw === null || raw === '') {
    return field.defaultValue == null ? '未填' : `默认 ${field.defaultValue}`;
  }
  return String(raw);
}

/** 配置字段的控件：type → 控件的唯一映射，标签/说明/必填/默认值/可选值全部来自后端 schema */
function MiddlewareField({ field }: { field: MiddlewareFieldSchema }) {
  const placeholder = field.defaultValue == null ? undefined : `默认 ${field.defaultValue}`;
  const common = {
    name: field.name,
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

export function MiddlewareConfigSection({ views, attached, values, onAttach, onDetach, onValues }: {
  views: MiddlewareSchemaView[];
  attached: Record<string, boolean>;
  values: MiddlewareValues;
  onAttach: (key: string) => void;
  onDetach: (key: string) => void;
  onValues: (next: MiddlewareValues) => void;
}) {
  const [modalForm] = Form.useForm();
  // 弹窗编辑目标：未装配的那次进来是 undefined（要先选），已装配的那次进来就锁定了改不了目标
  const [target, setTarget] = useState<string | undefined>();
  const [mounting, setMounting] = useState(false);
  const [open, setOpen] = useState(false);

  const available = views.filter((view) => !view.array && attached[view.key] !== true);
  const mounted = views.filter((view) => attached[view.key] === true);
  const view = views.find((v) => v.key === target);

  useEffect(() => {
    if (!open || !view) {
      return;
    }
    modalForm.setFieldsValue(values[view.key] ?? {});
  }, [open, view, modalForm, values]);

  const openMount = () => {
    setMounting(true);
    setTarget(undefined);
    modalForm.resetFields();
    setOpen(true);
  };

  const openEdit = (key: string) => {
    setMounting(false);
    setTarget(key);
    setOpen(true);
  };

  const close = () => {
    setOpen(false);
    setTarget(undefined);
  };

  /** 弹窗里的「保存」只负责把值收进列表（装配）或更新值；真正落库仍是页面右上角的 Save */
  const onSave = async () => {
    if (!view) {
      return;
    }
    let row: Record<string, unknown>;
    try {
      row = await modalForm.validateFields() as Record<string, unknown>;
    } catch {
      // 必填没填：弹窗不关，红字已经标在对应字段上
      return;
    }
    onValues({ ...values, [view.key]: { ...values[view.key], ...row } });
    if (mounting) {
      onAttach(view.key);
    }
    close();
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button type="primary" icon={<SparkPlusLine />} disabled={available.length === 0} onClick={openMount}>
          装配中间件
        </Button>
      </div>

      {mounted.length === 0 ? (
        <div className="glass-card" style={{ padding: 24 }}>
          <Empty description="尚未装配中间件" />
        </div>
      ) : mounted.map((mountedView) => {
        const emptyNamespace = !mountedView.array
          && Object.keys(namespaceOf(mountedView, values[mountedView.key])).length === 0;
        return (
          <div className="glass-card" key={mountedView.key} style={{ padding: 24 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <div style={{ fontSize: 15, fontWeight: 600 }}>{mountedView.key}</div>
              <div style={{ flex: 1 }} />
              {!mountedView.array && (
                <Button type="text" size="small" icon={<SparkEditLine />} onClick={() => openEdit(mountedView.key)}>
                  编辑
                </Button>
              )}
              <Popconfirm
                title="移除该中间件？"
                description="保存后这个 Agent 不再装配它"
                okText="移除"
                cancelText="取消"
                onConfirm={() => onDetach(mountedView.key)}
              >
                <Button type="text" size="small" icon={<SparkDeleteLine />} />
              </Popconfirm>
            </div>
            <div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.55)', margin: '4px 0 12px' }}>
              {mountedView.description}
            </div>
            {mountedView.configError && (
              <Alert
                type="error"
                showIcon
                style={{ marginBottom: 12 }}
                message="已存配置不会生效"
                description={mountedView.configError}
              />
            )}
            {mountedView.array ? (
              <div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.55)' }}>
                该中间件的配置形态是数组，本界面只支持对象配置；已存的数组配置在本页保存时原样保留。
              </div>
            ) : (
              <>
                {mountedView.fields.map((field) => (
                  <div
                    key={field.name}
                    style={{ display: 'flex', gap: 12, fontSize: 13, padding: '6px 0', borderTop: '1px solid rgba(26,26,29,0.06)' }}
                  >
                    <div style={{ width: 160, color: 'rgba(26, 26, 29, 0.55)' }}>{field.label}</div>
                    <div style={{ flex: 1, wordBreak: 'break-all' }}>
                      {displayValue(field, values[mountedView.key]?.[field.name])}
                    </div>
                  </div>
                ))}
                {emptyNamespace && (
                  <div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.55)', marginTop: 8 }}>
                    一项没填且没有默认值 —— 保存后这张卡不会留下。
                  </div>
                )}
              </>
            )}
          </div>
        );
      })}

      <ResponsiveModal
        title={mounting ? '装配中间件' : '编辑配置'}
        open={open}
        onOk={onSave}
        onCancel={close}
        okText="保存"
        cancelText="取消"
        okButtonProps={{ disabled: !view }}
        destroyOnClose
        width={560}
      >
        <Form form={modalForm} layout="vertical" style={{ marginTop: 12 }}>
          {mounting && (
            <Form.Item label="中间件" tooltip="只列平台提供、且这个 Agent 还没装的">
              <Select
                placeholder="选择要装配的中间件"
                value={target}
                onChange={(value) => {
                  const key = value as string;
                  setTarget(key);
                  modalForm.resetFields();
                  modalForm.setFieldsValue(values[key] ?? {});
                }}
                options={available.map((item) => ({ value: item.key, label: item.key }))}
              />
            </Form.Item>
          )}
          {view && (
            <div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.55)', marginBottom: 12 }}>
              {view.description}
            </div>
          )}
          {view?.fields.map((field) => <MiddlewareField key={field.name} field={field} />)}
        </Form>
      </ResponsiveModal>
    </div>
  );
}
