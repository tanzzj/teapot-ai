import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Col, Row, Spin, theme } from 'antd';
import {
  Button,
  Form,
  Input,
  InputNumber,
  message,
  Select,
  Switch,
} from '@agentscope-ai/design';
import {
  CloudServerOutlined,
  EditOutlined,
  IdcardOutlined,
  ProfileOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import {
  agentBindSkill,
  agentDetail,
  agentUnbindSkill,
  agentUpdate,
  modelPresets,
  sandboxOptions,
  updateSandboxConfig,
} from '../api/agent';
import { skillList } from '../api/skill';
import { useAuthStore } from '../store/auth';
import type { AgentSandboxConfig, SandboxOptions, SkillListItem } from '../types';

type Section = 'profile' | 'basic' | 'sandbox' | 'skills';

/** 装饰性贡献热力图（Barley 视觉复刻，零数据灰格） */
function Heatmap() {
  const weeks = 40;
  const cells = useMemo(() => Array.from({ length: weeks * 7 }, () => 0), []);
  return (
    <div style={{ overflowX: 'auto', paddingTop: 8 }}>
      <div style={{ display: 'grid', gridAutoFlow: 'column', gridTemplateRows: 'repeat(7, 10px)', gap: 3, width: 'max-content' }}>
        {cells.map((_, i) => (
          <span key={i} style={{ width: 10, height: 10, borderRadius: 2, background: 'rgba(0, 0, 0, 0.07)' }} />
        ))}
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 6, alignItems: 'center', marginTop: 8, fontSize: 11, color: 'rgba(26, 26, 29, 0.45)' }}>
        少
        {[0.15, 0.3, 0.5, 0.7, 0.9].map((o) => (
          <span key={o} style={{ width: 10, height: 10, borderRadius: 2, background: `rgba(91, 185, 139, ${o})` }} />
        ))}
        多
      </div>
    </div>
  );
}

/**
 * Agent 详情（Barley 设计语言复刻：左侧胶囊菜单 + 分区内容）。
 * SPEC §12.2：sysPrompt/模型/压缩参数/Skill 绑定。
 */
