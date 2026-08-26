import { useCallback, useEffect, useState } from 'react';
import { AutoComplete, Space, Typography } from 'antd';
import { Button, Form, Input, message, Modal, Popconfirm, Select, Switch, Table, Tag } from '@agentscope-ai/design';
import { SparkPlusLine } from '@agentscope-ai/icons';
import {
  modelCreate,
  modelDelete,
  modelList,
  modelUpdate,
  modelVendorModels,
  type ModelEntry,
} from '../api/model';

const PROVIDER_OPTIONS = [
  { label: 'DashScope（通义千问系）', value: 'dashscope' },
  { label: 'OpenAI / 兼容端点', value: 'openai' },
];

// 多模态能力位（SPEC §19）
const CAPABILITY_OPTIONS = [
  { label: '图片（image）', value: 'image' },
  { label: '视频（video）', value: 'video' },
];

/** 模型入口管理（SPEC §6.4 修订：界面配置化，admin 专属） */
export default function Models() {
  const [list, setList] = useState<ModelEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<ModelEntry | null>(null);
  const [createForm] = Form.useForm();
  const [editForm] = Form.useForm();
  /** DashScope 在售模型清单（模型名下拉数据源；拉取失败降级为手输） */
  const [vendorModels, setVendorModels] = useState<string[]>([]);
  const [vendorLoading, setVendorLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setList(await modelList());
    } catch {
      // 已统一提示
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    // 模型清单仅 admin 可见本页；失败静默降级为手输
    setVendorLoading(true);
    modelVendorModels('dashscope')
      .then(setVendorModels)
      .catch(() => setVendorModels([]))
      .finally(() => setVendorLoading(false));
  }, [load]);

  const onCreate = async () => {
    const values = await createForm.validateFields();
    await modelCreate({
      provider: values.provider,
      modelName: values.modelName.trim(),
      displayName: values.displayName || null,
      baseUrl: values.baseUrl?.trim() || null,
      capabilities: values.capabilities?.length ? values.capabilities.join(',') : null,
      status: 1,
    });
    message.success('创建成功');
    setCreateOpen(false);
    createForm.resetFields();
    load();
  };

  const onEdit = async () => {
    if (!editTarget?.id) return;
    const values = await editForm.validateFields();
    await modelUpdate(editTarget.id, {
      provider: values.provider,
      modelName: values.modelName.trim(),
      displayName: values.displayName || null,
      baseUrl: values.baseUrl?.trim() || null,
      capabilities: values.capabilities?.length ? values.capabilities.join(',') : null,
    });
    message.success('保存成功');
    setEditTarget(null);
    load();
  };

  const onToggleStatus = async (record: ModelEntry, enabled: boolean) => {
    if (!record.id) return;
    await modelUpdate(record.id, { status: enabled ? 1 : 0 });
    message.success(enabled ? '已启用' : '已停用');
    load();
  };

  return (
    <div style={{ padding: window.innerWidth < 768 ? 12 : 24 }}>
      <Space style={{ marginBottom: 8 }} align="center">
        <Button type="primary" icon={<SparkPlusLine />} onClick={() => setCreateOpen(true)}>
          新建模型入口
        </Button>
      </Space>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
        模型标识形如 provider:model（如 dashscope:qwen-plus）。API Key 仍由服务器环境变量
        （DASHSCOPE_API_KEY / OPENAI_API_KEY）管理，界面不存储任何密钥；OpenAI 兼容端点可在此配置 baseUrl。
      </Typography.Paragraph>
      <div className="mobile-table-scroll">
      <Table<ModelEntry>
        rowKey="id"
        loading={loading}
        dataSource={list}
        scroll={window.innerWidth < 768 ? { x: 800 } : undefined}
        pagination={false}
        columns={[
          {
            title: '模型标识',
            width: 220,
            render: (_: unknown, r: ModelEntry) => (
              <Typography.Text code>
                {r.provider}:{r.modelName}
              </Typography.Text>
            ),
          },
          { title: '展示名', dataIndex: 'displayName', width: 160, render: (v: string | null) => v || '-' },
          {
            title: '供应商',
            dataIndex: 'provider',
            width: 110,
            render: (v: string) => <Tag color={v === 'dashscope' ? 'purple' : 'blue'}>{v}</Tag>,
          },
          {
            title: 'baseUrl',
            dataIndex: 'baseUrl',
            ellipsis: true,
            render: (v: string | null) => v || '-',
          },
          {
            title: '多模态能力',
            dataIndex: 'capabilities',
            width: 130,
            render: (v: string | null) =>
              v
                ? v.split(',').map((c) => (
                    <Tag key={c} color="geekblue">
                      {c}
                    </Tag>
                  ))
                : '-',
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (status: number, r: ModelEntry) => (
              <Switch size="small" checked={status === 1} onChange={(checked: boolean) => onToggleStatus(r, checked)} />
            ),
          },
          { title: '创建人', dataIndex: 'createdBy', width: 100 },
          {
            title: '操作',
            width: 140,
            render: (_: unknown, record: ModelEntry) => (
              <Space>
                <Button
                  size="small"
                  onClick={() => {
                    editForm.setFieldsValue({
                      provider: record.provider,
                      modelName: record.modelName,
                      displayName: record.displayName || '',
                      baseUrl: record.baseUrl || '',
                      capabilities: record.capabilities ? record.capabilities.split(',') : [],
                    });
                    setEditTarget(record);
                  }}
                >
                  编辑
                </Button>
                <Popconfirm
                  title={`确认删除「${record.provider}:${record.modelName}」？引用该模型的 Agent 将无法对话`}
                  onConfirm={async () => {
                    if (!record.id) return;
                    await modelDelete(record.id);
                    message.success('已删除');
                    load();
                  }}
                >
                  <Button size="small" danger>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />
      </div>

      <Modal
        title="新建模型入口"
        open={createOpen}
        onOk={onCreate}
        onCancel={() => setCreateOpen(false)}
        okText="创建"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={createForm} layout="vertical" preserve={false} initialValues={{ provider: 'dashscope' }}>
          <Form.Item name="provider" label="供应商" rules={[{ required: true }]}>
            <Select options={PROVIDER_OPTIONS} />
          </Form.Item>
          <Form.Item
            noStyle
            shouldUpdate={(prev, cur) => prev.provider !== cur.provider}
          >
            {({ getFieldValue }) =>
              getFieldValue('provider') === 'dashscope' && vendorModels.length > 0 ? (
                <Form.Item
                  name="modelName"
                  label="模型名"
                  rules={[{ required: true, message: '请选择模型名' }, { max: 64, message: '不超过 64 位' }]}
                  tooltip="数据源为 DashScope 在售清单（服务器代拉，10 分钟缓存）；也可手动输入清单外的模型名"
                >
                  <AutoComplete
                    options={vendorModels.map((m) => ({ value: m }))}
                    filterOption={(input, option) =>
                      String(option?.value ?? '').toLowerCase().includes(input.toLowerCase())
                    }
                    placeholder="搜索或选择，也可手动输入"
                    disabled={vendorLoading}
                  />
                </Form.Item>
              ) : (
                <Form.Item
                  name="modelName"
                  label="模型名"
                  rules={[{ required: true, message: '请输入模型名' }, { max: 64, message: '不超过 64 位' }]}
                >
                  <Input placeholder="如 qwen-plus / gpt-5.2" />
                </Form.Item>
              )
            }
          </Form.Item>
          <Form.Item name="displayName" label="展示名">
            <Input placeholder="如 通义千问 Plus（留空显示模型标识）" />
          </Form.Item>
          <Form.Item name="capabilities" label="多模态能力" tooltip="勾选后前端对话台开放对应附件上传；DashScope 会改走 multimodal-generation 端点">
            <Select mode="multiple" options={CAPABILITY_OPTIONS} placeholder="纯文本（默认）" />
          </Form.Item>
          <Form.Item
            noStyle
            shouldUpdate={(prev, cur) => prev.provider !== cur.provider}
          >
            {({ getFieldValue }) =>
              getFieldValue('provider') === 'openai' ? (
                <Form.Item name="baseUrl" label="baseUrl（OpenAI 兼容端点，可选）">
                  <Input placeholder="如 https://api.example.com/v1" />
                </Form.Item>
              ) : null
            }
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`编辑 ${editTarget?.provider || ''}:${editTarget?.modelName || ''}`}
        open={!!editTarget}
        onOk={onEdit}
        onCancel={() => setEditTarget(null)}
        okText="保存"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={editForm} layout="vertical" preserve={false}>
          <Form.Item name="provider" label="供应商" rules={[{ required: true }]}>
            <Select options={PROVIDER_OPTIONS} />
          </Form.Item>
          <Form.Item
            noStyle
            shouldUpdate={(prev, cur) => prev.provider !== cur.provider}
          >
            {({ getFieldValue }) =>
              getFieldValue('provider') === 'dashscope' && vendorModels.length > 0 ? (
                <Form.Item
                  name="modelName"
                  label="模型名"
                  rules={[{ required: true, message: '请选择模型名' }, { max: 64, message: '不超过 64 位' }]}
                  tooltip="数据源为 DashScope 在售清单；也可手动输入清单外的模型名"
                >
                  <AutoComplete
                    options={vendorModels.map((m) => ({ value: m }))}
                    filterOption={(input, option) =>
                      String(option?.value ?? '').toLowerCase().includes(input.toLowerCase())
                    }
                    placeholder="搜索或选择，也可手动输入"
                    disabled={vendorLoading}
                  />
                </Form.Item>
              ) : (
                <Form.Item
                  name="modelName"
                  label="模型名"
                  rules={[{ required: true, message: '请输入模型名' }, { max: 64, message: '不超过 64 位' }]}
                >
                  <Input />
                </Form.Item>
              )
            }
          </Form.Item>
          <Form.Item name="displayName" label="展示名">
            <Input placeholder="留空显示模型标识" />
          </Form.Item>
          <Form.Item name="capabilities" label="多模态能力" tooltip="勾选后前端对话台开放对应附件上传；DashScope 会改走 multimodal-generation 端点">
            <Select mode="multiple" options={CAPABILITY_OPTIONS} placeholder="纯文本（默认）" />
          </Form.Item>
          <Form.Item name="baseUrl" label="baseUrl（仅 OpenAI 生效，可选）">
            <Input placeholder="留空使用环境变量 OPENAI_BASE_URL" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
