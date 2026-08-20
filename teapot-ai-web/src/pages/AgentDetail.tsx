import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
  CameraOutlined,
  CloudServerOutlined,
  EditOutlined,
  IdcardOutlined,
  ProfileOutlined,
  SettingOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import {
  agentBindSkill,
  agentDetail,
  agentUnbindSkill,
  agentUpdate,
  modelPresets,
} from '../api/agent';
import { sandboxOptions, sandboxRecordNames, storageRecordNames } from '../api/config';
import { uploadAgentAvatar } from '../api/avatar';
import { skillList } from '../api/skill';
import { sessionStats } from '../api/session';
import { useAuthStore } from '../store/auth';
import type {
  AgentSandboxConfig,
  SandboxOptions,
  SandboxRecordName,
  SkillListItem,
  StorageRecordName,
} from '../types';

type Section = 'profile' | 'basic' | 'sandbox' | 'skills';

/** 贡献热力图：按 session by date 渲染该 Agent 近 280 天会话量（列=周，周一对齐） */
function Heatmap({ agentKey }: { agentKey: string }) {
  const [counts, setCounts] = useState<Record<string, number>>({});

  useEffect(() => {
    let alive = true;
    sessionStats(agentKey)
      .then((rows) => {
        if (!alive) return;
        const m: Record<string, number> = {};
        rows.forEach((r) => { m[r.date] = r.count; });
        setCounts(m);
      })
      .catch(() => undefined);
    return () => { alive = false; };
  }, [agentKey]);

  const cells = useMemo(() => {
    const today = new Date();
    const start = new Date();
    start.setDate(today.getDate() - 279);
    start.setDate(start.getDate() - ((start.getDay() + 6) % 7)); // 回退到周一
    const out: { date: string; count: number }[] = [];
    for (const d = new Date(start); d <= today; d.setDate(d.getDate() + 1)) {
      const iso = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
      out.push({ date: iso, count: counts[iso] ?? 0 });
    }
    return out;
  }, [counts]);

  const color = (n: number) =>
    n <= 0 ? 'rgba(0, 0, 0, 0.07)'
      : n === 1 ? 'rgba(91, 185, 139, 0.3)'
        : n === 2 ? 'rgba(91, 185, 139, 0.5)'
          : n <= 4 ? 'rgba(91, 185, 139, 0.7)'
            : 'rgba(91, 185, 139, 0.9)';

  return (
    <div style={{ overflowX: 'auto', paddingTop: 8 }}>
      <div style={{ display: 'grid', gridAutoFlow: 'column', gridTemplateRows: 'repeat(7, 10px)', gap: 3, width: 'max-content' }}>
        {cells.map((c) => (
          <span
            key={c.date}
            title={`${c.date} · ${c.count} 个会话`}
            style={{ width: 10, height: 10, borderRadius: 2, background: color(c.count) }}
          />
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
  const [section, setSection] = useState<Section>('profile');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [models, setModels] = useState<string[]>([]);
  const [boundSkills, setBoundSkills] = useState<string[]>([]);
  const [allSkills, setAllSkills] = useState<SkillListItem[]>([]);
  const [detail, setDetail] = useState<{ name: string; description?: string; status: number; createdAt?: string; avatar?: string } | null>(null);
  const [sbOptions, setSbOptions] = useState<SandboxOptions | null>(null);
  const [storageNames, setStorageNames] = useState<StorageRecordName[]>([]);
  const [sandboxNames, setSandboxNames] = useState<SandboxRecordName[]>([]);
  /** 已加载的原始 feature 命名空间：分区保存时合并，避免单分区覆盖另一分区（§22） */
  const [loadedFeature, setLoadedFeature] = useState<Record<string, unknown>>({});
  const isAdmin = useAuthStore((s) => s.hasRole('admin'));
  const sbEnabled = Form.useWatch(['sandbox', 'enabled'], form);
  const sbPersistence = Form.useWatch(['sandbox', 'persistence'], form);
  const [avatarUploading, setAvatarUploading] = useState(false);
  const avatarInputRef = useRef<HTMLInputElement>(null);

  /** Agent 头像上传（SPEC §23）：客户端预检 2MB/图片类型，服务端复检后落 OSS + t_agent.avatar */
  const onAvatarFile = useCallback(
    async (file: File | undefined) => {
      if (!file || avatarUploading) {
        return;
      }
      if (!file.type.startsWith('image/')) {
        message.error('仅支持图片格式头像');
        return;
      }
      if (file.size > 2 * 1024 * 1024) {
        message.error('头像超过 2MB 限制');
        return;
      }
      setAvatarUploading(true);
      try {
        const { url } = await uploadAgentAvatar(agentKey, file);
        setDetail((d) => (d ? { ...d, avatar: url } : d));
        message.success('头像已更新');
      } catch (e) {
        message.error(e instanceof Error ? e.message : '头像上传失败');
      } finally {
        setAvatarUploading(false);
        if (avatarInputRef.current) {
          avatarInputRef.current.value = '';
        }
      }
    },
    [agentKey, avatarUploading],
  );

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [d, presets, skills, sbOpts, ossNames, sbRecordNames] = await Promise.all([
        agentDetail(agentKey),
        modelPresets(),
        skillList(),
        sandboxOptions(),
        storageRecordNames().catch(() => [] as StorageRecordName[]),
        sandboxRecordNames().catch(() => [] as SandboxRecordName[]),
      ]);
      setSbOptions(sbOpts);
      setStorageNames(ossNames);
      setSandboxNames(sbRecordNames);
      setDetail({
        name: d.agent.name,
        description: d.agent.description,
        status: d.agent.status,
        createdAt: d.agent.createdAt,
        avatar: d.agent.avatar,
      });
      // feature.sandbox / feature.storage 回显（SPEC §16.11/§22）
      let sb: Partial<AgentSandboxConfig> = {};
      let storageTarget = 'base64';
      let parsedFeature: Record<string, unknown> = {};
      if (d.agent.feature) {
        try {
          const f = JSON.parse(d.agent.feature);
          if (f && typeof f === 'object') {
            parsedFeature = f;
            if (f.sandbox) {
              sb = f.sandbox;
            }
            if (f.storage && f.storage.mode === 'oss' && f.storage.storageRecord) {
              storageTarget = f.storage.storageRecord;
            }
          }
        } catch {
          // 非法 JSON 忽略回显
        }
      }
      setLoadedFeature(parsedFeature);
      form.setFieldsValue({
        name: d.agent.name,
        description: d.agent.description,
        modelId: d.agent.modelId,
        compactionTrigger: d.agent.compactionTrigger,
        compactionKeep: d.agent.compactionKeep,
        sysPrompt: d.agent.sysPrompt,
        storageTarget,
        sandbox: {
          enabled: !!sb.enabled,
          sandboxRecord: sb.sandboxRecord,
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
    const { sandbox, storageTarget, ...rest } = values as Record<string, unknown> & {
      sandbox?: Partial<AgentSandboxConfig>;
      storageTarget?: string;
    };
    const payload: Record<string, unknown> = { ...rest };
    // feature：在已加载命名空间基础上合并本次分区提交，避免单分区覆盖另一分区（§22）
    const feature: Record<string, unknown> = { ...loadedFeature };
    if (storageTarget !== undefined) {
      feature.storage = storageTarget === 'base64'
        ? { mode: 'base64' }
        : { mode: 'oss', storageRecord: storageTarget };
    }
    if (sandbox !== undefined) {
      // enabled=false 时仅提交 {enabled:false}，保持 feature 精简（SPEC §16.11）
      feature.sandbox = sandbox.enabled ? sandbox : { enabled: false };
    }
    if (Object.keys(feature).length > 0) {
      payload.feature = feature;
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
        {detail?.avatar ? (
          <img
            src={detail.avatar}
            alt={detail.name || agentKey}
            style={{ width: 40, height: 40, borderRadius: 999, objectFit: 'cover', flexShrink: 0 }}
          />
        ) : (
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
        )}
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
                  {/* 大头像卡（SPEC §23：点击换头像） */}
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
                      onClick={() => avatarInputRef.current?.click()}
                      title="点击更换头像"
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
                        cursor: 'pointer',
                        overflow: 'hidden',
                        position: 'relative',
                      }}
                    >
                      {detail?.avatar ? (
                        <img src={detail.avatar} alt={detail.name || agentKey} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                      ) : (
                        (detail?.name || agentKey).charAt(0).toUpperCase()
                      )}
                      <span
                        style={{
                          position: 'absolute',
                          inset: 'auto 0 0 0',
                          background: 'rgba(0, 0, 0, 0.55)',
                          fontSize: 11,
                          fontWeight: 400,
                          padding: '4px 0',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          gap: 4,
                        }}
                      >
                        <CameraOutlined /> {avatarUploading ? '上传中…' : '更换头像'}
                      </span>
                    </div>
                    {/* 头像选择器（SPEC §23） */}
                    <input
                      ref={avatarInputRef}
                      type="file"
                      accept="image/jpeg,image/png,image/webp,image/gif"
                      style={{ display: 'none' }}
                      onChange={(e) => onAvatarFile(e.target.files?.[0])}
                    />
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
                  <Heatmap agentKey={agentKey} />
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
                  <Form.Item
                    name="storageTarget"
                    label="图片存储载体"
                    tooltip="对话台图片附件的存储方式（§22.1）；OSS 记录在系统配置 - 存储中维护"
                  >
                    <Select
                      options={[
                        { value: 'base64', label: 'Base64 内联（默认，图片随消息体传输）' },
                        ...storageNames.map((r) => ({
                          value: r.name,
                          label: `OSS 记录：${r.name}（${r.region ?? ''} / ${r.bucket ?? ''}）`,
                        })),
                      ]}
                      placeholder="Base64 内联（默认）"
                    />
                  </Form.Item>
                  {storageNames.length === 0 && (
                    <Alert
                      type="info"
                      showIcon
                      style={{ marginBottom: 16 }}
                      message="尚无 OSS 连接记录，当前仅可使用 Base64 内联；如需 OSS，请在系统配置 - 存储中新建记录。"
                    />
                  )}
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
                {sandboxNames.length === 0 && (
                  <Alert
                    type="warning"
                    showIcon
                    message="沙箱未接入"
                    description="请联系管理员在「系统配置 - 沙箱」中新建沙箱连接记录后再启用沙箱。"
                  />
                )}
                <div className="glass-card" style={{ padding: 24 }}>
                  <Form form={form} layout="vertical">
                    <Form.Item
                      name={['sandbox', 'enabled']}
                      label="启用沙箱"
                      valuePropName="checked"
                      tooltip="启用后 Agent 获得 shell_execute 与文件读写能力，全部在隔离容器内执行"
                    >
                      <Switch disabled={sandboxNames.length === 0} />
                    </Form.Item>
                    {sbEnabled && (
                      <>
                        <Row gutter={16}>
                          <Col xs={24} sm={12}>
                            <Form.Item
                              name={['sandbox', 'sandboxRecord']}
                              label="沙箱承载记录"
                              tooltip="启用沙箱必选其一（§22.2）；链路由记录的 linkType 决定，记录在系统配置 - 沙箱中维护"
                              rules={[{ required: true, message: '启用沙箱必须选择一条沙箱记录' }]}
                            >
                              <Select
                                placeholder="选择沙箱连接记录"
                                options={sandboxNames.map((r) => ({
                                  value: r.name,
                                  label: `${r.name}（${r.linkType === 'e2b' ? 'E2B 兼容' : 'AgentRun MCP'}）`,
                                }))}
                              />
                            </Form.Item>
                          </Col>
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
                        </Row>
                        <Row gutter={16}>
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
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
                      <div style={{ fontSize: 15, fontWeight: 700 }}>沙箱连接记录</div>
                      <div style={{ flex: 1 }} />
                      {/* 记录统一在系统配置管理（SPEC §22.2） */}
                      <Button icon={<SettingOutlined />} onClick={() => navigate('/system/sandbox')}>
                        前往系统配置
                      </Button>
                    </div>
                    <div style={{ fontSize: 12, color: 'rgba(26,26,29,0.5)', marginTop: 12 }}>
                      当前共 {sandboxNames.length} 条沙箱连接记录；启用沙箱时必选其一作为承载，
                      链路与凭证由记录决定。
                    </div>
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
