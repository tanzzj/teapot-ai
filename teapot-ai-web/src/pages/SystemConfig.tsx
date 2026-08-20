import { useCallback, useEffect, useState } from 'react';
import { Col, Row, Spin, theme } from 'antd';
import { Button, Form, Input, message, Modal, Popconfirm, Radio, Table, Tag } from '@agentscope-ai/design';
import {
  ApiOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  PlusOutlined,
  SettingOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import Models from './Models';
import Users from './Users';
import {
  createSandboxRecord,
  createStorageRecord,
  deleteSandboxRecord,
  deleteStorageRecord,
  sandboxList,
  storageList,
  updateSandboxRecord,
  updateStorageRecord,
} from '../api/config';
import type { SandboxListData, SandboxRecord, StorageListData, StorageRecord } from '../types';

/**
 * 系统配置页（SPEC §21/§22，admin 一站式管理台）：
 * 模型 / 用户 / 存储（OSS 连接记录，§20.12）/ 沙箱（沙箱连接记录，§22.2）。
 * 载体选择已下放 Agent 级（AgentConfig feature，§22.1/§22.2），本页只维护记录。
 * 布局复刻 AgentDetail 左侧胶囊菜单。
 */
type Section = 'models' | 'users' | 'storage' | 'sandbox';

export default function SystemConfigPage() {
  const { section: rawSection } = useParams();
  const navigate = useNavigate();
  const { token } = theme.useToken();
  const section: Section = (['models', 'users', 'storage', 'sandbox'] as const)
    .includes(rawSection as Section)
    ? (rawSection as Section)
    : 'models';

  const menuItems: { key: Section; label: string; icon: React.ReactNode }[] = [
    { key: 'models', label: '模型', icon: <ApiOutlined /> },
    { key: 'users', label: '用户', icon: <TeamOutlined /> },
    { key: 'storage', label: '存储', icon: <DatabaseOutlined /> },
    { key: 'sandbox', label: '沙箱', icon: <CloudServerOutlined /> },
  ];

  return (
    <div style={{ padding: '20px 28px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 20 }}>
        <SettingOutlined style={{ fontSize: 18, color: 'rgba(26,26,29,0.7)' }} />
        <span style={{ fontWeight: 700, fontSize: 17, color: 'rgba(26, 26, 29, 0.92)' }}>系统配置</span>
        <span style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.45)' }}>模型 · 用户 · 存储 · 沙箱（仅管理员）</span>
      </div>

      <Row gutter={20}>
        {/* 左侧胶囊菜单（同 AgentDetail 风格） */}
        <Col xs={24} md={6} lg={5} style={{ marginBottom: 16 }}>
          <div className="glass-card" style={{ padding: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
            {menuItems.map((m) => {
              const active = section === m.key;
              return (
                <div
                  key={m.key}
                  onClick={() => navigate(`/system/${m.key}`)}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    padding: '10px 14px',
                    borderRadius: 10,
                    cursor: 'pointer',
                    fontSize: 13.5,
                    fontWeight: active ? 600 : 400,
                    color: active ? 'rgba(26, 26, 29, 0.92)' : 'rgba(26, 26, 29, 0.6)',
                    background: active ? '#fff' : 'transparent',
                    boxShadow: active ? '0 2px 8px rgba(0, 0, 0, 0.08)' : 'none',
                    transition: 'all 0.2s ease',
                  }}
                >
                  {m.icon} {m.label}
                </div>
              );
            })}
          </div>
        </Col>

        {/* 右侧内容 */}
        <Col xs={24} md={18} lg={19}>
          {section === 'models' && <Models />}
          {section === 'users' && <Users />}
          {section === 'storage' && <StorageSection />}
          {section === 'sandbox' && <SandboxSection />}
        </Col>
      </Row>
      {/* token 仅用于保持主题上下文一致（与其他页面同风格） */}
      <span style={{ display: 'none' }}>{token.colorPrimary}</span>
    </div>
  );
}

/* ---------------- 存储分区（SPEC §20.12/§22.1：仅 OSS 连接记录；载体按 Agent 选择） ---------------- */

function StorageSection() {
  const [recordForm] = Form.useForm();
  const [loading, setLoading] = useState(true);
  const [savingRecord, setSavingRecord] = useState(false);
  const [listData, setListData] = useState<StorageListData | null>(null);
  const [modal, setModal] = useState<{ mode: 'create' | 'edit' } | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setListData(await storageList());
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const onDelete = async (name: string) => {
    const l = await deleteStorageRecord(name);
    setListData(l);
    message.success('记录已删除');
  };

  const openCreate = () => {
    recordForm.resetFields();
    setModal({ mode: 'create' });
  };

  const openEdit = (record: StorageRecord) => {
    recordForm.resetFields();
    recordForm.setFieldsValue({
      name: record.name,
      region: record.region,
      bucket: record.bucket,
      endpoint: record.endpoint,
      customDomain: record.customDomain,
      keyPrefix: record.keyPrefix,
      remark: record.remark,
    });
    setModal({ mode: 'edit' });
  };

  /** Modal 提交：新建 / 更新记录（AK/Secret 编辑时留空不修改） */
  const onSaveRecord = async () => {
    const values = await recordForm.validateFields();
    setSavingRecord(true);
    try {
      const payload: Record<string, string> = {
        name: values.name,
        accessKeyId: values.accessKeyId || '',
        accessKeySecret: values.accessKeySecret || '',
        region: values.region || '',
        bucket: values.bucket || '',
        endpoint: values.endpoint || '',
        customDomain: values.customDomain || '',
        keyPrefix: values.keyPrefix || '',
        remark: values.remark || '',
      };
      const l = modal?.mode === 'edit'
        ? await updateStorageRecord(payload)
        : await createStorageRecord(payload);
      setListData(l);
      setModal(null);
      message.success(modal?.mode === 'edit' ? '记录已更新' : '记录已创建');
    } finally {
      setSavingRecord(false);
    }
  };

  return (
    <Spin spinning={loading}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        {/* OSS 连接记录表（§20.12 多记录；§22.1 载体按 Agent 选择，无全局策略） */}
        <div className="glass-card" style={{ padding: 24 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
            <div>
              <div style={{ fontSize: 15, fontWeight: 700 }}>OSS 连接记录</div>
              <div style={{ fontSize: 12, color: 'rgba(26,26,29,0.5)' }}>
                支持多条记录（不同 bucket / 账号）；Agent 在各自配置页（Basic Info）选择其一作为图片载体。
                AK/Secret 加密入库，界面不回显明文。
              </div>
            </div>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新建记录
            </Button>
          </div>
          <Table<StorageRecord>
            rowKey="name"
            size="small"
            pagination={false}
            dataSource={listData?.records ?? []}
            scroll={window.innerWidth < 768 ? { x: 720 } : undefined}
            columns={[
              { title: '记录名', dataIndex: 'name' },
              { title: 'Region', dataIndex: 'region' },
              { title: 'Bucket', dataIndex: 'bucket' },
              {
                title: '凭证',
                dataIndex: 'accessKeyConfigured',
                render: (v: boolean) => (v ? <Tag color="blue">已配置</Tag> : <Tag>未配置</Tag>),
              },
              {
                title: '更新时间',
                dataIndex: 'updatedAt',
                render: (v?: string) => (v ? v.replace('T', ' ').slice(0, 16) : '-'),
              },
              {
                title: '操作',
                render: (_: unknown, r: StorageRecord) => (
                  <span style={{ display: 'flex', gap: 10, fontSize: 13 }}>
                    <a onClick={() => openEdit(r)}>编辑</a>
                    <Popconfirm title={`删除记录 ${r.name}？`} onConfirm={() => onDelete(r.name)}>
                      <a style={{ color: '#cf1322' }}>删除</a>
                    </Popconfirm>
                  </span>
                ),
              },
            ]}
          />
        </div>

        {/* 新建 / 编辑记录 Modal */}
        <Modal
          title={modal?.mode === 'edit' ? '编辑 OSS 连接记录' : '新建 OSS 连接记录'}
          open={modal !== null}
          confirmLoading={savingRecord}
          onOk={onSaveRecord}
          onCancel={() => setModal(null)}
          destroyOnClose
        >
          <Form form={recordForm} layout="vertical" style={{ marginTop: 12 }}>
            <Form.Item name="name" label="记录名" rules={[{ required: true, message: '必填' }]}>
              <Input placeholder="如 teamer-prod" disabled={modal?.mode === 'edit'} />
            </Form.Item>
            <Row gutter={16}>
              <Col xs={24} sm={12}>
                <Form.Item
                  name="accessKeyId"
                  label="AccessKey ID"
                  rules={[{ required: modal?.mode === 'create', message: '必填' }]}
                  tooltip={modal?.mode === 'edit' ? '留空不修改；AES-GCM 加密入库' : 'AES-GCM 加密入库'}
                >
                  <Input.Password placeholder={modal?.mode === 'edit' ? '留空不修改' : ''} />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12}>
                <Form.Item
                  name="accessKeySecret"
                  label="AccessKey Secret"
                  rules={[{ required: modal?.mode === 'create', message: '必填' }]}
                  tooltip={modal?.mode === 'edit' ? '留空不修改；AES-GCM 加密入库' : 'AES-GCM 加密入库'}
                >
                  <Input.Password placeholder={modal?.mode === 'edit' ? '留空不修改' : ''} />
                </Form.Item>
              </Col>
            </Row>
            <Row gutter={16}>
              <Col xs={24} sm={12}>
                <Form.Item name="region" label="Region" rules={[{ required: true, message: '必填' }]}>
                  <Input placeholder="cn-beijing" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12}>
                <Form.Item name="bucket" label="Bucket" rules={[{ required: true, message: '必填' }]}>
                  <Input placeholder="teapot-ai-images" />
                </Form.Item>
              </Col>
            </Row>
            <Row gutter={16}>
              <Col xs={24} sm={12}>
                <Form.Item name="endpoint" label="Endpoint（可选）" tooltip="与自定义域名二选一；自定义域名优先">
                  <Input placeholder="oss-cn-beijing.aliyuncs.com" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12}>
                <Form.Item name="customDomain" label="自定义域名（可选）" tooltip="含 https://；内地新 bucket 数据面必须走自定义域名">
                  <Input placeholder="https://oss.teamer.com.cn" />
                </Form.Item>
              </Col>
            </Row>
            <Form.Item name="keyPrefix" label="对象 key 前缀" tooltip="建议配置 OSS 生命周期规则定期清理">
              <Input placeholder="teapot-ai/chat-images/" />
            </Form.Item>
            <Form.Item name="remark" label="备注">
              <Input placeholder="可选" />
            </Form.Item>
          </Form>
        </Modal>
      </div>
    </Spin>
  );
}

/* ---------------- 沙箱分区（SPEC §22.2：沙箱连接记录；承载按 Agent 选择） ---------------- */

/** 从存量 domain/apiBaseUrl 反解析 e2b region（唯一变量）；不匹配返回空串 */
function parseE2bRegion(domain?: string, apiBaseUrl?: string): string {
  if (domain) {
    const m = domain.match(/^([^.]+)\.e2b\.fc\.aliyuncs\.com$/);
    if (m) return m[1];
  }
  if (apiBaseUrl) {
    const m = apiBaseUrl.match(/^https:\/\/api\.([^.]+)\.e2b\.fc\.aliyuncs\.com\/?$/);
    if (m) return m[1];
  }
  return '';
}

function SandboxSection() {
  const [recordForm] = Form.useForm();
  const [loading, setLoading] = useState(true);
  const [savingRecord, setSavingRecord] = useState(false);
  const [listData, setListData] = useState<SandboxListData | null>(null);
  const [modal, setModal] = useState<{ mode: 'create' | 'edit' } | null>(null);
  const linkType = Form.useWatch('linkType', recordForm);
  const e2bRegionWatch = Form.useWatch('e2bRegion', recordForm);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setListData(await sandboxList());
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const onDelete = async (name: string) => {
    const l = await deleteSandboxRecord(name);
    setListData(l);
    message.success('记录已删除');
  };

  const openCreate = () => {
    recordForm.resetFields();
    recordForm.setFieldsValue({ linkType: 'e2b' });
    setModal({ mode: 'create' });
  };

  const openEdit = (record: SandboxRecord) => {
    recordForm.resetFields();
    recordForm.setFieldsValue({
      name: record.name,
      linkType: record.linkType,
      e2bRegion: parseE2bRegion(record.e2bDomain, record.e2bApiBaseUrl),
      e2bDefaultTemplate: record.e2bDefaultTemplate,
      region: record.region,
      defaultTemplate: record.defaultTemplate,
      mcpServerUrl: record.mcpServerUrl,
      remark: record.remark,
    });
    setModal({ mode: 'edit' });
  };

  /** Modal 提交：新建 / 更新记录（敏感列编辑时留空不修改；e2b 只填 region，前端派生完整 URL/Domain） */
  const onSaveRecord = async () => {
    const values = await recordForm.validateFields();
    setSavingRecord(true);
    try {
      const e2bRegion = (values.e2bRegion || '').trim();
      const payload: Record<string, string> = {
        name: values.name,
        linkType: values.linkType || '',
        e2bApiKey: values.e2bApiKey || '',
        e2bApiBaseUrl: e2bRegion ? `https://api.${e2bRegion}.e2b.fc.aliyuncs.com` : '',
        e2bDomain: e2bRegion ? `${e2bRegion}.e2b.fc.aliyuncs.com` : '',
        e2bDefaultTemplate: values.e2bDefaultTemplate || '',
        apiKey: values.apiKey || '',
        accountId: values.accountId || '',
        region: values.region || '',
        defaultTemplate: values.defaultTemplate || '',
        mcpServerUrl: values.mcpServerUrl || '',
        remark: values.remark || '',
      };
      const l = modal?.mode === 'edit'
        ? await updateSandboxRecord(payload)
        : await createSandboxRecord(payload);
      setListData(l);
      setModal(null);
      message.success(modal?.mode === 'edit' ? '记录已更新' : '记录已创建');
    } finally {
      setSavingRecord(false);
    }
  };

  /** 记录按其 linkType 判断凭证是否齐备 */
  const configuredOf = (r: SandboxRecord) =>
    r.linkType === 'e2b' ? !!r.e2bConfigured : !!r.agentrunConfigured;

  return (
    <Spin spinning={loading}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        {/* 沙箱连接记录表（§22.2 多记录；承载按 Agent 选择，无全局激活） */}
        <div className="glass-card" style={{ padding: 24 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
            <div>
              <div style={{ fontSize: 15, fontWeight: 700 }}>沙箱连接记录</div>
              <div style={{ fontSize: 12, color: 'rgba(26,26,29,0.5)' }}>
                支持多条记录（E2B 兼容 / AgentRun MCP 双链路）；Agent 启用沙箱时在各自配置页选择其一作为承载。
                API Key / 账号 ID 加密入库，界面不回显明文。
              </div>
            </div>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新建记录
            </Button>
          </div>
          <Table<SandboxRecord>
            rowKey="name"
            size="small"
            pagination={false}
            dataSource={listData?.records ?? []}
            scroll={window.innerWidth < 768 ? { x: 720 } : undefined}
            columns={[
              { title: '记录名', dataIndex: 'name' },
              {
                title: '链路',
                dataIndex: 'linkType',
                render: (v: string) => (
                  <Tag color={v === 'e2b' ? 'purple' : 'geekblue'}>{v === 'e2b' ? 'E2B 兼容' : 'AgentRun MCP'}</Tag>
                ),
              },
              {
                title: '凭证',
                render: (_: unknown, r: SandboxRecord) =>
                  configuredOf(r) ? <Tag color="blue">已配置</Tag> : <Tag>未配置</Tag>,
              },
              {
                title: '更新时间',
                dataIndex: 'updatedAt',
                render: (v?: string) => (v ? v.replace('T', ' ').slice(0, 16) : '-'),
              },
              {
                title: '操作',
                render: (_: unknown, r: SandboxRecord) => (
                  <span style={{ display: 'flex', gap: 10, fontSize: 13 }}>
                    <a onClick={() => openEdit(r)}>编辑</a>
                    <Popconfirm title={`删除记录 ${r.name}？引用它的 Agent 沙箱将降级为无 shell。`} onConfirm={() => onDelete(r.name)}>
                      <a style={{ color: '#cf1322' }}>删除</a>
                    </Popconfirm>
                  </span>
                ),
              },
            ]}
          />
        </div>

        {/* 新建 / 编辑记录 Modal */}
        <Modal
          title={modal?.mode === 'edit' ? '编辑沙箱连接记录' : '新建沙箱连接记录'}
          open={modal !== null}
          confirmLoading={savingRecord}
          onOk={onSaveRecord}
          onCancel={() => setModal(null)}
          destroyOnClose
          width={620}
        >
          <Form form={recordForm} layout="vertical" style={{ marginTop: 12 }}>
            <Form.Item name="name" label="记录名" rules={[{ required: true, message: '必填' }]}>
              <Input placeholder="如 e2b-prod" disabled={modal?.mode === 'edit'} />
            </Form.Item>
            <Form.Item name="linkType" label="链路类型" rules={[{ required: true, message: '必选' }]}>
              <Radio.Group>
                <Radio value="e2b">E2B 兼容</Radio>
                <Radio value="agentrun">AgentRun MCP</Radio>
              </Radio.Group>
            </Form.Item>

            {linkType === 'e2b' && (
              <>
                <Form.Item
                  name="e2bApiKey"
                  label="E2B API Key"
                  rules={[{ required: modal?.mode === 'create', message: '必填' }]}
                  tooltip={modal?.mode === 'edit' ? '留空不修改；AES-GCM 加密入库' : 'AES-GCM 加密入库'}
                >
                  <Input.Password placeholder={modal?.mode === 'edit' ? '留空不修改' : ''} />
                </Form.Item>
                <Form.Item
                  name="e2bRegion"
                  label="Region"
                  rules={[{ required: modal?.mode === 'create', message: '必填' }]}
                  tooltip="E2B 链路的唯一变量；保存时自动派生 API Base URL 与 Domain"
                  extra={
                    linkType === 'e2b' && e2bRegionWatch
                      ? `→ https://api.${String(e2bRegionWatch).trim()}.e2b.fc.aliyuncs.com`
                      : undefined
                  }
                >
                  <Input placeholder={modal?.mode === 'edit' ? '留空不修改' : '如 cn-beijing'} />
                </Form.Item>
                <Form.Item name="e2bDefaultTemplate" label="默认模板" tooltip="Agent 未配置 templateName 时使用">
                  <Input placeholder="如 code-interpreter-v1" />
                </Form.Item>
              </>
            )}

            {linkType === 'agentrun' && (
              <>
                <Row gutter={16}>
                  <Col xs={24} sm={12}>
                    <Form.Item
                      name="apiKey"
                      label="API Key"
                      rules={[{ required: modal?.mode === 'create', message: '必填' }]}
                      tooltip={modal?.mode === 'edit' ? '留空不修改；AES-GCM 加密入库' : 'AES-GCM 加密入库'}
                    >
                      <Input.Password placeholder={modal?.mode === 'edit' ? '留空不修改' : ''} />
                    </Form.Item>
                  </Col>
                  <Col xs={24} sm={12}>
                    <Form.Item
                      name="accountId"
                      label="阿里云账号 ID"
                      rules={[{ required: modal?.mode === 'create', message: '必填' }]}
                      tooltip={modal?.mode === 'edit' ? '留空不修改；AES-GCM 加密入库' : 'AES-GCM 加密入库'}
                    >
                      <Input.Password placeholder={modal?.mode === 'edit' ? '留空不修改' : ''} />
                    </Form.Item>
                  </Col>
                </Row>
                <Row gutter={16}>
                  <Col xs={24} sm={12}>
                    <Form.Item
                      name="region"
                      label="Region"
                      rules={[{ required: modal?.mode === 'create', message: '必填' }]}
                    >
                      <Input placeholder="cn-hangzhou" />
                    </Form.Item>
                  </Col>
                  <Col xs={24} sm={12}>
                    <Form.Item name="defaultTemplate" label="默认模板" tooltip="Agent 未配置 templateName 时使用">
                      <Input placeholder="如 teapot-ci-2c4g" />
                    </Form.Item>
                  </Col>
                </Row>
                <Form.Item
                  name="mcpServerUrl"
                  label="MCP 服务地址"
                  rules={[{ required: modal?.mode === 'create', message: '必填' }]}
                >
                  <Input placeholder="https://<账号ID>.agentrun-data.<region>.aliyuncs.com/templates/<模板>/mcp" />
                </Form.Item>
              </>
            )}

            <Form.Item name="remark" label="备注">
              <Input placeholder="可选" />
            </Form.Item>
          </Form>
        </Modal>
      </div>
    </Spin>
  );
}
