import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Col, Row, Spin, theme, Tooltip } from 'antd';
import {
  Button,
  Form,
  IconButton,
  Input,
  InputNumber,
  message,
  Radio,
  Select,
  Switch,
} from '@agentscope-ai/design';
import {
  SparkCameraLine,
  SparkInternetLine,
  SparkEditLine,
  SparkHistoryLine,
  SparkIdLine,
  SparkLinkLine,
  SparkDocumentLine,
  SparkMenuExpandLine,
  SparkMenuFoldLine,
  SparkSettingLine,
  SparkMagicWandLine,
  SparkMemoryLine,
  SparkMultiAgentLine,
} from '@agentscope-ai/icons';
import { useNavigate, useParams } from 'react-router-dom';
import {
  agentBindSkill,
  agentDetail,
  agentUnbindSkill,
  agentUpdate,
  modelPresets,
} from '../api/agent';
import { sandboxOptions, sandboxRecordNames, storageRecordNames } from '../api/config';
import { channelRegistry } from '../api/channelConfig';
import HistoryChatPanel from '../chat/HistoryChatPanel';
import { uploadAgentAvatar } from '../api/avatar';
import { skillList } from '../api/skill';
import { sessionStats } from '../api/session';
import { useAuthStore } from '../store/auth';
import { useIsPhone } from '../hooks/useIsPhone';
import { PHONE_BP } from '../theme/breakpoints';
import type {
  AgentChannelConfig,
  AgentMemoryConfig,
  AgentMultiAgentConfig,
  AgentRuntimeConfig,
  AgentSandboxConfig,
  ChannelRecordName,
  SandboxOptions,
  SandboxRecordName,
  SkillListItem,
  StorageRecordName,
} from '../types';

type Section = 'profile' | 'basic' | 'tools' | 'multiagent' | 'memory' | 'sandbox' | 'channel' | 'skills' | 'history';