export default function AgentDetailPage() {
  const { agentKey = '' } = useParams();
  const navigate = useNavigate();
  const { token } = theme.useToken();
  const [form] = Form.useForm();
  const [credForm] = Form.useForm();
  const [section, setSection] = useState<Section>('profile');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [models, setModels] = useState<string[]>([]);
  const [boundSkills, setBoundSkills] = useState<string[]>([]);
  const [allSkills, setAllSkills] = useState<SkillListItem[]>([]);
  const [detail, setDetail] = useState<{ name: string; description?: string; status: number; createdAt?: string } | null>(null);
  const [sbOptions, setSbOptions] = useState<SandboxOptions | null>(null);
  const [credSaving, setCredSaving] = useState(false);
  const isAdmin = useAuthStore((s) => s.hasRole('admin'));
  const sbEnabled = Form.useWatch(['sandbox', 'enabled'], form);
  const sbPersistence = Form.useWatch(['sandbox', 'persistence'], form);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [d, presets, skills, sbOpts] = await Promise.all([
        agentDetail(agentKey),
        modelPresets(),
        skillList(),
        sandboxOptions(),
      ]);
      setSbOptions(sbOpts);
      setDetail({
        name: d.agent.name,
        description: d.agent.description,
        status: d.agent.status,
        createdAt: d.agent.createdAt,
      });
      // feature.sandbox 回显（SPEC §16.11）
      let sb: Partial<AgentSandboxConfig> = {};
      if (d.agent.feature) {
        try {
          const f = JSON.parse(d.agent.feature);
          if (f && typeof f === 'object' && f.sandbox) {
            sb = f.sandbox;
          }
        } catch {
          // 非法 JSON 忽略回显
        }
      }
      form.setFieldsValue({
        name: d.agent.name,
        description: d.agent.description,
        modelId: d.agent.modelId,
        compactionTrigger: d.agent.compactionTrigger,
        compactionKeep: d.agent.compactionKeep,
        sysPrompt: d.agent.sysPrompt,
        sandbox: {
          enabled: !!sb.enabled,
          isolationScope: sb.isolationScope ?? 'SESSION',
          persistence: sb.persistence ?? 'LOCAL_SNAPSHOT',
          templateName: sb.templateName,
          workspaceRoot: sb.workspaceRoot,
          idleTimeoutSeconds: sb.idleTimeoutSeconds,
          nas: sb.nas,
        },
      });
      setBoundSkills(d.skillNames || []);
      setModels(presets || []);
      setAllSkills(skills || []);
    } catch {
      // 已统一提示
    } finally {
      setLoading(false);
    }
  }, [agentKey, form]);

  useEffect(() => {
    load();
  }, [load]);

  const onSave = async () => {
    const values = await form.validateFields();
    const { sandbox, ...rest } = values as Record<string, unknown> & { sandbox?: Partial<AgentSandboxConfig> };
    const payload: Record<string, unknown> = { ...rest };
    if (sandbox) {
      // enabled=false 时仅提交 {enabled:false}，保持 feature 精简（SPEC §16.11）
      payload.feature = { sandbox: sandbox.enabled ? sandbox : { enabled: false } };
    }
    setSaving(true);
    try {
      await agentUpdate(agentKey, payload);
      message.success('保存成功');
      load();
    } finally {
      setSaving(false);
    }
  };

  /** 全局沙箱接入凭证保存（仅 admin，SPEC §16.5.1 修订：e2b·agentrun 双链路，留空不修改） */
  const onSaveCredentials = async (vals: {
    apiKey?: string; accountId?: string; region?: string; mcpServerUrl?: string; defaultTemplate?: string;
    e2bApiKey?: string; e2bApiBaseUrl?: string; e2bDomain?: string; e2bDefaultTemplate?: string;
  }) => {
    setCredSaving(true);
    try {
      const opts = await updateSandboxConfig({
        apiKey: vals.apiKey || '',
        accountId: vals.accountId || '',
        region: vals.region || '',
        mcpServerUrl: vals.mcpServerUrl || '',
        defaultTemplate: vals.defaultTemplate || '',
        e2bApiKey: vals.e2bApiKey || '',
        e2bApiBaseUrl: vals.e2bApiBaseUrl || '',
        e2bDomain: vals.e2bDomain || '',
        e2bDefaultTemplate: vals.e2bDefaultTemplate || '',
      });
      setSbOptions(opts);
      credForm.resetFields();
      message.success('全局接入凭证已保存');
    } finally {
      setCredSaving(false);
    }
  };

  const menuItems: { key: Section; label: string; icon: React.ReactNode }[] = [
    { key: 'profile', label: 'Profile', icon: <IdcardOutlined /> },
    { key: 'basic', label: 'Basic Info', icon: <ProfileOutlined /> },
    { key: 'sandbox', label: 'Sandbox', icon: <CloudServerOutlined /> },
    { key: 'skills', label: 'Skills', icon: <ThunderboltOutlined /> },
  ];

  return (
    <div style={{ padding: '20px 28px' }}>
      {/* 页头：头像 + 名称 + Save */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 20 }}>
        <span
          style={{
            width: 40,
            height: 40,
            borderRadius: 999,
            background: 'linear-gradient(135deg, #2b2b31, #1a1a1d)',
            color: '#fff',
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 17,
            fontWeight: 700,
          }}
        >
          {(detail?.name || agentKey).charAt(0).toUpperCase()}
        </span>
        <div>
          <div style={{ fontWeight: 700, fontSize: 17, color: 'rgba(26, 26, 29, 0.92)' }}>
            {detail?.name || agentKey}
          </div>
          <div style={{ fontFamily: 'Menlo, Consolas, monospace', fontSize: 11, color: 'rgba(26, 26, 29, 0.45)' }}>
            {agentKey}
          </div>
        </div>
        <div style={{ flex: 1 }} />
        {(section === 'basic' || section === 'sandbox') && (
          <Button type="primary" onClick={onSave} loading={saving}>
            Save
          </Button>
        )}
      </div>

      <Spin spinning={loading}>
        <Row gutter={20}>
          {/* 左侧胶囊菜单 */}
          <Col xs={24} md={6} lg={5} style={{ marginBottom: 16 }}>
            <div className="glass-card" style={{ padding: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
              {menuItems.map((m) => {
                const active = section === m.key;
                return (
                  <div
                    key={m.key}
                    onClick={() => setSection(m.key)}
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
            {section === 'profile' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
                <div className="glass-card" style={{ padding: 24, display: 'flex', gap: 24, flexWrap: 'wrap' }}>
                  {/* 大头像卡 */}
                  <div
                    style={{
                      background: '#fff',
                      borderRadius: 14,
                      padding: 12,
                      boxShadow: '0 4px 16px rgba(0, 0, 0, 0.08)',
                      textAlign: 'center',
                    }}
                  >
                    <div
                      style={{
                        width: 140,
                        height: 140,
                        borderRadius: 10,
                        background: '#141416',
                        color: '#fff',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: 56,
                        fontWeight: 700,
                      }}
                    >
                      {(detail?.name || agentKey).charAt(0).toUpperCase()}
                    </div>
                    <div style={{ fontFamily: 'Menlo, Consolas, monospace', fontSize: 11, color: 'rgba(26, 26, 29, 0.55)', marginTop: 10 }}>
                      ID: {agentKey}
                    </div>
                  </div>

                  {/* 基本信息 */}
                  <div style={{ flex: 1, minWidth: 240 }}>
                    <div style={{ fontSize: 24, fontWeight: 700, color: 'rgba(26, 26, 29, 0.92)' }}>
                      {agentKey}
                    </div>
                    <div style={{ display: 'flex', gap: 12, alignItems: 'center', margin: '10px 0', fontSize: 13, color: 'rgba(26, 26, 29, 0.55)' }}>
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                        <span style={{ width: 8, height: 8, borderRadius: 999, background: detail?.status === 1 ? '#5bb98b' : '#bbb' }} />
                        {detail?.status === 1 ? '在线' : '停用'}
                      </span>
                      <span style={{ color: 'rgba(26, 26, 29, 0.25)' }}>|</span>
                      <span>创建时间：{detail?.createdAt ? String(detail.createdAt).slice(0, 10) : '—'}</span>
                    </div>
                    <div style={{ color: 'rgba(26, 26, 29, 0.6)', fontSize: 13.5, marginBottom: 16 }}>
                      {detail?.description || 'No persona configured.'}
                    </div>
                    <Button icon={<EditOutlined />} onClick={() => setSection('basic')}>
                      编辑
                    </Button>
                  </div>
                </div>

                {/* 工作记录 */}
                <div className="glass-card" style={{ padding: 24 }}>
                  <div style={{ fontSize: 17, fontWeight: 700, color: 'rgba(26, 26, 29, 0.92)', marginBottom: 20 }}>
                    工作记录
                  </div>
                  <Row gutter={16} style={{ marginBottom: 20 }}>
                    {[
                      { n: boundSkills.length, label: '绑定技能' },
                      { n: models.length, label: '可用模型' },
                      { n: detail?.status === 1 ? 1 : 0, label: '运行状态' },
                      { n: 0, label: '自动任务' },
                    ].map((s) => (
                      <Col xs={12} sm={6} key={s.label} style={{ textAlign: 'center', marginBottom: 12 }}>
                        <div style={{ fontSize: 22, fontWeight: 700, color: 'rgba(26, 26, 29, 0.92)' }}>{s.n}</div>
                        <div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.5)', marginTop: 4 }}>{s.label}</div>
                      </Col>
                    ))}
                  </Row>
                  <Heatmap />
                </div>
              </div>
            )}

            {section === 'basic' && (
              <div className="glass-card" style={{ padding: 24 }}>
                <Form form={form} layout="vertical">
                  <Form.Item label="Agent ID">
                    <Input value={agentKey} disabled />
                  </Form.Item>
                  <Form.Item name="name" label="Name" rules={[{ required: true }]}>
                    <Input placeholder="Agent 显示名称" />
                  </Form.Item>
                  <Form.Item name="description" label="Description">
                    <Input.TextArea rows={2} />
                  </Form.Item>
                  <Form.Item name="sysPrompt" label="Persona (System Prompt)">
                    <Input.TextArea rows={10} style={{ fontFamily: 'Menlo, Consolas, monospace' }} placeholder="You are a helpful assistant..." />
                  </Form.Item>
                  <Form.Item name="modelId" label="Model" rules={[{ required: true }]}>
                    <Select options={models.map((m) => ({ label: m, value: m }))} placeholder="e.g. qwen-max" />
                  </Form.Item>
                  <Row gutter={16}>
                    <Col xs={24} sm={12}>
                      <Form.Item name="compactionTrigger" label="压缩触发轮数" tooltip="会话历史超过该轮数时触发记忆压缩">
                        <InputNumber min={1} max={200} style={{ width: '100%' }} />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={12}>
                      <Form.Item name="compactionKeep" label="压缩保留轮数" tooltip="压缩后保留的最近轮数">
                        <InputNumber min={0} max={100} style={{ width: '100%' }} />
                      </Form.Item>
                    </Col>
                  </Row>
                </Form>
              </div>
            )}

            {section === 'sandbox' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                {sbOptions && !sbOptions.configured && (
                  <Alert
                    type="warning"
                    showIcon
                    message="AgentRun 未接入"
                    description="请联系管理员在下方「全局接入凭证」配置 API Key / 账号 ID / MCP 地址后再启用沙箱。"
                  />
                )}
                <div className="glass-card" style={{ padding: 24 }}>
                  <Form form={form} layout="vertical">
                    <Form.Item
                      name={['sandbox', 'enabled']}
                      label="启用沙箱"
                      valuePropName="checked"
                      tooltip="启用后 Agent 获得 shell_execute 与文件读写能力，全部在阿里云隔离容器内执行"
                    >
                      <Switch disabled={!(sbOptions?.configured ?? false)} />
                    </Form.Item>
                    {sbEnabled && (
                      <>
                        <Row gutter={16}>
                          <Col xs={24} sm={12}>
                            <Form.Item name={['sandbox', 'isolationScope']} label="隔离维度" tooltip="SESSION=每会话独立沙箱（推荐）">
                              <Select
                                options={[
                                  { value: 'SESSION', label: 'SESSION（每会话独立）' },
                                  { value: 'USER', label: 'USER（每用户）' },
                                  { value: 'AGENT', label: 'AGENT（每 Agent）' },
                                  { value: 'GLOBAL', label: 'GLOBAL（全局）' },
                                ]}
                              />
                            </Form.Item>
                          </Col>
                          <Col xs={24} sm={12}>
                            <Form.Item name={['sandbox', 'persistence']} label="持久化">
                              <Select
                                options={[
                                  { value: 'LOCAL_SNAPSHOT', label: '本地快照（推荐）' },
                                  { value: 'NONE', label: '不持久化' },
                                  { value: 'NAS', label: 'NAS 挂载' },
                                ]}
                              />
                            </Form.Item>
                          </Col>
                        </Row>
                        <Row gutter={16}>
                          <Col xs={24} sm={12}>
                            <Form.Item name={['sandbox', 'templateName']} label="沙箱模板" tooltip="留空用全局默认模板">
                              <Input placeholder={sbOptions?.defaultTemplate || '全局默认模板'} />
                            </Form.Item>
                          </Col>
                          <Col xs={24} sm={12}>
                            <Form.Item name={['sandbox', 'idleTimeoutSeconds']} label="闲置超时（秒）" tooltip="300–21600，超时自动回收">
                              <InputNumber
                                min={300}
                                max={21600}
                                style={{ width: '100%' }}
                                placeholder={String(sbOptions?.defaultIdleTimeoutSeconds ?? 1800)}
                              />
                            </Form.Item>
                          </Col>
                        </Row>
                        <Form.Item name={['sandbox', 'workspaceRoot']} label="沙箱工作区根" tooltip="绝对路径；留空用全局默认">
                          <Input placeholder={sbOptions?.defaultWorkspaceRoot || '/home/agentscope/workspace'} />
                        </Form.Item>
                        {sbPersistence === 'NAS' && (
                          <div style={{ border: '1px dashed rgba(0,0,0,0.15)', borderRadius: 10, padding: 16 }}>
                            <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 12 }}>NAS 挂载配置</div>
                            <Row gutter={16}>
                              <Col xs={24} sm={12}>
                                <Form.Item name={['sandbox', 'nas', 'serverAddr']} label="serverAddr" rules={[{ required: true, message: '必填' }]}>
                                  <Input placeholder="xxxx.cn-hangzhou.nas.aliyuncs.com" />
                                </Form.Item>
                              </Col>
                              <Col xs={24} sm={12}>
                                <Form.Item name={['sandbox', 'nas', 'mountDir']} label="mountDir" rules={[{ required: true, message: '必填' }, { pattern: /^\/(home|mnt|data)\//, message: '须以 /home/、/mnt/ 或 /data/ 开头' }]}>
                                  <Input placeholder="/mnt/nas" />
                                </Form.Item>
                              </Col>
                            </Row>
                            <Row gutter={16}>
                              <Col xs={24} sm={12}>
                                <Form.Item name={['sandbox', 'nas', 'remotePath']} label="remotePath">
                                  <Input placeholder="/" />
                                </Form.Item>
                              </Col>
                              <Col xs={24} sm={12}>
                                <Form.Item name={['sandbox', 'nas', 'enableTLS']} label="启用 TLS" valuePropName="checked">
                                  <Switch />
                                </Form.Item>
                              </Col>
                            </Row>
                          </div>
                        )}
                      </>
                    )}
                  </Form>
                </div>

                {isAdmin && (
                  <div className="glass-card" style={{ padding: 24 }}>
                    <div style={{ fontSize: 15, fontWeight: 700, marginBottom: 4 }}>全局接入凭证（admin）</div>
                    <div style={{ fontSize: 12, color: 'rgba(26,26,29,0.5)', marginBottom: 16 }}>
                      <div>
                        接入状态：{sbOptions?.configured ? '已接入' : '未接入'}
                        {sbOptions?.link ? ` · 首选链路 ${sbOptions.link === 'agentrun' ? 'AgentRun MCP' : 'E2B 兼容'}` : ''}
                      </div>
                      <div>
                        E2B 兼容：{sbOptions?.e2bConfigured ? '已接入' : '未接入'}
                        {sbOptions?.e2bApiKeyMasked ? ` · Key ${sbOptions.e2bApiKeyMasked}` : ''}
                        {sbOptions?.e2bDomain ? ` · ${sbOptions.e2bDomain}` : ''}
                      </div>
                      <div>
                        AgentRun MCP：{sbOptions?.apiKeyMasked && sbOptions?.accountIdMasked && sbOptions?.mcpServerUrl ? '已接入' : '未接入'}
                        {sbOptions?.apiKeyMasked ? ` · Key ${sbOptions.apiKeyMasked}` : ''}
                        {sbOptions?.accountIdMasked ? ` · 账号 ${sbOptions.accountIdMasked}` : ''}
                      </div>
                    </div>
                    <Form form={credForm} layout="vertical">
                      <div style={{ fontWeight: 600, fontSize: 13, margin: '4px 0 10px' }}>E2B 兼容链路</div>
                      <Row gutter={16}>
                        <Col xs={24} sm={12}>
                          <Form.Item name="e2bApiKey" label="E2B API Key" tooltip="留空不修改；AES-GCM 加密入库">
                            <Input.Password placeholder="留空不修改" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} sm={12}>
                          <Form.Item name="e2bApiBaseUrl" label="API Base URL">
                            <Input placeholder="https://api.cn-beijing.e2b.fc.aliyuncs.com" />
                          </Form.Item>
                        </Col>
                      </Row>
                      <Row gutter={16}>
                        <Col xs={24} sm={12}>
                          <Form.Item name="e2bDomain" label="Domain">
                            <Input placeholder="cn-beijing.e2b.fc.aliyuncs.com" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} sm={12}>
                          <Form.Item name="e2bDefaultTemplate" label="默认模板">
                            <Input placeholder="如 code-interpreter-v1" />
                          </Form.Item>
                        </Col>
                      </Row>
                      <div style={{ fontWeight: 600, fontSize: 13, margin: '12px 0 10px' }}>AgentRun MCP 链路</div>
                      <Row gutter={16}>
                        <Col xs={24} sm={12}>
                          <Form.Item name="apiKey" label="API Key" tooltip="留空不修改；AES-GCM 加密入库">
                            <Input.Password placeholder="留空不修改" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} sm={12}>
                          <Form.Item name="accountId" label="阿里云账号 ID" tooltip="留空不修改">
                            <Input.Password placeholder="留空不修改" />
                          </Form.Item>
                        </Col>
                      </Row>
                      <Row gutter={16}>
                        <Col xs={24} sm={8}>
                          <Form.Item name="region" label="region">
                            <Input placeholder="cn-hangzhou" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} sm={8}>
                          <Form.Item name="defaultTemplate" label="默认模板">
                            <Input placeholder="如 teapot-ci-2c4g" />
                          </Form.Item>
                        </Col>
                      </Row>
                      <Form.Item name="mcpServerUrl" label="MCP 服务地址">
                        <Input placeholder="https://<账号ID>.agentrun-data.<region>.aliyuncs.com/templates/<模板>/mcp" />
                      </Form.Item>
                      <Button type="primary" loading={credSaving} onClick={() => credForm.validateFields().then(onSaveCredentials)}>
                        保存凭证
                      </Button>
                    </Form>
                  </div>
                )}
              </div>
            )}

            {section === 'skills' && (
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
                  <div style={{ fontSize: 20, fontWeight: 700, color: 'rgba(26, 26, 29, 0.92)' }}>Skills</div>
                  <span style={{ color: 'rgba(26, 26, 29, 0.45)', fontSize: 13 }}>管理与配置技能集成</span>
                  <div style={{ flex: 1 }} />
                  <Button type="primary" icon={<ThunderboltOutlined />} onClick={() => navigate('/skills')}>
                    Create Skill
                  </Button>
                </div>

                {allSkills.length === 0 ? (
                  <div className="glass-card" style={{ padding: 32, textAlign: 'center', color: token.colorTextTertiary, fontSize: 13 }}>
                    暂无可用 Skill，去 Skill 工坊创建
                  </div>
                ) : (
                  <Row gutter={[16, 16]}>
                    {allSkills.map((s) => {
                      const bound = boundSkills.includes(s.name);
                      return (
                        <Col xs={24} lg={12} key={s.name}>
                          <div className="glass-card" style={{ padding: 18 }}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                              <span
                                style={{
                                  width: 36,
                                  height: 36,
                                  borderRadius: 999,
                                  background: '#141416',
                                  color: '#fff',
                                  display: 'inline-flex',
                                  alignItems: 'center',
                                  justifyContent: 'center',
                                  fontWeight: 700,
                                  flexShrink: 0,
                                }}
                              >
                                {s.name.charAt(0).toUpperCase()}
                              </span>
                              <div style={{ minWidth: 0, flex: 1 }}>
                                <div style={{ fontWeight: 600, fontSize: 14, color: 'rgba(26, 26, 29, 0.92)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                  {s.name}
                                  {s.source && (
                                    <span style={{ fontWeight: 400, fontSize: 11, color: 'rgba(26, 26, 29, 0.4)', marginLeft: 8 }}>
                                      {s.source}
                                    </span>
                                  )}
                                </div>
                                <div style={{ fontSize: 11, marginTop: 2, color: bound ? '#5bb98b' : 'rgba(26, 26, 29, 0.35)', display: 'flex', alignItems: 'center', gap: 5 }}>
                                  <span style={{ width: 6, height: 6, borderRadius: 999, background: bound ? '#5bb98b' : '#ccc' }} />
                                  {bound ? 'Enabled' : 'Disabled'}
                                </div>
                              </div>
                              <Switch
                                checked={bound}
                                onChange={async (v) => {
                                  if (v) {
                                    await agentBindSkill(agentKey, s.name);
                                    message.success(`已绑定 ${s.name}`);
                                    setBoundSkills((prev) => [...prev, s.name]);
                                  } else {
                                    await agentUnbindSkill(agentKey, s.name);
                                    message.success(`已解绑 ${s.name}`);
                                    setBoundSkills((prev) => prev.filter((n) => n !== s.name));
                                  }
                                }}
                              />
                            </div>
                            <div
                              style={{
                                marginTop: 10,
                                fontSize: 12.5,
                                color: 'rgba(26, 26, 29, 0.6)',
                                display: '-webkit-box',
                                WebkitLineClamp: 2,
                                WebkitBoxOrient: 'vertical',
                                overflow: 'hidden',
                              }}
                            >
                              {s.description || '（无描述）'}
                            </div>
                          </div>
                        </Col>
                      );
                    })}
                  </Row>
                )}
              </div>
            )}
          </Col>
        </Row>
      </Spin>
    </div>
  );
}
