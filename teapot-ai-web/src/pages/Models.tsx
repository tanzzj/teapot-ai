import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { AutoComplete, Space, Typography } from 'antd';
import { Button, Form, Input, InputNumber, message, Popconfirm, Select, Switch, Table, Tag } from '@agentscope-ai/design';
import { SparkPlusLine } from '@agentscope-ai/icons';
import {
  modelCreate,
  modelDelete,
  modelList,
  modelProbe,
  modelProviders,
  modelUpdate,
  modelVendorModels,
  type ModelEntry,
  type ModelProviderMeta,
} from '../api/model';
import { workspaceAll } from '../api/workspace';
import type { Workspace } from '../types';
import { useIsPhone } from '../hooks/useIsPhone';
import { ResponsiveModal } from '../components/ResponsiveModal';
import { useVendorModelMeta, VendorModelMetaNote } from '../components/VendorModelMeta';

/** 全局空间桶（SPEC §28.11）：模型开放到 0 = 全部空间（含个人空间）可用 */
const GLOBAL_WS = 0;

/**
 * 供应商展示名（SPEC §29.16）：清单本身来自 /api/model/providers（后端策略 Bean 汇总），
 * 这里只补中文说明；接口异常时回落到这份静态名单，保证表单仍可用。
 */
const PROVIDER_LABELS: Record<string, string> = {
  dashscope: 'DashScope（通义千问系）',
  openai: 'OpenAI / 兼容端点',
  'openai-official': 'OpenAI 官方 Responses API',
  anthropic: 'Anthropic（Claude）',
  gemini: 'Google Gemini',
  ollama: 'Ollama（本地推理，无需密钥）',
};
const PROVIDER_FALLBACK = Object.keys(PROVIDER_LABELS);

// 多模态能力位（SPEC §19）
const CAPABILITY_OPTIONS = [
  { label: '图片（image）', value: 'image' },
  { label: '视频（video）', value: 'video' },
];

/** 上下文窗口展示：131072 → 128K，1048576 → 1M（SPEC §30.1） */
const formatWindow = (tokens: number) =>
  tokens >= 1024 * 1024 ? `${Math.round(tokens / (1024 * 1024))}M` : `${Math.round(tokens / 1024)}K`;

const CAPABILITIES_TOOLTIP = '勾选后前端对话台开放对应附件上传；DashScope 会改走 multimodal-generation 端点';
const WINDOW_TOOLTIP =
  '模型总窗口，决定 harness 给对话留多少预算。留空 = 交给框架按模型名查表，表里没有的新模型（如 qwen3.8-*）会退回 64000 硬编码上限，长会话报「Prepared model input exceeds budget」。';

/**
 * 能力位与上下文窗口（SPEC §30.6）：供应商报得出的（当前 DashScope）只展示同步结果、不让人手填；
 * 报不出的才留手工字段。隐藏的两个 Form.Item 仍注册，同步值随表单一起提交。
 */