/** 胶囊菜单展开宽度（移动端悬浮展开层同宽） */
const MENU_W = 208;
/** 桌面端收起态纯图标条宽度（会话历史分区让位用） */
const MENU_RAIL_W = 72;
/** 移动端收起态纯图标条宽度（扣除 gutter 后卡面约 44px，图标不被裁） */
const PHONE_RAIL_W = 64;
/** 移动端右侧配置区最小宽度：视口再窄也不再压缩，超出部分横向滚动 */
const PHONE_CONTENT_MIN_W = 248;

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
  /** 手机段（<768）：菜单默认收起为图标条，展开时悬浮覆盖，不挤压配置区 */
  const isPhone = useIsPhone();
  /** 胶囊菜单收起为纯图标（手动按钮切换；移动端切分区、桌面端会话历史分区自动收起） */
  const [menuNarrow, setMenuNarrow] = useState(() => window.innerWidth < PHONE_BP);
  const menuCollapsed = menuNarrow;
  // 会话历史分区横向/纵向空间让给会话列表，页头与菜单自动压缩
  const isHistory = section === 'history';
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [models, setModels] = useState<string[]>([]);
  const [boundSkills, setBoundSkills] = useState<string[]>([]);
  const [allSkills, setAllSkills] = useState<SkillListItem[]>([]);
  const [detail, setDetail] = useState<{ name: string; description?: string; status: number; createdAt?: string; avatar?: string } | null>(null);
  const [sbOptions, setSbOptions] = useState<SandboxOptions | null>(null);
  const [storageNames, setStorageNames] = useState<StorageRecordName[]>([]);
  const [sandboxNames, setSandboxNames] = useState<SandboxRecordName[]>([]);
  const [channelNames, setChannelNames] = useState<ChannelRecordName[]>([]);
  /** 已加载的原始 feature 命名空间：分区保存时合并，避免单分区覆盖另一分区（§22） */
  const [loadedFeature, setLoadedFeature] = useState<Record<string, unknown>>({});
  const isAdmin = useAuthStore((s) => s.hasRole('admin'));
  const sbEnabled = Form.useWatch(['sandbox', 'enabled'], form);
  const sbPersistence = Form.useWatch(['sandbox', 'persistence'], form);
  const chEnabled = Form.useWatch(['channel', 'enabled'], form);
  /** 上下文压缩显式开关（映射 compactionTrigger：关 = -1，SPEC §25） */
  const compactionEnabled = Form.useWatch('compactionEnabled', form);
  /** 记忆落盘策略（throttled 时展示节流间隔，SPEC §25） */
  const memFlushTrigger = Form.useWatch(['memory', 'flushTrigger'], form);
  const [avatarUploading, setAvatarUploading] = useState(false);
  const avatarInputRef = useRef<HTMLInputElement>(null);

  /** 用户手动切换过菜单宽窄：桌面端之后不再被分区切换自动覆盖（移动端仍随分区收起） */
  const menuTouched = useRef(false);

  useEffect(() => {
    // 移动端：进页/切分区一律收起（把宽度让给配置区）；
    // 桌面端：仅会话历史分区自动收起，手动切换过后以手动为准
    if (isPhone) {
      setMenuNarrow(true);
      return;
    }
    if (!menuTouched.current) {
      setMenuNarrow(section === 'history');
    }
  }, [isPhone, section]);

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
      const [d, presets, skills, sbOpts, ossNames, sbRecordNames, chNames] = await Promise.all([
        agentDetail(agentKey),
        modelPresets(),
        skillList(),
        sandboxOptions(),
        storageRecordNames().catch(() => [] as StorageRecordName[]),
        sandboxRecordNames().catch(() => [] as SandboxRecordName[]),
        channelRegistry().catch(() => [] as ChannelRecordName[]),
      ]);
      setSbOptions(sbOpts);
      setStorageNames(ossNames);
      setSandboxNames(sbRecordNames);
      setChannelNames(chNames);
      setDetail({
        name: d.agent.name,
        description: d.agent.description,
        status: d.agent.status,
        createdAt: d.agent.createdAt,
        avatar: d.agent.avatar,
      });
      // feature.sandbox / feature.storage / feature.channel / feature.runtime 回显（SPEC §16.11/§22/§24.5）
      let sb: Partial<AgentSandboxConfig> = {};
      let ch: Partial<AgentChannelConfig> = {};
      let rt: Partial<AgentRuntimeConfig> = {};
      let ma: Partial<AgentMultiAgentConfig> = {};
      let mem: Partial<AgentMemoryConfig> = {};
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
            if (f.channel) {
              ch = f.channel;
            }
            if (f.runtime) {
              rt = f.runtime;
            }
            if (f.multiagent) {
              ma = f.multiagent;
            }
            if (f.memory) {
              mem = f.memory;
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
        // 压缩开关回显：列值为负数/0 = 关；空值 = 跟随 SDK 默认（启用）
        compactionEnabled: !(d.agent.compactionTrigger != null && d.agent.compactionTrigger <= 0),
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
        channel: {
          enabled: !!ch.enabled,
          channelRecord: ch.channelRecord,
          dmScope: ch.dmScope ?? 'PER_CHANNEL_PEER',
        },
        runtime: {
          thinkingMode: !!rt.thinkingMode,
          temperature: rt.temperature,
          topP: rt.topP,
          maxTokens: rt.maxTokens,
          enablePlanMode: !!rt.enablePlanMode,
          // 存量未配置时跟随沙箱启用回显，与后端回落语义一致
          enableShell: rt.enableShell ?? !!sb.enabled,
          allowedTools: rt.allowedTools,
          maxIterations: rt.maxIterations,
        },
        // 缺省命名空间 = 启用（与后端 MVP 语义一致，SPEC §25）
        multiagent: {
          enabled: ma.enabled !== false,
        },
        memory: {
          enabled: mem.enabled !== false,
          flushTrigger: mem.flushTrigger ?? 'always',
          flushThrottleMinutes: mem.flushThrottleMinutes ?? 10,
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
    const { sandbox, storageTarget, channel, runtime, multiagent, memory, compactionEnabled, ...rest } = values as Record<string, unknown> & {
      sandbox?: Partial<AgentSandboxConfig>;
      storageTarget?: string;
      channel?: Partial<AgentChannelConfig>;
      runtime?: Partial<AgentRuntimeConfig>;
      multiagent?: Partial<AgentMultiAgentConfig>;
      memory?: Partial<AgentMemoryConfig>;
      compactionEnabled?: boolean;
    };
    const payload: Record<string, unknown> = { ...rest };
    // 压缩开关 ↔ 列值映射：关 = -1/-1；开且未填有效值时回落默认 30/10（SPEC §25）
    if (compactionEnabled !== undefined) {
      if (!compactionEnabled) {
        payload.compactionTrigger = -1;
        payload.compactionKeep = -1;
      } else if (payload.compactionTrigger == null || (payload.compactionTrigger as number) <= 0) {
        payload.compactionTrigger = 30;
        payload.compactionKeep = payload.compactionKeep == null || (payload.compactionKeep as number) <= 0
          ? 10
          : payload.compactionKeep;
      }
    }
    // feature：在已加载命名空间基础上合并本次分区提交，避免单分区覆盖另一分区（§22）
    const feature: Record<string, unknown> = { ...loadedFeature };
    if (storageTarget !== undefined) {
      feature.storage = storageTarget === 'base64'
        ? { mode: 'base64' }
        : { mode: 'oss', storageRecord: storageTarget };
    }
    if (sandbox !== undefined) {
      // enabled=false 时仅提交 {enabled:false}，保持 feature 精简（SPEC §16.11）
      // 记录名 trim：列表下拉历史数据可能带尾随空格，直接引用会被后端记录校验拒绝（§25）
      const sbOut = { ...sandbox };
      if (typeof sbOut.sandboxRecord === 'string') {
        sbOut.sandboxRecord = sbOut.sandboxRecord.trim();
      }
      feature.sandbox = sbOut.enabled ? sbOut : { enabled: false };
    }
    if (channel !== undefined) {
      const chOut = { ...channel };
      if (typeof chOut.channelRecord === 'string') {
        chOut.channelRecord = chOut.channelRecord.trim();
      }
      feature.channel = chOut.enabled ? chOut : { enabled: false };
    }
    if (runtime !== undefined) {
      // runtime 跨 Basic Info / Tool & Advanced 两分区提交：仅合并本次挂载字段，null = 清除该项
      const merged: Record<string, unknown> = { ...((loadedFeature.runtime as Record<string, unknown>) || {}) };
      for (const [k, v] of Object.entries(runtime)) {
        if (v === undefined || v === null) {
          delete merged[k];
        } else {
          merged[k] = v;
        }
      }
      feature.runtime = merged;
    }
    if (multiagent !== undefined) {
      feature.multiagent = multiagent;
    }
    if (memory !== undefined) {
      // enabled=false 时仅提交 {enabled:false}，落盘策略无意义（SPEC §25）
      feature.memory = memory.enabled ? memory : { enabled: false };
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
    { key: 'profile', label: 'Profile', icon: <SparkIdLine /> },
    { key: 'basic', label: 'Basic Info', icon: <SparkDocumentLine /> },
    { key: 'tools', label: 'Tool & Advanced', icon: <SparkSettingLine /> },
    { key: 'multiagent', label: 'MultiAgent', icon: <SparkMultiAgentLine /> },
    { key: 'memory', label: '记忆', icon: <SparkMemoryLine /> },
    { key: 'sandbox', label: 'Sandbox', icon: <SparkInternetLine /> },
    { key: 'channel', label: 'Channel', icon: <SparkLinkLine /> },
    { key: 'skills', label: 'Skills', icon: <SparkMagicWandLine /> },
    ...(isAdmin ? [{ key: 'history' as Section, label: '会话历史', icon: <SparkHistoryLine /> }] : []),
  ];

  return (
    <div
      style={{
        // 移动端收紧左右留白，把横向空间尽量让给配置区
        padding: isPhone ? '12px 12px 20px' : '20px 28px',
        // 会话历史分区：flex 纵向链路铺满 main，面板精确吃满剩余高度（其余分区自然滚动）
        ...(isHistory
          ? {
              height: '100%',
              display: 'flex',
              flexDirection: 'column' as const,
              boxSizing: 'border-box' as const,
            }
          : null),
      }}
    >
      {/* 页头：头像 + 名称 + Save；会话历史分区压缩页头，纵向空间让给面板 */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: isHistory ? 8 : 12,
          marginBottom: isHistory ? 10 : 20,
          flexShrink: 0,
        }}
      >
        {detail?.avatar ? (
          <img
            src={detail.avatar}
            alt={detail.name || agentKey}
            style={{
              width: isHistory ? 28 : 40,
              height: isHistory ? 28 : 40,
              borderRadius: 999,
              objectFit: 'cover',
              flexShrink: 0,
            }}
          />
        ) : (
          <span
            style={{
              width: isHistory ? 28 : 40,
              height: isHistory ? 28 : 40,
              borderRadius: 999,
              background: 'linear-gradient(135deg, #2b2b31, #1a1a1d)',
              color: '#fff',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: isHistory ? 13 : 17,
              fontWeight: 700,
            }}
          >
            {(detail?.name || agentKey).charAt(0).toUpperCase()}
          </span>
        )}
        <div>
          <div
            style={{
              fontWeight: 700,
              fontSize: isHistory ? 14 : 17,
              color: 'rgba(26, 26, 29, 0.92)',
            }}
          >
            {detail?.name || agentKey}
          </div>
          <div
            style={{
              fontFamily: 'Menlo, Consolas, monospace',
              fontSize: isHistory ? 10 : 11,
              color: 'rgba(26, 26, 29, 0.45)',
            }}
          >
            {agentKey}
          </div>
        </div>
        <div style={{ flex: 1 }} />
        {(section === 'basic' || section === 'tools' || section === 'multiagent' || section === 'memory' || section === 'sandbox' || section === 'channel') && (
          <Button type="primary" onClick={onSave} loading={saving}>
            Save
          </Button>
        )}
      </div>

      <div
        className={isHistory ? 'teapot-history-fill' : undefined}
        style={
          isHistory
            ? { flex: 1, minHeight: 0 }
            : // 移动端配置区固定最小宽度：视口再窄也只横向滚动，不压缩内容
              isPhone
              ? { overflowX: 'auto' as const }
              : undefined
        }
      >
        <Spin spinning={loading}>
        <Row gutter={20}>
          {/* 左侧胶囊菜单：收起态为纯图标窄条。
              移动端图标条固定 PHONE_RAIL_W 占位，展开时改悬浮层覆盖在内容之上，
              右侧配置区宽度始终不变，不会被菜单挤压 */}
          <Col
            xs={24}
            md={6}
            lg={5}
            style={
              isPhone
                ? { flex: `0 0 ${PHONE_RAIL_W}px`, maxWidth: PHONE_RAIL_W, position: 'relative', marginBottom: 16 }
                : menuCollapsed
                  ? { flex: `0 0 ${MENU_RAIL_W}px`, maxWidth: MENU_RAIL_W, height: '100%' }
                  : { flex: `0 0 ${MENU_W}px`, maxWidth: MENU_W, marginBottom: 16 }
            }
          >
            {/* 移动端展开态遮罩：点菜单外任意处即收回图标条 */}
            {isPhone && !menuCollapsed && (
              <div onClick={() => setMenuNarrow(true)} style={{ position: 'fixed', inset: 0, zIndex: 20 }} />
            )}
            <div
              className="glass-card"
              style={{
                padding: 8,
                display: 'flex',
                flexDirection: 'column',
                gap: 4,
                height: menuCollapsed && !isPhone ? '100%' : undefined,
                boxSizing: menuCollapsed ? ('border-box' as const) : undefined,
                ...(isPhone && !menuCollapsed
                  ? {
                      position: 'absolute',
                      top: 0,
                      // 抵消 Col 的 gutter 内边距，与收起态图标条左对齐
                      left: 10,
                      width: MENU_W,
                      zIndex: 30,
                      background: '#fff',
                      boxShadow: '0 12px 32px rgba(0, 0, 0, 0.16)',
                    }
                  : null),
              }}
            >
              {/* 收起/展开按钮：移动端菜单默认收起，靠它手动展开（收起态卡面仅 28px，按钮需收紧） */}
              <div style={{ display: 'flex', justifyContent: menuCollapsed ? 'center' : 'flex-end' }}>
                <Tooltip title={menuCollapsed ? '展开菜单' : '收起菜单'}>
                  <IconButton
                    bordered={false}
                    style={isPhone && menuCollapsed ? { width: 28, height: 28, minWidth: 28, padding: 0 } : undefined}
                    icon={menuCollapsed ? <SparkMenuExpandLine size={16} /> : <SparkMenuFoldLine size={16} />}
                    onClick={() => {
                      menuTouched.current = true;
                      setMenuNarrow((v) => !v);
                    }}
                  />
                </Tooltip>
              </div>
              {menuItems.map((m) => {
                const active = section === m.key;
                return (
                  <div
                    key={m.key}
                    onClick={() => {
                      setSection(m.key);
                      // 移动端：选定分区即收起为图标条，横向空间留给配置区
                      if (isPhone) {
                        setMenuNarrow(true);
                      }
                    }}
                    title={m.label}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: menuCollapsed ? 'center' : 'flex-start',
                      gap: 10,
                      padding: menuCollapsed ? '10px 0' : '10px 14px',
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
                    {m.icon}
                    {!menuCollapsed && <span style={{ whiteSpace: 'nowrap' }}>{m.label}</span>}
                  </div>
                );
              })}
            </div>
          </Col>

          {/* 右侧内容：flex-basis 0 + minWidth 0，只吃剩余宽度，避免被内容撑宽导致 Row wrap /
              横向溢出；移动端改为固定最小宽度（窄视口下横向滚动），不再被菜单压缩 */}
          <Col
            xs={24}
            md={18}
            lg={19}
            style={{
              flex: '1 1 0',
              minWidth: isPhone ? PHONE_CONTENT_MIN_W : 0,
              maxWidth: 'none',
              ...(isHistory ? { height: '100%' } : null),
            }}
          >
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
                        <SparkCameraLine /> {avatarUploading ? '上传中…' : '更换头像'}
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
                    <Button icon={<SparkEditLine />} onClick={() => setSection('basic')}>
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
                    name={['runtime', 'thinkingMode']}
                    label="Thinking Mode"
                    valuePropName="checked"
                    tooltip="开启模型思考模式（仅 DashScope 生效，OpenAI 供应商忽略）"
                  >
                    <Switch />
                  </Form.Item>
                  <Row gutter={16}>
                    <Col xs={24} sm={12}>
                      <Form.Item name={['runtime', 'temperature']} label="Temperature" tooltip="采样温度 0–2，留空默认">
                        <InputNumber min={0} max={2} step={0.1} style={{ width: '100%' }} />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={12}>
                      <Form.Item name={['runtime', 'topP']} label="Top P" tooltip="核采样 0–1，留空默认">
                        <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} />
                      </Form.Item>
                    </Col>
                  </Row>
                  <Form.Item name={['runtime', 'maxTokens']} label="Max Tokens" tooltip="最大生成 tokens，留空默认">
                    <InputNumber min={1} max={65536} style={{ width: '100%' }} />
                  </Form.Item>
                  <Row gutter={16}>
                    <Col xs={24} sm={12}>
                      <Form.Item
                        name={['runtime', 'enablePlanMode']}
                        label="Enable Plan Mode"
                        valuePropName="checked"
                        tooltip="开启后 Agent 具备计划模式能力"
                      >
                        <Switch />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={12}>
                      <Form.Item
                        name={['runtime', 'enableShell']}
                        label="Enable Shell"
                        valuePropName="checked"
                        tooltip="关闭则无 shell_execute 工具；未配置时跟随沙箱启用"
                      >
                        <Switch />
                      </Form.Item>
                    </Col>
                  </Row>
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
                </Form>
              </div>
            )}

            {section === 'tools' && (
              <div className="glass-card" style={{ padding: 24 }}>
                <Form form={form} layout="vertical">
                  <Form.Item
                    name="compactionEnabled"
                    label="启用上下文压缩"
                    valuePropName="checked"
                    tooltip="开启后会话历史超过触发轮数时自动压缩摘要；关闭则完整保留全部历史（可能超出模型上下文）"
                  >
                    <Switch />
                  </Form.Item>
                  {compactionEnabled && (
                    <Row gutter={16}>
                      <Col xs={24} sm={12}>
                        <Form.Item
                          name="compactionTrigger"
                          label="压缩触发轮数"
                          tooltip="会话历史超过该轮数时触发记忆压缩"
                        >
                          <InputNumber min={1} max={200} style={{ width: '100%' }} />
                        </Form.Item>
                      </Col>
                      <Col xs={24} sm={12}>
                        <Form.Item
                          name="compactionKeep"
                          label="压缩保留轮数"
                          tooltip="压缩后保留的最近轮数原文，更早消息被摘要替代"
                        >
                          <InputNumber min={1} max={100} style={{ width: '100%' }} />
                        </Form.Item>
                      </Col>
                    </Row>
                  )}
                  <Row gutter={16}>
                    <Col xs={24} sm={12}>
                      <Form.Item
                        name={['runtime', 'maxIterations']}
                        label="Max Iterations"
                        tooltip="ReAct 最大迭代轮数 1–100，留空 = SDK 默认"
                      >
                        <InputNumber min={1} max={100} style={{ width: '100%' }} />
                      </Form.Item>
                    </Col>
                  </Row>
                  <Form.Item
                    name={['runtime', 'allowedTools']}
                    label="Allowed Tools"
                    tooltip="工具白名单（回车/逗号分隔录入），留空 = 不限制"
                  >
                    <Select mode="tags" placeholder="留空不限制" tokenSeparators={[',', ' ']} />
                  </Form.Item>
                </Form>
              </div>
            )}

            {section === 'multiagent' && (
              <div className="glass-card" style={{ padding: 24 }}>
                <Form form={form} layout="vertical">
                  <Form.Item
                    name={['multiagent', 'enabled']}
                    label="启用 MultiAgent（Subagent）"
                    valuePropName="checked"
                    tooltip="开启后 Agent 可生成并编排子智能体拆解复杂任务（AgentScope subagent 能力）"
                  >
                    <Switch />
                  </Form.Item>
                  <div style={{ fontSize: 12, color: 'rgba(26,26,29,0.45)' }}>
                    关闭后将禁用 subagent 工具与动态 subagent 生成。保存后对新请求即时生效。
                  </div>
                </Form>
              </div>
            )}

            {section === 'memory' && (
              <div className="glass-card" style={{ padding: 24 }}>
                <Form form={form} layout="vertical">
                  <Form.Item
                    name={['memory', 'enabled']}
                    label="启用记忆"
                    valuePropName="checked"
                    tooltip="开启后对话自动沉淀长期记忆并在后续会话注入；关闭则停用记忆落盘与 memory_search 等记忆工具"
                  >
                    <Switch />
                  </Form.Item>
                  <Row gutter={16}>
                    <Col xs={24} sm={12}>
                      <Form.Item
                        name={['memory', 'flushTrigger']}
                        label="落盘策略"
                        tooltip="always = 每轮对话后即时落盘（默认）；throttled = 按间隔节流落盘；never = 不自动落盘"
                      >
                        <Select
                          options={[
                            { value: 'always', label: '每轮落盘（默认）' },
                            { value: 'throttled', label: '节流落盘' },
                            { value: 'never', label: '不自动落盘' },
                          ]}
                        />
                      </Form.Item>
                    </Col>
                    {memFlushTrigger === 'throttled' && (
                      <Col xs={24} sm={12}>
                        <Form.Item
                          name={['memory', 'flushThrottleMinutes']}
                          label="节流间隔（分钟）"
                          tooltip="两次落盘之间的最小间隔，1–1440，默认 10"
                        >
                          <InputNumber min={1} max={1440} style={{ width: '100%' }} />
                        </Form.Item>
                      </Col>
                    )}
                  </Row>
                  <div style={{ fontSize: 12, color: 'rgba(26,26,29,0.45)' }}>
                    chat 界面支持用户按请求临时覆盖记忆开关（参数传递），请求级开关优先于本配置。
                  </div>
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
                      <Button icon={<SparkSettingLine />} onClick={() => navigate('/system/sandbox')}>
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

            {section === 'channel' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                {channelNames.length === 0 && (
                  <Alert
                    type="warning"
                    showIcon
                    message="渠道未接入"
                    description="请联系管理员在「系统配置 - 连接器」中新建渠道连接记录后再启用 Channel。"
                  />
                )}
                <div className="glass-card" style={{ padding: 24 }}>
                  <Form form={form} layout="vertical">
                    <Form.Item
                      name={['channel', 'enabled']}
                      label="启用渠道连接器"
                      valuePropName="checked"
                      tooltip="启用后 Agent 接入钉钉等外部渠道收发消息，长驻连接由服务端维护"
                    >
                      <Switch disabled={channelNames.length === 0} />
                    </Form.Item>
                    {chEnabled && (
                      <>
                        <Form.Item
                          name={['channel', 'channelRecord']}
                          label="渠道连接记录"
                          tooltip="启用时必选其一（§24.4）；记录在系统配置 - 连接器中维护"
                          rules={[{ required: true, message: '启用渠道必须选择一条连接记录' }]}
                        >
                          <Select
                            placeholder="选择渠道连接记录"
                            options={channelNames.map((r) => ({
                              value: r.name,
                              label: `${r.name}（${r.channelType === 'dingtalk' ? '钉钉' : r.channelType === 'discord' ? 'Discord' : r.channelType === 'github' ? 'GitHub' : r.channelType}）`,
                            }))}
                          />
                        </Form.Item>
                        <Form.Item
                          name={['channel', 'dmScope']}
                          label="会话隔离粒度"
                          tooltip="决定渠道侧会话如何切分，与记忆/上下文隔离粒度一致"
                        >
                          <Radio.Group>
                            <Radio value="PER_CHANNEL_PEER">每群/每人独立（推荐）</Radio>
                            <Radio value="PER_PEER">每人合并（跨群同人一会话）</Radio>
                            <Radio value="MAIN">全局单会话</Radio>
                          </Radio.Group>
                        </Form.Item>
                        <div style={{ fontSize: 12, color: 'rgba(26,26,29,0.45)' }}>
                          渠道会话不进 Web 对话台（会话域隔离）；管理员可在左侧菜单「会话历史」中查看全量对话。
                          保存后服务端自动重启该 Agent 的渠道连接。
                        </div>
                      </>
                    )}
                  </Form>
                </div>

                {isAdmin && (
                  <div className="glass-card" style={{ padding: 24 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
                      <div style={{ fontSize: 15, fontWeight: 700 }}>渠道连接记录</div>
                      <div style={{ flex: 1 }} />
                      <Button icon={<SparkSettingLine />} onClick={() => navigate('/system/channel')}>
                        前往系统配置
                      </Button>
                    </div>
                    <div style={{ fontSize: 12, color: 'rgba(26,26,29,0.5)', marginTop: 12 }}>
                      当前共 {channelNames.length} 条渠道连接记录；启用时必选其一，凭证由记录决定。
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
                  <Button type="primary" icon={<SparkMagicWandLine />} onClick={() => navigate('/skills')}>
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

            {/* 会话历史分区（SPEC §24.9，仅 admin）：复用 chat 页 SessionPanel + 聊天面板，只读 */}
            {section === 'history' && isAdmin && <HistoryChatPanel agentKey={agentKey} />}
          </Col>
        </Row>
        </Spin>
      </div>
    </div>
  );
}