function CapabilityAndWindow({ discovery, note }: { discovery: boolean; note: ReactNode }) {
  if (discovery) {
    return (
      <>
        <Form.Item label="多模态能力 / 上下文窗口" tooltip="供应商报得出就不让人手填；保存时后端还会向供应商再核对一次">
          {note}
        </Form.Item>
        <Form.Item name="capabilities" hidden>
          <Select mode="multiple" options={CAPABILITY_OPTIONS} />
        </Form.Item>
        <Form.Item name="contextWindowSize" hidden>
          <InputNumber />
        </Form.Item>
      </>
    );
  }
  return (
    <>
      <Form.Item name="capabilities" label="多模态能力" tooltip={CAPABILITIES_TOOLTIP}>
        <Select mode="multiple" options={CAPABILITY_OPTIONS} placeholder="纯文本（默认）" />
      </Form.Item>
      <Form.Item name="contextWindowSize" label="上下文窗口（tokens）" tooltip={WINDOW_TOOLTIP}>
        <InputNumber
          min={0}
          step={1024}
          style={{ width: '100%' }}
          placeholder="留空=由框架按模型名推断（查不到兜底 64000）"
        />
      </Form.Item>
    </>
  );
}

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
  /** 已接入供应商（SPEC §29.16）：拉不到时回落静态名单 */
  const [providerMeta, setProviderMeta] = useState<ModelProviderMeta[]>([]);
  const providerOptions = (providerMeta.length ? providerMeta.map((p) => p.provider) : PROVIDER_FALLBACK)
    .map((provider) => ({ label: PROVIDER_LABELS[provider] ?? provider, value: provider }));
  const [vendorLoading, setVendorLoading] = useState(false);
  /** 正在探活的入口 id（按钮 loading，避免重复点击） */
  const [probingId, setProbingId] = useState<number | null>(null);

  // 供应商报得出元数据的（当前 DashScope），能力位与窗口一律同步、不让人手填（SPEC §30.6）
  const discoveryOf = (provider?: string) =>
    Boolean(providerMeta.find((m) => m.provider === provider)?.modelDiscovery);
  const createProvider = Form.useWatch('provider', createForm) as string | undefined;
  const createModelName = Form.useWatch('modelName', createForm) as string | undefined;
  const editProvider = Form.useWatch('provider', editForm) as string | undefined;
  const editModelName = Form.useWatch('modelName', editForm) as string | undefined;
  const createDiscovery = discoveryOf(createProvider);
  const editDiscovery = discoveryOf(editProvider);
  const createMeta = useVendorModelMeta({
    provider: createProvider,
    modelName: createModelName,
    discovery: createDiscovery,
    onSynced: (m) => createForm.setFieldsValue({
      capabilities: m.capabilities,
      contextWindowSize: m.contextWindowSize,
    }),
  });
  const editMeta = useVendorModelMeta({
    provider: editProvider,
    modelName: editModelName,
    discovery: editDiscovery,
    onSynced: (m) => editForm.setFieldsValue({
      capabilities: m.capabilities,
      contextWindowSize: m.contextWindowSize,
    }),
  });
  /** 团队空间清单（模型开放范围数据源，SPEC §28.11） */
  const [teamSpaces, setTeamSpaces] = useState<Workspace[]>([]);
  const isPhone = useIsPhone();

  const wsOptions = [
    { label: '全部空间（含个人）', value: GLOBAL_WS },
    ...teamSpaces
      .filter((w): w is Workspace & { id: number } => typeof w.id === 'number')
      .map((w) => ({ label: w.workspaceName, value: w.id })),
  ];
  const wsNameById = (id: number) =>
    id === GLOBAL_WS ? '全部空间' : teamSpaces.find((w) => w.id === id)?.workspaceName ?? `#${id}`;

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
    // 团队空间清单（模型开放范围）；失败静默为空
    workspaceAll()
      .then(setTeamSpaces)
      .catch(() => setTeamSpaces([]));
    // 已接入供应商清单（后端策略 Bean）；失败静默回落静态名单
    modelProviders()
      .then(setProviderMeta)
      .catch(() => setProviderMeta([]));
  }, [load]);

  const onCreate = async () => {
    const values = await createForm.validateFields();
    await modelCreate({
      provider: values.provider,
      modelName: values.modelName.trim(),
      displayName: values.displayName || null,
      baseUrl: values.baseUrl?.trim() || null,
      capabilities: values.capabilities?.length ? values.capabilities.join(',') : null,
      contextWindowSize: values.contextWindowSize || 0,
      status: 1,
      workspaceIds: values.workspaceIds || [],
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
      contextWindowSize: values.contextWindowSize || 0,
      workspaceIds: values.workspaceIds || [],
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

  /**
   * 探活（SPEC §30.2）：只打供应商的模型详情端点，不发补全请求、不计费。
   * 供应商报出的上下文窗口由后端直接回填，这里刷新列表即可看到生效值。
   */
  const onProbe = async (record: ModelEntry) => {
    if (!record.id) return;
    setProbingId(record.id);
    try {
      const result = await modelProbe(record.id);
      if (result.ok) {
        message.success(result.message);
      } else {
        message.error(result.message);
      }
      load();
    } finally {
      setProbingId(null);
    }
  };

  return (
    <div style={{ padding: isPhone ? 12 : 24 }}>
      <Space style={{ marginBottom: 8 }} align="center">
        <Button type="primary" icon={<SparkPlusLine />} onClick={() => setCreateOpen(true)}>
          新建模型入口
        </Button>
      </Space>
      <div className="mobile-table-scroll">
      <Table<ModelEntry>
        rowKey="id"
        loading={loading}
        dataSource={list}
        scroll={isPhone ? { x: 800 } : undefined}
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
            title: '上下文窗口',
            dataIndex: 'contextWindowSize',
            width: 110,
            // 未配置时框架按模型名查表，查不到会退回 64000 兜底上限（SPEC §30.1）
            render: (v: number | null) =>
              v && v > 0 ? (
                formatWindow(v)
              ) : (
                <span style={{ color: 'rgba(26,26,29,0.35)' }}>未配置</span>
              ),
          },
          {
            title: '开放空间',
            dataIndex: 'workspaceIds',
            width: 160,
            render: (ids: number[] | undefined) => {
              const list = ids || [];
              if (list.includes(GLOBAL_WS)) {
                return <Tag color="green">全部空间</Tag>;
              }
              if (list.length === 0) {
                return <span style={{ color: 'rgba(26,26,29,0.35)' }}>未开放</span>;
              }
              return list.map((id) => (
                <Tag key={id} color="blue">
                  {wsNameById(id)}
                </Tag>
              ));
            },
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
            width: 200,
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
                      contextWindowSize: record.contextWindowSize || undefined,
                      workspaceIds: record.workspaceIds || [],
                    });
                    setEditTarget(record);
                  }}
                >
                  编辑
                </Button>
                <Button
                  size="small"
                  loading={probingId === record.id}
                  onClick={() => onProbe(record)}
                >
                  探活
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

      <ResponsiveModal
        title="新建模型入口"
        open={createOpen}
        onOk={onCreate}
        onCancel={() => setCreateOpen(false)}
        okText="创建"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={createForm} layout="vertical" preserve={false} initialValues={{ provider: 'dashscope', workspaceIds: [GLOBAL_WS] }}>
          <Form.Item name="provider" label="供应商" rules={[{ required: true }]}>
            <Select options={providerOptions} />
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
          <CapabilityAndWindow discovery={createDiscovery} note={
            <VendorModelMetaNote meta={createMeta.meta} syncing={createMeta.syncing} onResync={createMeta.resync} />
          } />
          <Form.Item
            name="workspaceIds"
            label="开放空间"
            tooltip="模型仅对开放的空间可见可用；「全部空间（含个人）」对所有空间开放，留空则该模型不可被任何空间选用"
          >
            <Select mode="multiple" options={wsOptions} placeholder="选择开放的空间" />
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
      </ResponsiveModal>

      <ResponsiveModal
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
            <Select options={providerOptions} />
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
          <CapabilityAndWindow discovery={editDiscovery} note={
            <VendorModelMetaNote meta={editMeta.meta} syncing={editMeta.syncing} onResync={editMeta.resync} />
          } />
          <Form.Item
            name="workspaceIds"
            label="开放空间"
            tooltip="模型仅对开放的空间可见可用；「全部空间（含个人）」对所有空间开放，留空则该模型不可被任何空间选用"
          >
            <Select mode="multiple" options={wsOptions} placeholder="选择开放的空间" />
          </Form.Item>
          <Form.Item name="baseUrl" label="baseUrl（仅 OpenAI 生效，可选）">
            <Input placeholder="留空使用环境变量 OPENAI_BASE_URL" />
          </Form.Item>
        </Form>
      </ResponsiveModal>
    </div>
  );
}
