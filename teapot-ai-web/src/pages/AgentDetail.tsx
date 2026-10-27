import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, AutoComplete, Col, Collapse, Empty, Row, Spin, theme, Tooltip } from 'antd';
import {
  Button,
  Form,
  IconButton,
  Input,
  InputNumber,
  message,
  Popconfirm,
  Radio,
  Select,
  Switch,
  Tag,
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
  SparkApplicationToolLine,
  SparkGuardrailLine,
  SparkPluginLine,
  SparkPlusLine,
  SparkDeleteLine,
} from '@agentscope-ai/icons';
import { useNavigate, useParams } from 'react-router-dom';
import {
  agentBindSkill,
  agentCreate,
  agentDetail,
  agentUnbindSkill,
  agentUpdate,
  deleteMemoryItem,
  mediaModelCatalog,
  memoryItems,
  middlewareSchema,
  modelPresets,
} from '../api/agent';
import type { AgentCreatePayload } from '../api/agent';
import { sandboxOptions, sandboxRecordNames, storageRecordNames } from '../api/config';
import { channelRegistry } from '../api/channelConfig';
import { mcpRegistry } from '../api/mcpConfig';
import { ResponsiveModal } from '../components/ResponsiveModal';
import { customModelId, modelSelectOptions } from '../components/modelOptions';
import type { CustomModelRow } from '../components/modelOptions';
import {
  MCPConfigFormFields,
  argsToLines,
  linesToArgs,
  linesToMap,
  mapToLines,
} from '../components/MCPConfigForm';
import HistoryChatPanel from '../chat/HistoryChatPanel';
import {
  MiddlewareConfigSection,
  applyMiddlewareFeature,
  seedMiddleware,
} from '../components/MiddlewareConfig';
import type { MiddlewareValues } from '../components/MiddlewareConfig';
import { uploadAgentAvatar } from '../api/avatar';
import { skillDelete, skillList } from '../api/skill';
import { modelProviders } from '../api/model';
import type { ModelProviderMeta } from '../api/model';
import { AddSkillButton } from '../components/AddSkill';
import { sessionStats } from '../api/session';
import { useAuthStore } from '../store/auth';
import { useWorkspaceStore } from '../store/workspace';
import { useIsPhone } from '../hooks/useIsPhone';
import { PHONE_BP } from '../theme/breakpoints';
import type {
  AgentA2uiConfig,
  AgentGuardConfig,
  AgentChannelConfig,
  AgentMCPConfig,
  AgentMCPServer,
  AgentMemoryConfig,
  AgentMultiAgentConfig,
  AgentRuntimeConfig,
  AgentSandboxConfig,
  AgentSessionTitleConfig,
  AgentCompactionConfig,
  ChannelRecordName,
  MCPRecordName,
  MediaModelCatalogEntry,
  MemoryUserGroup,
  MiddlewareSchemaView,
  SandboxOptions,
  SandboxRecordName,
  SkillListItem,
  StorageRecordName,
} from '../types';

type Section = 'profile' | 'basic' | 'tools' | 'a2ui' | 'guard' | 'multiagent' | 'memory' | 'middleware' | 'sandbox' | 'channel' | 'mcp' | 'skills' | 'history';

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

  // 月份刻度：含当月首日的列标注月份（顶部日期轴）
  const monthMarks = useMemo(() => {
    const out: { col: number; label: string }[] = [];
    let lastMonth = -1;
    cells.forEach((c, i) => {
      const m = Number(c.date.slice(5, 7));
      if (m !== lastMonth) {
        out.push({ col: Math.floor(i / 7), label: `${m}月` });
        lastMonth = m;
      }
    });
    return out;
  }, [cells]);

  const CELL = 15;
  const GAP = 4;

  return (
    <div style={{ overflowX: 'auto', paddingTop: 8 }}>
      <div style={{ position: 'relative', width: 'max-content' }}>
        {/* 顶部日期轴：按月标注 */}
        <div style={{ position: 'relative', height: 18 }}>
          {monthMarks.map((m) => (
            <span
              key={`${m.label}-${m.col}`}
              style={{ position: 'absolute', left: m.col * (CELL + GAP), fontSize: 11, color: 'rgba(26, 26, 29, 0.45)', whiteSpace: 'nowrap' }}
            >
              {m.label}
            </span>
          ))}
        </div>
        <div style={{ display: 'grid', gridAutoFlow: 'column', gridTemplateRows: `repeat(7, ${CELL}px)`, gap: GAP, width: 'max-content' }}>
          {cells.map((c) => (
            <span
              key={c.date}
              title={`${c.date} · ${c.count} 个会话`}
              style={{ width: CELL, height: CELL, borderRadius: 3, background: color(c.count) }}
            />
          ))}
        </div>
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
 * Agent 详情（左侧胶囊菜单 + 分区内容）。
 * SPEC §12.2：sysPrompt/模型/压缩参数/Skill 绑定。
 * 路由 /agents/new 复用本页作为「新建态」（SPEC §12.2）：只开放 Basic Info 分区，
 * Save 走 create，成功后换成真实 agentKey 继续配置。
 */
export const NEW_AGENT_KEY = 'new';

/** 表单空间值 → 后端 workspaceId（'personal'/留空 = 个人空间懒创建，不下发该字段） */
function toWorkspaceId(v: unknown): number | undefined {
  return !v || v === 'personal' ? undefined : Number(v);
}

export default function AgentDetailPage() {
  const { agentKey = '' } = useParams();
  const isNew = agentKey === NEW_AGENT_KEY;
  const navigate = useNavigate();
  const { token } = theme.useToken();
  const [form] = Form.useForm();
  const [section, setSection] = useState<Section>(isNew ? 'basic' : 'profile');
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
  /** 已接入供应商与思考档表达力（SPEC §29.16）：provider 下拉与档位禁用的数据源 */
  const [providerMeta, setProviderMeta] = useState<ModelProviderMeta[]>([]);
  const [boundSkills, setBoundSkills] = useState<string[]>([]);
  const [allSkills, setAllSkills] = useState<SkillListItem[]>([]);
  const [detail, setDetail] = useState<{ name: string; description?: string; status: number; createdAt?: string; avatar?: string } | null>(null);
  const [sbOptions, setSbOptions] = useState<SandboxOptions | null>(null);
  const [storageNames, setStorageNames] = useState<StorageRecordName[]>([]);
  const [sandboxNames, setSandboxNames] = useState<SandboxRecordName[]>([]);
  const [channelNames, setChannelNames] = useState<ChannelRecordName[]>([]);
  const [mcpNames, setMcpNames] = useState<MCPRecordName[]>([]);
  /** 生成模型目录（SPEC-media-gen §4.8）：按能力位给出可选模型，接口异常时为空数组（仍可自由输入） */
  const [mediaCatalog, setMediaCatalog] = useState<MediaModelCatalogEntry[]>([]);
  /** 已加载的原始 feature 命名空间：分区保存时合并，避免单分区覆盖另一分区（§22） */
  const [loadedFeature, setLoadedFeature] = useState<Record<string, unknown>>({});
  /** feature 驱动中间件清单（SPEC §29.23）：后端内省配置类产出的 schema + 本 Agent 已存配置 */
  const [mwViews, setMwViews] = useState<MiddlewareSchemaView[]>([]);
  /** 中间件装配位（SPEC §29.25）：只有点「装配中间件」挑中的才出现在界面并写进 feature */
  const [mwAttached, setMwAttached] = useState<Record<string, boolean>>({});
  /** 中间件配置值（SPEC §29.27）：由装配/编辑弹窗收进来，不挂页面表单，避免同名字段两处挂载互相注销 */
  const [mwValues, setMwValues] = useState<MiddlewareValues>({});
  const isAdmin = useAuthStore((s) => s.hasRole('admin'));
  /** 新建态：所属空间下拉与空间作用域的模型清单（SPEC §28.11 与后端保存闸门同源） */
  const { workspaces, activeId, loaded: wsLoaded, load: loadWorkspaces } = useWorkspaceStore();
  const draftSpace = Form.useWatch('workspaceId', form) as string | undefined;
  const draftSeeded = useRef(false);

  useEffect(() => {
    if (!isNew) {
      return;
    }
    if (!wsLoaded) {
      loadWorkspaces();
      return;
    }
    if (draftSeeded.current) {
      return;
    }
    draftSeeded.current = true;
    form.setFieldsValue({
      modelSource: 'system',
      workspaceId: activeId == null ? 'personal' : String(activeId),
    });
  }, [isNew, wsLoaded, loadWorkspaces, activeId, form]);

  useEffect(() => {
    if (!isNew) {
      return;
    }
    let cancelled = false;
    modelPresets(toWorkspaceId(draftSpace) ?? 0)
      .then((presets) => {
        if (!cancelled) {
          setModels(presets || []);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setModels([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [isNew, draftSpace]);
  const sbEnabled = Form.useWatch(['sandbox', 'enabled'], form);
  const sbPersistence = Form.useWatch(['sandbox', 'persistence'], form);
  /** 沙箱承载（SPEC §29.17）：source 是纯 UI 字段，hasE2bApiKey 是回显标记，都不入库 */
  const sbCfg = Form.useWatch('sandbox', form) as
    | (AgentSandboxConfig & { source?: 'record' | 'custom'; hasE2bApiKey?: boolean })
    | undefined;
  const sbCustom = sbCfg?.source === 'custom';
  /** 切承载来源：清掉另一侧字段，避免带着"二选一"的混合值去保存（后端会拒） */
  const onSandboxSourceChange = (v: 'record' | 'custom') => {
    if (v === 'custom') {
      form.setFieldsValue({ sandbox: { sandboxRecord: undefined } });
      // 自带承载不支持 NAS（挂载点由服务端配置决定）
      if (form.getFieldValue(['sandbox', 'persistence']) === 'NAS') {
        form.setFieldValue(['sandbox', 'persistence'], 'LOCAL_SNAPSHOT');
      }
      return;
    }
    form.setFieldsValue({
      sandbox: { e2bApiKey: undefined, e2bApiBaseUrl: undefined, e2bDomain: undefined, hasE2bApiKey: undefined },
    });
  };
  const chEnabled = Form.useWatch(['channel', 'enabled'], form);
  const mcpEnabled = Form.useWatch(['mcp', 'enabled'], form);
  /** MCP Server 列表（弹窗编辑后需重渲染，走 useWatch） */
  const mcpServers = Form.useWatch(['mcp', 'mcpServers'], form) as AgentMCPServer[] | undefined;
  /** 模型来源（SPEC §29）：二选一——用空间开放的系统模型，或自定义唯一一个自带密钥的模型 */
  const modelSource = Form.useWatch('modelSource', form) as 'system' | 'custom' | undefined;
  const isCustomModel = modelSource === 'custom';
  /** 自定义模型只有一条，固定落在 customModels[0]（入库仍是数组，服务端限一条） */
  const customModel = (Form.useWatch(['customModels', 0], form) as CustomModelRow | undefined);
  const customModelValue = customModelId(customModel ?? {});
  const hasCustomModel = Boolean(customModel?.provider && customModel?.modelName?.trim());
  /** 各界点子模型下拉：系统模型 + （自定义模式下的）那一个自带模型 */
  const modelOptions = useMemo(
    () => modelSelectOptions(models, isCustomModel && hasCustomModel ? [customModel!] : []),
    [models, isCustomModel, hasCustomModel, customModel],
  );
  // 自定义模式下主模型就是它自己：模型名一改就跟随，避免两处填成两个模型
  useEffect(() => {
    if (isCustomModel && hasCustomModel) {
      form.setFieldValue('modelId', customModelValue);
    }
  }, [isCustomModel, hasCustomModel, customModelValue, form]);
  /** 切模型来源：自定义给一条空行；切回空间模型则清掉自带行，且主模型若正指向它要让用户重选 */
  const onModelSourceChange = (v: 'system' | 'custom') => {
    if (v === 'custom') {
      if (!form.getFieldValue(['customModels', 0, 'provider'])) {
        form.setFieldValue('customModels', [{ provider: 'dashscope' }]);
      }
      // 模型名还没填就先清空主模型，让 Model 框显示占位提示而不是上一个来源的残值
      form.setFieldValue('modelId', hasCustomModel ? customModelValue : undefined);
      return;
    }
    if (hasCustomModel && form.getFieldValue('modelId') === customModelValue) {
      form.setFieldValue('modelId', undefined);
    }
    form.setFieldValue('customModels', []);
  };
  /** MCP Server 新增/编辑弹窗（复用系统配置 MCP 弹窗表单，§MCP） */
  const [mcpModal, setMcpModal] = useState<{ mode: 'create' | 'edit'; index: number } | null>(null);
  const [mcpModalForm] = Form.useForm();
  const mcpModalMode = Form.useWatch('mode', mcpModalForm);
  /** 上下文压缩显式开关（映射 compactionTrigger：关 = -1，SPEC §25） */
  const compactionEnabled = Form.useWatch('compactionEnabled', form);
  /** 触发口径（SPEC §29.21/§29.22）：决定左格摆预缓冲、token 阈值还是触发轮数 */
  const compactionTriggerMode = Form.useWatch(['compaction', 'triggerMode'], form) as
    | 'dynamic'
    | 'messages'
    | 'tokens'
    | undefined;
  /** 尾部保留方式（SPEC §29.20）：决定"压缩保留轮数"还是"尾部 token 预算"出现在界面上 */
  const compactionKeepMode = Form.useWatch(['compaction', 'keepMode'], form) as
    | 'dynamic'
    | 'messages'
    | 'tokens'
    | undefined;
  /** 记忆落盘策略（throttled 时展示节流间隔，SPEC §25） */
  const memFlushTrigger = Form.useWatch(['memory', 'flushTrigger'], form);
  /** 生图/生视频开关：开启后才展示六个能力位的模型选择器（SPEC-media-gen §4.8） */
  const mediaGenEnabled = Form.useWatch(['runtime', 'enableMediaGen'], form);
  /** A2UI 开关：开启后才展示 catalog 与停止语义子配置 */
  const a2uiEnabled = Form.useWatch(['a2ui', 'enabled'], form);
  const guardEnabled = Form.useWatch(['guard', 'enabled'], form);
  const guardMode = Form.useWatch(['guard', 'mode'], form);
  /**
   * 思考档可用性跟着主模型的供应商走（SPEC §29.16）：六家 SDK 通道不等价，
   * 后端对不支持的档位直接拒保存，所以这里禁用并写明原因，而不是让人选了再报错。
   * providerMeta 拉不到时（接口异常）不禁用 —— 保持旧行为，交给后端兜底。
   */
  const mainModelId = Form.useWatch('modelId', form) as string | undefined;
  const mainThinkingSupport = providerMeta.find(
    (p) => mainModelId?.split(':')[0] === p.provider,
  )?.thinkingSupport;
  // providerMeta 未就绪时不禁用（undefined），保持旧行为并交给后端闸门兜底
  const thinkingOnOk = mainThinkingSupport === undefined || mainThinkingSupport !== 'NONE';
  const thinkingOffOk = mainThinkingSupport === undefined || mainThinkingSupport === 'TRISTATE';
  const thinkingOptions = useMemo(() => [
    { label: '默认', value: 'default' },
    { label: thinkingOnOk ? '开启' : '开启（该供应商无此通道）', value: 'on', disabled: !thinkingOnOk },
    { label: thinkingOffOk ? '关闭' : '关闭（该供应商没有关闭通道）', value: 'off', disabled: !thinkingOffOk },
  ], [thinkingOnOk, thinkingOffOk]);
  // 换模型后原档位可能不再可表达：回落「默认」并说明原因，避免带着后端会拒的值去保存
  useEffect(() => {
    const cur = form.getFieldValue(['runtime', 'thinkingMode']);
    if (cur !== 'on' && cur !== 'off') {
      return;
    }
    if ((cur === 'on' && !thinkingOnOk) || (cur === 'off' && !thinkingOffOk)) {
      form.setFieldValue(['runtime', 'thinkingMode'], 'default');
      message.info(
        `${mainModelId ?? '该模型'} 的供应商不支持思考模式「${cur === 'on' ? '开启' : '关闭'}」，已改回「默认」`,
      );
    }
  }, [thinkingOnOk, thinkingOffOk, mainModelId, form]);
  /** provider 下拉数据源（SPEC §29.16）：清单没拉到时退回历史两家，保证表单可用 */
  const providerOptions = useMemo(
    () => (providerMeta.length ? providerMeta : [{ provider: 'dashscope' }, { provider: 'openai' }])
      .map((p) => ({ value: p.provider })),
    [providerMeta],
  );
  const [avatarUploading, setAvatarUploading] = useState(false);
  const avatarInputRef = useRef<HTMLInputElement>(null);
  /** Redis 记忆内容（SPEC §27 记忆管理）：按 uid 分组的记忆文件清单，支持逐条删除 */
  const [memGroups, setMemGroups] = useState<MemoryUserGroup[]>([]);
  const [memLoading, setMemLoading] = useState(false);
  /** 记忆用户筛选：空 = 全部，否则仅显示匹配 uid */
  const [memUidFilter, setMemUidFilter] = useState<string>('');
  const loadMemory = useCallback(() => {
    setMemLoading(true);
    memoryItems(agentKey)
      .then(setMemGroups)
      .catch(() => setMemGroups([]))
      .finally(() => setMemLoading(false));
  }, [agentKey]);
  useEffect(() => {
    if (section === 'memory') loadMemory();
  }, [section, loadMemory]);
  /** 逐条删除记忆文件后刷新列表 */
  const onDeleteMemoryItem = useCallback(async (uid: string, path: string) => {
    try {
      await deleteMemoryItem(agentKey, uid, path);
      message.success('记忆已删除');
      loadMemory();
    } catch {
      message.error('删除失败');
    }
  }, [agentKey, loadMemory]);

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
    if (isNew) {
      // 新建态没有 Agent 可读：模型清单由 draftSpace 的副作用负责，这里不发任何详情请求
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const [d, skills, sbOpts, ossNames, sbRecordNames, chNames, mcpList, mmCatalog, pm, mwSchemas] = await Promise.all([
        agentDetail(agentKey),
        skillList(),
        sandboxOptions(),
        storageRecordNames().catch(() => [] as StorageRecordName[]),
        sandboxRecordNames().catch(() => [] as SandboxRecordName[]),
        channelRegistry().catch(() => [] as ChannelRecordName[]),
        mcpRegistry().catch(() => [] as MCPRecordName[]),
        mediaModelCatalog().catch(() => [] as MediaModelCatalogEntry[]),
        modelProviders().catch(() => [] as ModelProviderMeta[]),
        middlewareSchema(agentKey).catch(() => [] as MiddlewareSchemaView[]),
      ]);
      setProviderMeta(pm);
      // 系统模型按 Agent 所属空间过滤（SPEC §28.11），与后端保存闸门同源；
      // 存量未归属的 Agent 落全局桶 0
      const presets = await modelPresets(d.agent.workspaceId ?? 0);
      setSbOptions(sbOpts);
      setStorageNames(ossNames);
      setSandboxNames(sbRecordNames);
      setChannelNames(chNames);
      setMcpNames(mcpList);
      setMediaCatalog(mmCatalog);
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
      let mcp: Partial<AgentMCPConfig> = {};
      let rt: Partial<AgentRuntimeConfig> = {};
      let ma: Partial<AgentMultiAgentConfig> = {};
      let mem: Partial<AgentMemoryConfig> = {};
      let st: Partial<AgentSessionTitleConfig> = {};
      let comp: Partial<AgentCompactionConfig> = {};
      let a2ui: Partial<AgentA2uiConfig> = {};
      let gd: Partial<AgentGuardConfig> = {};
      let cms: CustomModelRow[] = [];
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
            if (f.mcp) {
              mcp = f.mcp;
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
            if (f.sessionTitle) {
              st = f.sessionTitle;
            }
            if (f.compaction) {
              comp = f.compaction;
            }
            if (f.a2ui) {
              a2ui = f.a2ui;
            }
            if (f.guard) {
              gd = f.guard;
            }
            if (Array.isArray(f.customModels)) {
              // apiKey 出服务端即清空（SPEC §29），界面靠 hasApiKey 提示「已配置，留空不修改」
              cms = (f.customModels as Record<string, unknown>[]).map((m) => ({
                provider: m.provider as string,
                modelName: m.modelName as string,
                displayName: m.displayName as string | undefined,
                baseUrl: m.baseUrl as string | undefined,
                hasApiKey: !!m.hasApiKey,
                capabilities: typeof m.capabilities === 'string' && m.capabilities
                  ? (m.capabilities as string).split(',')
                  : undefined,
              }));
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
      // feature 驱动中间件回显（SPEC §29.23）：开关位由「有没有写这个命名空间」派生
      setMwViews(mwSchemas);
      const mwSeed = seedMiddleware(mwSchemas);
      setMwAttached(mwSeed.attached);
      setMwValues(mwSeed.values);
      form.setFieldsValue({
        name: d.agent.name,
        description: d.agent.description,
        modelId: d.agent.modelId,
        compactionTrigger: d.agent.compactionTrigger,
        compactionKeep: d.agent.compactionKeep,
        // 压缩开关回显：列值为负数/0 = 关；空值 = 跟随 SDK 默认（启用）
        // 0 = 压缩仍开、只是不按条数触发（§29.21），故仅 -1 判关
        compactionEnabled: !(d.agent.compactionTrigger != null && d.agent.compactionTrigger < 0),
        sysPrompt: d.agent.sysPrompt,
        storageTarget,
        sandbox: {
          enabled: !!sb.enabled,
          // 承载来源回显（SPEC §29.17）：任一自带字段有值 = custom；apiKey 出服务端即清空，靠 has 标记提示
          source: sb.e2bApiKey || sb.e2bApiBaseUrl || sb.e2bDomain ? 'custom' : 'record',
          sandboxRecord: sb.sandboxRecord,
          e2bApiBaseUrl: sb.e2bApiBaseUrl,
          e2bDomain: sb.e2bDomain,
          hasE2bApiKey: !!sb.hasE2bApiKey,
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
        mcp: {
          enabled: !!mcp.enabled,
          mcpServers: mcp.mcpServers ?? [],
        },
        runtime: {
          // 思考模式三态（SPEC §29.15）：表单用字符串承载（Select 的 value 放不了 boolean），
          // 未配置 = 'default'，保存时再还原成 true / false / 不写该键
          thinkingMode: rt.thinkingMode === undefined ? 'default' : rt.thinkingMode ? 'on' : 'off',
          temperature: rt.temperature,
          topP: rt.topP,
          maxTokens: rt.maxTokens,
          enablePlanMode: !!rt.enablePlanMode,
          // 存量未配置时跟随沙箱启用回显，与后端回落语义一致
          enableShell: rt.enableShell ?? !!sb.enabled,
          enableOssFile: !!rt.enableOssFile,
          enableMcpConfig: !!rt.enableMcpConfig,
          enableMediaGen: !!rt.enableMediaGen,
          // 生成模型回显：旧数据可能带空串，统一成 undefined 以免占位
          mediaModels: rt.mediaModels,
          permissionMode: rt.permissionMode,
          allowedTools: rt.allowedTools,
          maxIterations: rt.maxIterations,
          enablePendingToolRecovery: !!rt.enablePendingToolRecovery,
        },
        // 缺省命名空间 = 启用（与后端 MVP 语义一致，SPEC §25）
        multiagent: {
          enabled: ma.enabled !== false,
        },
        memory: {
          enabled: mem.enabled !== false,
          flushTrigger: mem.flushTrigger ?? 'always',
          flushThrottleMinutes: mem.flushThrottleMinutes ?? 10,
          modelId: mem.modelId,
        },
        sessionTitle: {
          enabled: st.enabled !== false,
          modelId: st.modelId,
        },
        compaction: {
          modelId: comp.modelId,
          // 未配置 = 框架默认（动态窗口预算 + 20k 预缓冲）；裁剪与落盘默认开启（SPEC §29.20/§29.21）
          triggerMode: comp.triggerMode ?? 'dynamic',
          triggerTokens: comp.triggerTokens,
          reserved: comp.reserved,
          keepMode: comp.keepMode ?? 'dynamic',
          keepTokens: comp.keepTokens,
          prune: { enabled: comp.prune?.enabled !== false },
          eviction: { enabled: comp.eviction?.enabled !== false },
        },
        // a2ui 缺省不启用；持久化未配置时回显 SDK 默认 true（与后端回落一致）
        a2ui: {
          enabled: !!a2ui.enabled,
          catalogId: a2ui.catalogId,
          catalogResource: a2ui.catalogResource,
          maxComponents: a2ui.maxComponents,
          surfacePersistEnabled: a2ui.surfacePersistEnabled !== false,
          appendPreference: a2ui.appendPreference,
          renderNoThinking: a2ui.renderNoThinking === true,
        },
        // 意图守卫：规则列表回显成多行文本；阈值/拒答文案未配置时留空跟随 SDK 默认
        guard: {
          enabled: !!gd.enabled,
          mode: gd.mode ?? 'jev',
          modelId: gd.modelId,
          acceptText: argsToLines(gd.acceptIntents),
          rejectText: argsToLines(gd.rejectIntents),
          allowThreshold: gd.allowThreshold,
          denyMessage: gd.denyMessage,
        },
        // 只有一条：多出来的（历史数据）截断，保存后不再回写
        customModels: cms.slice(0, 1),
        modelSource: cms.length > 0 ? 'custom' : 'system',
      });
      setBoundSkills(d.skillNames || []);
      setModels(presets || []);
      setAllSkills(skills || []);
    } catch {
      // 已统一提示
    } finally {
      setLoading(false);
    }
  }, [agentKey, isNew, form]);

  useEffect(() => {
    load();
  }, [load]);

  /** 打开 MCP Server 新增/编辑弹窗（index 缺省 = 新增） */
  const openMcpModal = (index?: number) => {
    mcpModalForm.resetFields();
    if (index === undefined) {
      mcpModalForm.setFieldsValue({
        mode: mcpNames.length > 0 ? 'record' : 'inline',
        transport: 'streamable_http',
      });
      setMcpModal({ mode: 'create', index: -1 });
      return;
    }
    const srv = (mcpServers ?? [])[index];
    if (srv?.record) {
      mcpModalForm.setFieldsValue({ mode: 'record', record: srv.record });
    } else {
      mcpModalForm.setFieldsValue({
        mode: 'inline',
        transport: srv?.transport ?? 'streamable_http',
        command: srv?.command,
        args: argsToLines(srv?.args),
        env: mapToLines(srv?.env),
        url: srv?.url,
        headers: mapToLines(srv?.headers),
        description: srv?.description,
      });
    }
    setMcpModal({ mode: 'edit', index });
  };

  /** MCP 弹窗提交：校验后生成 AgentMCPServer 写回主表单 */
  const onMcpModalOk = async () => {
    const values = await mcpModalForm.validateFields();
    let srv: AgentMCPServer;
    if (values.mode === 'record') {
      srv = { record: values.record };
    } else {
      const args = linesToArgs(values.args || '');
      const env = linesToMap(values.env || '');
      const headers = linesToMap(values.headers || '');
      srv = values.transport === 'stdio'
        ? {
            transport: 'stdio',
            command: values.command,
            args: args.length ? args : undefined,
            env: Object.keys(env).length ? env : undefined,
          }
        : {
            transport: values.transport,
            url: values.url,
            headers: Object.keys(headers).length ? headers : undefined,
          };
      if (values.description) srv.description = values.description;
    }
    const cur: AgentMCPServer[] = [...((form.getFieldValue(['mcp', 'mcpServers']) as AgentMCPServer[]) ?? [])];
    if (mcpModal?.mode === 'edit') {
      cur[mcpModal.index] = srv;
    } else {
      cur.push(srv);
    }
    form.setFieldsValue({ mcp: { ...form.getFieldValue('mcp'), mcpServers: cur } });
    setMcpModal(null);
  };

  const onSave = async () => {
    const values = await form.validateFields();
    const { sandbox, storageTarget, channel, mcp, runtime, multiagent, memory, sessionTitle, compaction: compactionFeature, compactionEnabled, a2ui, guard, customModels: customRows, modelSource, ...rest } = values as Record<string, unknown> & {
      sandbox?: Partial<AgentSandboxConfig>;
      storageTarget?: string;
      channel?: Partial<AgentChannelConfig>;
      mcp?: Partial<AgentMCPConfig>;
      runtime?: Partial<AgentRuntimeConfig>;
      multiagent?: Partial<AgentMultiAgentConfig>;
      memory?: Partial<AgentMemoryConfig>;
      sessionTitle?: Partial<AgentSessionTitleConfig>;
      compaction?: Partial<AgentCompactionConfig>;
      compactionEnabled?: boolean;
      a2ui?: Partial<AgentA2uiConfig>;
      guard?: Partial<AgentGuardConfig> & { acceptText?: string; rejectText?: string };
      customModels?: CustomModelRow[];
      modelSource?: 'system' | 'custom';
    };
    const payload: Record<string, unknown> = { ...rest };
    // 压缩开关 ↔ 列值映射：关 = -1/-1；开且留空时回落默认 30/10（SPEC §25）。
    // 0 是合法值（§29.21：轮数填 0 = 不按条数触发，只按 token/窗口），不能被回落吃掉
    if (compactionEnabled !== undefined) {
      if (!compactionEnabled) {
        payload.compactionTrigger = -1;
        payload.compactionKeep = -1;
      } else {
        if (payload.compactionTrigger == null || (payload.compactionTrigger as number) < 0) {
          payload.compactionTrigger = 30;
        }
        if (payload.compactionKeep == null || (payload.compactionKeep as number) < 0) {
          payload.compactionKeep = 10;
        }
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
      const sbOut = { ...sandbox } as Record<string, unknown>;
      if (typeof sbOut.sandboxRecord === 'string') {
        sbOut.sandboxRecord = sbOut.sandboxRecord.trim();
      }
      // source 是纯 UI 字段；按来源剥掉另一侧，保证落库的 sandbox 只有一套承载（§29.17）
      delete sbOut.source;
      delete sbOut.hasE2bApiKey;
      if (sbOut.enabled) {
        if (sbOut.e2bApiKey || sbOut.e2bApiBaseUrl || sbOut.e2bDomain || sbCfg?.hasE2bApiKey) {
          delete sbOut.sandboxRecord;
          delete sbOut.nas;
        } else {
          delete sbOut.e2bApiKey;
          delete sbOut.e2bApiBaseUrl;
          delete sbOut.e2bDomain;
        }
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
    if (mcp !== undefined) {
      const mcpOut = { ...mcp };
      if (Array.isArray(mcpOut.mcpServers)) {
        // 清理每条 server：record trim；inline 字段按 transport 保留
        mcpOut.mcpServers = mcpOut.mcpServers.map((s: AgentMCPServer) => {
          if (s.record) return { record: s.record.trim() };
          const cleaned: AgentMCPServer = { transport: s.transport };
          if (s.transport === 'stdio') {
            cleaned.command = s.command;
            if (s.args?.length) cleaned.args = s.args;
            if (s.env && Object.keys(s.env).length) cleaned.env = s.env;
          } else {
            cleaned.url = s.url;
            if (s.headers && Object.keys(s.headers).length) cleaned.headers = s.headers;
          }
          if (s.description) cleaned.description = s.description;
          return cleaned;
        }).filter((s: AgentMCPServer) => s.record || s.transport);
      }
      feature.mcp = mcpOut.enabled ? mcpOut : { enabled: false };
    }
    if (runtime !== undefined) {
      // 思考模式三态还原（SPEC §29.15）：'default' → 不写该键（后端 null = 不下发参数）
      const sel = (runtime as Record<string, unknown>).thinkingMode;
      if (typeof sel === 'string') {
        (runtime as Record<string, unknown>).thinkingMode = sel === 'default' ? undefined : sel === 'on';
      }
      // runtime 跨 Basic Info / Tool & Advanced 两分区提交：仅合并本次挂载字段，null = 清除该项
      const merged: Record<string, unknown> = { ...((loadedFeature.runtime as Record<string, unknown>) || {}) };
      for (const [k, v] of Object.entries(runtime)) {
        if (v === undefined || v === null) {
          delete merged[k];
        } else {
          merged[k] = v;
        }
      }
      // 生成模型清理（SPEC-media-gen §4.8）：空串/空对象 = 不锁定，不写入冗余键；
      // 关掉生图开关时一并清除旧配置（选择器未挂载时 values 里不会有 mediaModels）
      const mm = merged.mediaModels as Record<string, unknown> | undefined;
      if (mm && typeof mm === 'object') {
        const cleaned: Record<string, unknown> = {};
        for (const [k, v] of Object.entries(mm)) {
          if (typeof v === 'string' && v.trim()) {
            cleaned[k] = v.trim();
          }
        }
        if (Object.keys(cleaned).length > 0 && runtime.enableMediaGen !== false) {
          merged.mediaModels = cleaned;
        } else {
          delete merged.mediaModels;
        }
      } else if (runtime.enableMediaGen === false) {
        delete merged.mediaModels;
      }
      feature.runtime = merged;
    }
    if (multiagent !== undefined) {
      feature.multiagent = multiagent;
    }
    // feature 驱动中间件（SPEC §29.23/§29.27）：没装配的删命名空间，装配的只写非空字段，数组形态原样保留。
    // 值来自弹窗收进来的 state（不挂本页表单），所以不依赖 validateFields 的返回
    applyMiddlewareFeature(feature, mwViews, mwValues, mwAttached);
    if (memory !== undefined) {
      // enabled=false 时仅提交 {enabled:false}，落盘策略无意义（SPEC §25）
      feature.memory = memory.enabled ? memory : { enabled: false };
    }
    if (sessionTitle !== undefined) {
      feature.sessionTitle = sessionTitle.enabled !== false ? sessionTitle : { enabled: false };
    }
    if (compactionFeature !== undefined) {
      // compaction 命名空间（§29.20）：只下发"与框架默认不同"的部分 ——
      // 裁剪/落盘的"开"等价于不下发，只有显式关掉才写 {enabled:false}
      const cp: Record<string, unknown> = {};
      if (compactionFeature.modelId) {
        cp.modelId = compactionFeature.modelId;
      }
      // 触发口径（§29.21/§29.22）：dynamic 且未填预缓冲 = 框架默认，不下发；
      // messages 只是口径标注（轮数走 compaction_trigger 列），reserved 在该口径下不生效所以不带
      if (compactionFeature.triggerMode === 'tokens') {
        cp.triggerMode = 'tokens';
        if (compactionFeature.triggerTokens) {
          cp.triggerTokens = compactionFeature.triggerTokens;
        }
      } else if (compactionFeature.triggerMode === 'messages') {
        cp.triggerMode = 'messages';
      } else if (compactionFeature.reserved) {
        cp.reserved = compactionFeature.reserved;
      }
      if (compactionFeature.keepMode && compactionFeature.keepMode !== 'dynamic') {
        cp.keepMode = compactionFeature.keepMode;
        if (compactionFeature.keepMode === 'tokens' && compactionFeature.keepTokens) {
          cp.keepTokens = compactionFeature.keepTokens;
        }
      }
      if (compactionFeature.prune?.enabled === false) {
        cp.prune = { enabled: false };
      }
      if (compactionFeature.eviction?.enabled === false) {
        cp.eviction = { enabled: false };
      }
      if (Object.keys(cp).length > 0) {
        feature.compaction = cp;
      } else {
        delete feature.compaction;
      }
    }
    if (a2ui !== undefined) {
      // 关闭时仅提交 {enabled:false}；开启时 catalog 空串 = 未配置（回落 SDK 默认），不落冗余键
      if (!a2ui.enabled) {
        feature.a2ui = { enabled: false };
      } else {
        const a2uiOut: Record<string, unknown> = { ...a2ui };
        for (const key of ['catalogId', 'catalogResource', 'appendPreference']) {
          const v = a2uiOut[key];
          if (typeof v === 'string' && v.trim()) {
            a2uiOut[key] = v.trim();
          } else {
            delete a2uiOut[key];
          }
        }
        if (a2uiOut.maxComponents == null) {
          delete a2uiOut.maxComponents;
        }
        if (a2uiOut.renderNoThinking !== true) {
          delete a2uiOut.renderNoThinking;
        }
        feature.a2ui = a2uiOut;
      }
    }
    if (guard !== undefined) {
      // 意图守卫：关闭仅提交 {enabled:false}；多行文本转规则数组，空阈值/空文案不落键（回落 SDK 默认）
      if (!guard.enabled) {
        feature.guard = { enabled: false };
      } else {
        const guardOut: Record<string, unknown> = {
          enabled: true,
          mode: guard.mode ?? 'jev',
          acceptIntents: linesToArgs(guard.acceptText ?? ''),
          rejectIntents: linesToArgs(guard.rejectText ?? ''),
        };
        if (guardOut.mode === 'llm') {
          if (guard.modelId) {
            guardOut.modelId = guard.modelId;
          }
        } else if (guard.allowThreshold != null) {
          // 放行阈值仅 JEV 模式有意义
          guardOut.allowThreshold = guard.allowThreshold;
        }
        if (typeof guard.denyMessage === 'string' && guard.denyMessage.trim()) {
          guardOut.denyMessage = guard.denyMessage.trim();
        }
        feature.guard = guardOut;
      }
    }
    if (modelSource === 'custom') {
      // 自定义模型（SPEC §29）：只有一条，主模型就是它；apiKey 留空 = 不提交该键，
      // 服务端沿用上一版同 provider:model 的密文（hasApiKey 只是回显标记，不入库）
      const m = customRows?.[0];
      if (m?.provider && m.modelName?.trim()) {
        const out: Record<string, unknown> = { provider: m.provider, modelName: m.modelName.trim() };
        if (m.displayName?.trim()) out.displayName = m.displayName.trim();
        if (m.baseUrl?.trim()) out.baseUrl = m.baseUrl.trim();
        if (m.apiKey?.trim()) out.apiKey = m.apiKey.trim();
        if (m.capabilities?.length) out.capabilities = m.capabilities.join(',');
        feature.customModels = [out];
        payload.modelId = customModelId(m);
      } else {
        delete feature.customModels;
      }
    } else {
      // 选「空间模型」即彻底放弃自带模型：命名空间整体不下发，服务端随之清除密文
      delete feature.customModels;
    }
    if (Object.keys(feature).length > 0) {
      payload.feature = feature;
    }
    setSaving(true);
    try {
      if (isNew) {
        const draft = payload as Record<string, unknown> & {
          agentKey?: string;
          workspaceId?: string;
          sysPrompt?: string;
        };
        const key = (draft.agentKey ?? '').trim();
        await agentCreate({
          ...draft,
          agentKey: key,
          sysPrompt: draft.sysPrompt?.trim() ? draft.sysPrompt : '你是一个乐于助人的 AI 助手。',
          workspaceId: toWorkspaceId(draft.workspaceId),
        } as unknown as AgentCreatePayload);
        message.success('创建成功');
        navigate(`/agents/${key}`, { replace: true });
        return;
      }
      await agentUpdate(agentKey, payload);
      message.success('保存成功');
      load();
    } finally {
      setSaving(false);
    }
  };

  const allMenuItems: { key: Section; label: string; icon: React.ReactNode }[] = [
    { key: 'profile', label: 'Profile', icon: <SparkIdLine /> },
    { key: 'basic', label: 'Basic Info', icon: <SparkDocumentLine /> },
    { key: 'tools', label: 'Tool & Advanced', icon: <SparkSettingLine /> },
    { key: 'skills', label: 'Skills', icon: <SparkMagicWandLine /> },
    { key: 'a2ui', label: 'A2UI', icon: <SparkApplicationToolLine /> },
    { key: 'guard', label: '意图守卫', icon: <SparkGuardrailLine /> },
    { key: 'multiagent', label: 'MultiAgent', icon: <SparkMultiAgentLine /> },
    { key: 'memory', label: '记忆', icon: <SparkMemoryLine /> },
    { key: 'middleware', label: 'Middleware', icon: <SparkPluginLine /> },
    { key: 'sandbox', label: 'Sandbox', icon: <SparkInternetLine /> },
    { key: 'channel', label: 'Channel', icon: <SparkLinkLine /> },
    { key: 'mcp', label: 'MCP', icon: <SparkMagicWandLine /> },
    ...(isAdmin ? [{ key: 'history' as Section, label: '会话历史', icon: <SparkHistoryLine /> }] : []),
  ];
  /** 新建态只开放 Basic Info：其余分区依赖已存在的 Agent 记录（头像上传、Skill 绑定、连接记录…） */
  const menuItems = isNew ? allMenuItems.filter((m) => m.key === 'basic') : allMenuItems;
  /** 所属空间下拉（与 Agents 列表同一份口径）：'personal' = 个人空间，首个 Agent 创建时才落库 */
  const spaceOptions = [
    { value: 'personal', label: '个人空间' },
    ...workspaces
      .filter((w) => w.id !== null && w.id !== undefined)
      .map((w) => ({ value: String(w.id), label: w.workspaceName })),
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
            {(isNew ? '新建' : detail?.name || agentKey).charAt(0).toUpperCase()}
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
            {isNew ? '新建 Agent' : detail?.name || agentKey}
          </div>
          <div
            style={{
              fontFamily: 'Menlo, Consolas, monospace',
              fontSize: isHistory ? 10 : 11,
              color: 'rgba(26, 26, 29, 0.45)',
            }}
          >
            {isNew ? '尚未创建' : agentKey}
          </div>
        </div>
        <div style={{ flex: 1 }} />
        {(section === 'basic' || section === 'tools' || section === 'a2ui' || section === 'guard' || section === 'multiagent' || section === 'memory' || section === 'middleware' || section === 'sandbox' || section === 'channel') && (
          <Button type="primary" onClick={onSave} loading={saving}>
            {isNew ? '创建' : 'Save'}
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
                  </div>

                  {/* 基本信息 */}
                  <div style={{ flex: 1, minWidth: 240 }}>
                    <div style={{ fontSize: 24, fontWeight: 700, color: 'rgba(26, 26, 29, 0.92)' }}>
                      {detail?.name || agentKey}
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
                  {isNew ? (
                    <>
                      <Form.Item
                        name="agentKey"
                        label="Agent 标识"
                        rules={[
                          { required: true, message: '请输入标识' },
                          {
                            pattern: /^[a-z][a-z0-9-]{1,63}$/,
                            message: '小写字母开头，仅含小写字母/数字/短横线，2-64 位',
                          },
                        ]}
                        extra="创建后不可修改"
                      >
                        <Input placeholder="如 general-assistant" />
                      </Form.Item>
                      <Form.Item name="workspaceId" label="所属空间">
                        <Select options={spaceOptions} />
                      </Form.Item>
                    </>
                  ) : (
                    <Form.Item label="Agent ID">
                      <Input value={agentKey} disabled />
                    </Form.Item>
                  )}
                  <Form.Item name="name" label="Name" rules={[{ required: true }]}>
                    <Input placeholder="Agent 显示名称" />
                  </Form.Item>
                  <Form.Item name="description" label="Description">
                    <Input.TextArea rows={2} />
                  </Form.Item>
                  <Form.Item name="sysPrompt" label="Persona (System Prompt)">
                    <Input.TextArea rows={10} style={{ fontFamily: 'Menlo, Consolas, monospace' }} placeholder="You are a helpful assistant..." />
                  </Form.Item>
                  <Form.Item name="modelSource" label="模型来源" tooltip="二选一：用空间开放给你的模型，或自带 API Key 自定义一个模型（一个 Agent 只允许一个自带模型）。选自定义后主模型就是它，压缩/守卫/记忆/标题等子模型仍可在两个来源里各选。">
                    <Radio.Group
                      optionType="button"
                      buttonStyle="solid"
                      onChange={(e) => onModelSourceChange(e.target.value as 'system' | 'custom')}
                    >
                      <Radio value="system">空间模型</Radio>
                      <Radio value="custom">自定义模型</Radio>
                    </Radio.Group>
                  </Form.Item>
                  <Form.Item name="modelId" label="Model" rules={[{ required: true }]}>
                    <Select
                      options={modelOptions}
                      disabled={isCustomModel}
                      placeholder={isCustomModel ? '由下方自定义模型的「模型名」决定' : 'e.g. qwen-max'}
                    />
                  </Form.Item>
                  {/* 自定义模型（feature.customModels，SPEC §29）：自带 API Key，不受空间授权限制；
                      密钥加密存储、出参只回 hasApiKey，留空即沿用已存密钥 */}
                  {isCustomModel ? (
                    <div
                      style={{
                        border: '1px solid rgba(26, 26, 29, 0.12)',
                        borderRadius: 12,
                        padding: '10px 12px 0',
                        marginBottom: 24,
                      }}
                    >
                      <Row gutter={8}>
                        <Col xs={12} sm={6}>
                          <Form.Item
                            name={['customModels', 0, 'provider']}
                            label="供应商"
                            rules={[{ required: true, message: '必选' }]}
                          >
                            <Select options={providerOptions} />
                          </Form.Item>
                        </Col>
                        <Col xs={12} sm={6}>
                          <Form.Item
                            name={['customModels', 0, 'modelName']}
                            label="模型名"
                            rules={[{ required: true, message: '必填' }]}
                          >
                            <Input placeholder="qwen3.8-flash" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} sm={12}>
                          <Form.Item name={['customModels', 0, 'displayName']} label="展示名（可选）">
                            <Input placeholder="下拉里显示的名称" />
                          </Form.Item>
                        </Col>
                      </Row>
                      <Row gutter={8}>
                        <Col xs={24} sm={12}>
                          <Form.Item
                            name={['customModels', 0, 'apiKey']}
                            label="API Key"
                            rules={[{
                              validator: (_, value: string | undefined) =>
                                value?.trim() || customModel?.hasApiKey
                                  ? Promise.resolve()
                                  : Promise.reject(new Error('请填写 API Key')),
                            }]}
                          >
                            <Input.Password
                              placeholder={customModel?.hasApiKey ? '已配置，留空不修改' : 'sk-...'}
                              autoComplete="new-password"
                            />
                          </Form.Item>
                        </Col>
                        <Col xs={24} sm={12}>
                          <Form.Item name={['customModels', 0, 'baseUrl']} label="baseUrl（OpenAI 兼容网关，可选）">
                            <Input placeholder="https://api.example.com/v1" />
                          </Form.Item>
                        </Col>
                      </Row>
                      <Form.Item
                        name={['customModels', 0, 'capabilities']}
                        label="多模态能力"
                        tooltip="勾选后对话台开放对应附件上传；DashScope 会改走 multimodal-generation 端点"
                      >
                        <Select
                          mode="multiple"
                          options={[{ label: '图片（image）', value: 'image' }, { label: '视频（video）', value: 'video' }]}
                          placeholder="纯文本（默认）"
                        />
                      </Form.Item>
                    </div>
                  ) : null}
                  <Form.Item
                    name={['runtime', 'thinkingMode']}
                    label="Thinking Mode"
                    tooltip="三态（仅 DashScope 生效，OpenAI 供应商忽略）：默认=不下发 enableThinking，混合思考模型跟随服务端默认（多为开启）；开启/关闭则显式指定。"
                  >
                    <Select
                      style={{ width: 200 }}
                      options={thinkingOptions}
                    />
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
                      message="尚无 OSS 连接记录，当前仅可使用 Base64 内联"
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
                    tooltip="开启后按下方口径自动压缩历史摘要；关闭则完整保留全部历史（可能超出模型上下文）"
                  >
                    <Switch />
                  </Form.Item>
                  {compactionEnabled && (
                    <>
                    <Row gutter={16}>
                      <Col xs={24} sm={12}>
                        <Form.Item
                          name={['compaction', 'triggerMode']}
                          label="触发口径"
                          tooltip="按条数只是把下面的「触发轮数」当主输入，token 侧仍按窗口动态算 —— 两个触发器先到先算，窗口预算那条关不掉，长会话里往往是它先触发"
                        >
                          <Select
                            options={[
                              { value: 'dynamic', label: '动态（窗口预算）' },
                              { value: 'messages', label: '按条数' },
                              { value: 'tokens', label: '固定 token 阈值' },
                            ]}
                          />
                        </Form.Item>
                      </Col>
                      <Col xs={24} sm={12}>
                        <Form.Item
                          name={['compaction', 'keepMode']}
                          label="尾部保留"
                          tooltip="动态=按模型上下文窗口预算保留最近尾部（框架默认，此时「压缩保留轮数」不参与）；按条数=固定保留 N 条；按 token=固定保留 N tokens"
                        >
                          <Select
                            options={[
                              { value: 'dynamic', label: '动态（窗口预算）' },
                              { value: 'messages', label: '按条数' },
                              { value: 'tokens', label: '按 token' },
                            ]}
                          />
                        </Form.Item>
                      </Col>
                    </Row>
                    <Row gutter={16}>
                      <Col xs={24} sm={12}>
                        {compactionTriggerMode === 'tokens' ? (
                          <Form.Item
                            name={['compaction', 'triggerTokens']}
                            label="触发 token 阈值"
                            tooltip="估算 token 超过该值即触发压缩；与窗口预算取较小者，所以填得比窗口还大等于回到动态"
                            rules={[{ required: true, message: '固定 token 阈值时必须填写' }]}
                          >
                            <InputNumber min={1000} max={2000000} step={1000} style={{ width: '100%' }} />
                          </Form.Item>
                        ) : compactionTriggerMode === 'messages' ? (
                          <Form.Item
                            name="compactionTrigger"
                            label="触发轮数"
                            tooltip="消息条数达到即触发；窗口预算那条仍在背后先到先算，长会话可能它先到。0 请在其他口径下填（本口径下会被后端拒）"
                          >
                            <InputNumber min={1} max={200} placeholder="默认 30" style={{ width: '100%' }} />
                          </Form.Item>
                        ) : (
                          <Form.Item
                            name={['compaction', 'reserved']}
                            label="预缓冲 token"
                            tooltip="从模型窗口里扣掉、留给摘要调用本身的 token（框架默认 20000）；扣得越多越早触发"
                          >
                            <InputNumber
                              min={1}
                              max={200000}
                              step={1000}
                              placeholder="默认 20000"
                              style={{ width: '100%' }}
                            />
                          </Form.Item>
                        )}
                      </Col>
                      <Col xs={24} sm={12}>
                        {compactionKeepMode === 'messages' && (
                          <Form.Item
                            name="compactionKeep"
                            label="压缩保留轮数"
                            tooltip="压缩后保留的最近轮数原文，更早消息被摘要替代"
                          >
                            <InputNumber min={0} max={100} style={{ width: '100%' }} />
                          </Form.Item>
                        )}
                        {compactionKeepMode === 'tokens' && (
                          <Form.Item
                            name={['compaction', 'keepTokens']}
                            label="尾部 token 预算"
                            tooltip="从最近消息往前扫到该预算耗尽为止"
                            rules={[{ required: true, message: '按 token 保留时必须填写' }]}
                          >
                            <InputNumber min={256} max={200000} step={256} style={{ width: '100%' }} />
                          </Form.Item>
                        )}
                      </Col>
                    </Row>
                    {/* 按条数口径下这个框已经搬到左格（同名 Form.Item 不能同时挂两处） */}
                    {compactionTriggerMode !== 'messages' && (
                      <Form.Item
                        name="compactionTrigger"
                        label="触发轮数"
                        tooltip="条数是与 token 口径并列的独立触发器，谁先到算谁；0 = 不按条数触发（留空 = 默认 30）"
                      >
                        <InputNumber
                          min={0}
                          max={200}
                          placeholder="默认 30；0 = 不按条数"
                          style={{ width: 280 }}
                        />
                      </Form.Item>
                    )}
                    <Form.Item
                      name={['compaction', 'modelId']}
                      label="压缩摘要模型"
                      tooltip="上下文压缩摘要使用的模型，留空跟随 Agent 主模型"
                    >
                      <Select
                        allowClear
                        placeholder="跟随 Agent 主模型"
                        options={modelOptions}
                      />
                    </Form.Item>
                    <Row gutter={16}>
                      <Col xs={24} sm={12}>
                        <Form.Item
                          name={['compaction', 'prune', 'enabled']}
                          label="工具结果裁剪"
                          valuePropName="checked"
                          tooltip="把较早的工具输出聚合裁剪成 head+tail 预览（框架默认：保护最近 40k tokens、可裁总量超 20k 才动手、每条留 2k 字符）"
                        >
                          <Switch />
                        </Form.Item>
                      </Col>
                      <Col xs={24} sm={12}>
                        <Form.Item
                          name={['compaction', 'eviction', 'enabled']}
                          label="超大输出落盘"
                          valuePropName="checked"
                          tooltip="单条工具输出超阈值（默认 80k 字符）时写入沙箱工作区文件，上下文里换成占位 + 用 readFile 取全文"
                        >
                          <Switch />
                        </Form.Item>
                      </Col>
                    </Row>
                    </>
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
                    <Col xs={24} sm={12}>
                      <Form.Item
                        name={['runtime', 'permissionMode']}
                        label="权限模式"
                        tooltip="AgentScope 权限系统：只读探索 = 仅放行只读工具；阻止危险命令 = 拦截 rm -rf / dd 等高危 shell 命令；全部放行 = 不询问直接执行。留空 = 不设置权限上下文。chat 面板的同名开关优先级更高"
                      >
                        <Select
                          allowClear
                          placeholder="默认（不设置）"
                          options={[
                            { value: 'EXPLORE', label: '只读探索' },
                            { value: 'BLOCK_DANGEROUS', label: '阻止危险命令' },
                            { value: 'BYPASS', label: '全部放行' },
                          ]}
                        />
                      </Form.Item>
                    </Col>
                  </Row>
                  <Form.Item
                    name={['runtime', 'allowedTools']}
                    label="Allowed Tools"
                    tooltip="工具白名单（回车/逗号分隔录入），留空 = 不限制；注意：若配置白名单需包含 upload_file / download_file / list_mcp_servers / get_mcp_server 才能生效"
                  >
                    <Select mode="tags" placeholder="留空不限制" tokenSeparators={[',', ' ']} />
                  </Form.Item>
                  <Row gutter={16}>
                    <Col xs={24} sm={12}>
                      <Form.Item
                        name={['runtime', 'enableOssFile']}
                        label="OSS 文件上传下载"
                        valuePropName="checked"
                        tooltip="开启后 Agent 获得 upload_file / download_file 工具，可将工作区文件上传到 OSS 换取直链、或下载外部文件到工作区（凭证用系统配置 - 存储的全局激活记录）"
                      >
                        <Switch />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={12}>
                      <Form.Item
                        name={['runtime', 'enableMcpConfig']}
                        label="MCP 配置查询"
                        valuePropName="checked"
                        tooltip="开启后 Agent 获得 list_mcp_servers / get_mcp_server 只读工具，可向用户说明自身 MCP 配置（凭据字段不回显值）"
                      >
                        <Switch />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={12}>
                      <Form.Item
                        name={['runtime', 'enableMediaGen']}
                        label="生图/生视频"
                        valuePropName="checked"
                        tooltip="开启后 Agent 获得 DashScope 文生图/文生视频/图生视频等工具（密钥取服务端 DASHSCOPE_API_KEY，未配置时不生效）；视频生成耗时可达分钟级；开启后可指定各能力的生成模型"
                      >
                        <Switch />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={12}>
                      <Form.Item
                        name={['runtime', 'enablePendingToolRecovery']}
                        label="Pending Tool 恢复"
                        valuePropName="checked"
                        tooltip="开启后恢复运行时，孤儿 pending tool call（挂起工具的重启/断链场景）会被自动补一条合成错误结果，Agent 可继续对话而非直接报错；关闭 = SDK 默认（不恢复）"
                      >
                        <Switch />
                      </Form.Item>
                    </Col>
                  </Row>
                  {/* 生成模型指定（SPEC-media-gen §4.8）：开关打开才展开，六个能力位各自可选 */}
                  {!!mediaGenEnabled && mediaCatalog.length > 0 && (
                    <>
                      <Alert
                        type="info"
                        showIcon
                        style={{ marginBottom: 12 }}
                        message="留空即跟随工具默认模型；指定后服务端按此覆写生成请求"
                      />
                      <Row gutter={16}>
                        {mediaCatalog.map((entry) => (
                          <Col key={entry.field} xs={24} sm={12}>
                            <Form.Item
                              name={['runtime', 'mediaModels', entry.field]}
                              label={`${entry.label}模型`}
                              tooltip={`对应工具 ${entry.tool}；默认 ${entry.defaultModel}。候选为实测可用型号，也可填入清单外的在售型号`}
                            >
                              <AutoComplete
                                allowClear
                                placeholder={`默认：${entry.defaultModel}`}
                                options={entry.models.map((m) => ({ value: m }))}
                                filterOption={(input, option) =>
                                  (option?.value ?? '').toLowerCase().includes(input.trim().toLowerCase())
                                }
                              />
                            </Form.Item>
                          </Col>
                        ))}
                      </Row>
                    </>
                  )}
                  {!!mediaGenEnabled && mediaCatalog.length === 0 && (
                    <Alert
                      type="warning"
                      showIcon
                      message="生成模型目录加载失败，本次仅能使用各工具默认模型；可刷新重试"
                    />
                  )}
                </Form>
              </div>
            )}

            {/* A2UI 界面生成（feature.a2ui，AgentScope a2ui 扩展）：开启即挂 A2uiMiddleware */}
            {section === 'a2ui' && (
              <div className="glass-card" style={{ padding: 24 }}>
                <Form form={form} layout="vertical">
                  <Form.Item
                    name={['a2ui', 'enabled']}
                    label="A2UI 界面生成"
                    valuePropName="checked"
                    tooltip="开启后主 Agent 获得 a2ui_render / ask_user_question（表单档）工具，可在对话中渲染表单、卡片、选项等交互界面（组件目录与组件树由 SDK 内部渲染子 Agent 持有）；关闭时 ask_user_question 仍可用作纯文本问题卡（框架内置澄清能力）；关闭 = 存量行为"
                  >
                    <Switch />
                  </Form.Item>
                  {!!a2uiEnabled && (
                    <>
                      <Row gutter={16}>
                        <Col xs={24} sm={12}>
                          <Form.Item
                            name={['a2ui', 'catalogId']}
                            label="Catalog ID"
                            tooltip="信封内声明的组件目录标识，留空 = agentscope.io:a2ui/basic"
                          >
                            <Input allowClear placeholder="agentscope.io:a2ui/basic" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} sm={12}>
                          <Form.Item
                            name={['a2ui', 'catalogResource']}
                            label="Catalog 资源路径"
                            tooltip="组件目录 JSON 在服务端 classpath 的路径，留空 = 扩展内置 a2ui/basic-catalog.json；自定义目录需将文件放入服务端 resources，保存时校验存在性"
                          >
                            <Input allowClear placeholder="a2ui/basic-catalog.json" />
                          </Form.Item>
                        </Col>
                      </Row>
                      <Row gutter={16}>
                        <Col xs={24} sm={8}>
                          <Form.Item
                            name={['a2ui', 'surfacePersistEnabled']}
                            label="界面状态跨轮持久化"
                            valuePropName="checked"
                            tooltip="开启后 surface 状态写入会话状态存储，多轮可持续更新同一界面；关闭则界面状态仅存活于单轮"
                          >
                            <Switch />
                          </Form.Item>
                        </Col>
                        <Col xs={24} sm={8}>
                          <Form.Item
                            name={['a2ui', 'maxComponents']}
                            label="单信封组件数上限"
                            tooltip="1–500，留空 = 50"
                          >
                            <InputNumber min={1} max={500} style={{ width: '100%' }} placeholder="50" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} sm={8}>
                          <Form.Item
                            name={['a2ui', 'renderNoThinking']}
                            label="渲染子模型关闭思考"
                            valuePropName="checked"
                            tooltip="渲染子 Agent 输出结构化组件树，思考只会拖慢渲染：开启后子模型显式下发 enable_thinking=false（qwen3 混合思考模型不传参数时服务端默认开启思考）；关闭 = 子模型跟随主模型实例，思考随 Agent 级配置"
                          >
                            <Switch />
                          </Form.Item>
                        </Col>
                      </Row>
                      <Form.Item
                        name={['a2ui', 'appendPreference']}
                        label="用户界面偏好（一行一条，可选）"
                        tooltip="追加进主模型系统提示词与渲染子 Agent 提示词的常驻偏好（如「界面文案用中文」「表单紧凑一点」）；每次画界面都会带上，留空 = 不追加"
                      >
                        <Input.TextArea
                          rows={3}
                          placeholder={'界面文案用中文\n表单字段紧凑排列'}
                          style={{ fontFamily: 'monospace' }}
                        />
                      </Form.Item>
                    </>
                  )}
                </Form>
              </div>
            )}

            {section === 'guard' && (
              <div className="glass-card" style={{ padding: 24 }}>
                <Form form={form} layout="vertical">
                  <Form.Item
                    name={['guard', 'enabled']}
                    label="启用意图守卫"
                    valuePropName="checked"
                    tooltip="开启后每条用户请求先按规则判定是否允许（JEV 决策模型或大模型判定，见下）；未通过时主模型看不到该请求，直接返回拒答文案。至少填写一条接受/拒绝意图规则才会挂载"
                  >
                    <Switch />
                  </Form.Item>
                  {!!guardEnabled && (
                    <>
                      <Row gutter={16}>
                        <Col xs={24} sm={12}>
                          <Form.Item
                            name={['guard', 'mode']}
                            label="拦截方式"
                            tooltip="jev=TypeSafe JEV 决策模型（概率判定，需服务端 TYPESAFE_API_KEY，缺密钥跳过挂载）；llm=用一个大模型按规则做 ACCEPT/REJECT 判定，无需额外密钥"
                          >
                            <Select
                              options={[
                                { value: 'jev', label: 'JEV 决策模型（默认）' },
                                { value: 'llm', label: '大模型判定' },
                              ]}
                            />
                          </Form.Item>
                        </Col>
                        {guardMode === 'llm' && (
                          <Col xs={24} sm={12}>
                            <Form.Item
                              name={['guard', 'modelId']}
                              label="判定模型"
                              tooltip="意图判定使用的模型，留空跟随 Agent 主模型（建议选轻量快模型，判定会增加每轮首字延迟）"
                            >
                              <Select
                                allowClear
                                placeholder="跟随 Agent 主模型"
                                options={modelOptions}
                              />
                            </Form.Item>
                          </Col>
                        )}
                      </Row>
                      <Row gutter={16}>
                        <Col xs={24} sm={12}>
                          <Form.Item
                            name={['guard', 'acceptText']}
                            label="接受的意图（一行一条）"
                            tooltip="白名单意图描述，例如「编程问答」「查询订单状态」。填写后未命中任何接受意图的请求会被拒绝"
                          >
                            <Input.TextArea
                              rows={6}
                              placeholder={'编程问答\n查询订单状态'}
                              style={{ fontFamily: 'monospace' }}
                            />
                          </Form.Item>
                        </Col>
                        <Col xs={24} sm={12}>
                          <Form.Item
                            name={['guard', 'rejectText']}
                            label="拒绝的意图（一行一条）"
                            tooltip="黑名单意图描述，例如「索要他人隐私」「生成违法内容」。命中任一拒绝意图的请求会被直接拒答"
                          >
                            <Input.TextArea
                              rows={6}
                              placeholder={'索要他人隐私\n生成违法内容'}
                              style={{ fontFamily: 'monospace' }}
                            />
                          </Form.Item>
                        </Col>
                      </Row>
                      <Row gutter={16}>
                        {guardMode !== 'llm' && (
                          <Col xs={24} sm={8}>
                            <Form.Item
                              name={['guard', 'allowThreshold']}
                              label="放行阈值"
                              tooltip="JEV 给出「请求被允许」的概率低于该值即拒绝，0–1，留空 = 0.5（仅 JEV 模式）"
                            >
                              <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} placeholder="0.5" />
                            </Form.Item>
                          </Col>
                        )}
                        <Col xs={24} sm={guardMode === 'llm' ? 24 : 16}>
                          <Form.Item
                            name={['guard', 'denyMessage']}
                            label="拒答文案"
                            tooltip="被拒绝时回复给用户的内容，留空 = 内置默认文案（JEV 模式为英文，LLM 模式为中文）"
                          >
                            <Input allowClear placeholder="抱歉，该请求不符合本 Agent 的使用范围。" />
                          </Form.Item>
                        </Col>
                      </Row>
                    </>
                  )}
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
                </Form>
              </div>
            )}

            {section === 'memory' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
              <div className="glass-card" style={{ padding: 24 }}>
                <Form form={form} layout="vertical">
                  <Form.Item
                    name={['memory', 'enabled']}
                    label="启用记忆"
                    valuePropName="checked"
                    tooltip="开启后对话自动沉淀长期记忆并在后续会话注入；关闭则停用记忆落盘与 memory_search 等记忆工具。对话界面可按请求临时覆盖，请求级开关优先于本配置"
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
                  <Form.Item
                    name={['memory', 'modelId']}
                    label="记忆摘要模型"
                    tooltip="记忆 flush / consolidation 使用的模型，留空跟随 Agent 主模型"
                  >
                    <Select
                      allowClear
                      placeholder="跟随 Agent 主模型"
                      options={modelOptions}
                    />
                  </Form.Item>
                  <Form.Item
                    name={['sessionTitle', 'enabled']}
                    label="异步会话标题生成"
                    valuePropName="checked"
                    tooltip="开启后首次对话时异步调 LLM 总结标题，关闭则标题保持 Agent 名称"
                  >
                    <Switch />
                  </Form.Item>
                  <Form.Item
                    name={['sessionTitle', 'modelId']}
                    label="标题生成模型"
                    tooltip="标题生成专用模型，留空跟随 Agent 主模型"
                  >
                    <Select
                      allowClear
                      placeholder="跟随 Agent 主模型"
                      options={modelOptions}
                    />
                  </Form.Item>
                </Form>
              </div>

              {/* Redis 记忆内容（SPEC §27 记忆管理）：按 uid 分组查询，支持逐条删除 */}
              <div className="glass-card" style={{ padding: 24 }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16, flexWrap: 'wrap', gap: 8 }}>
                  <div style={{ fontSize: 17, fontWeight: 700, color: 'rgba(26, 26, 29, 0.92)' }}>记忆内容（Redis）</div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <Select
                      allowClear
                      showSearch
                      style={{ minWidth: 160 }}
                      placeholder="全部用户"
                      value={memUidFilter || undefined}
                      onChange={(v) => setMemUidFilter(v ?? '')}
                      options={memGroups.map((g) => ({ label: g.uid, value: g.uid }))}
                    />
                    <Button onClick={loadMemory} loading={memLoading}>刷新</Button>
                  </div>
                </div>
                {memLoading && memGroups.length === 0 ? (
                  <div style={{ textAlign: 'center', padding: 24 }}><Spin /></div>
                ) : memGroups.length === 0 ? (
                  <Empty description="暂无记忆内容（该 Agent 尚未沉淀记忆，或未启用 Redis 记忆存储）" />
                ) : (
                  <Collapse
                    items={memGroups
                      .filter((g) => !memUidFilter || g.uid === memUidFilter)
                      .map((g) => ({
                      key: g.uid,
                      label: (
                        <span>
                          <span style={{ fontFamily: 'Menlo, Consolas, monospace' }}>{g.uid}</span>
                          <span style={{ marginLeft: 8, fontSize: 12, color: 'rgba(26, 26, 29, 0.45)' }}>{g.files.length} 条</span>
                        </span>
                      ),
                      children: (
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                          {g.files.map((f) => (
                            <div key={f.path} style={{ border: '1px solid rgba(0, 0, 0, 0.06)', borderRadius: 8, padding: '8px 12px' }}>
                              <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                                <span style={{ fontWeight: 600, fontSize: 13, fontFamily: 'Menlo, Consolas, monospace' }}>{f.path}</span>
                                {f.modifiedAt && (
                                  <span style={{ fontSize: 11, color: 'rgba(26, 26, 29, 0.45)' }}>更新于 {f.modifiedAt}</span>
                                )}
                                <span style={{ flex: 1 }} />
                                <Popconfirm title={`确认删除记忆 ${f.path}？删除后不可恢复`} onConfirm={() => onDeleteMemoryItem(g.uid, f.path)}>
                                  <Button size="small" danger>删除</Button>
                                </Popconfirm>
                              </div>
                              {f.content != null && (
                                <pre style={{ margin: '8px 0 0', fontSize: 12, lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 240, overflow: 'auto', background: 'rgba(0, 0, 0, 0.03)', borderRadius: 6, padding: 8 }}>
                                  {f.content || '（空文件）'}
                                </pre>
                              )}
                            </div>
                          ))}
                        </div>
                      ),
                    }))}
                  />
                )}
              </div>
              </div>
            )}

            {section === 'middleware' && (
              <MiddlewareConfigSection
                views={mwViews}
                attached={mwAttached}
                values={mwValues}
                onAttach={(key) => setMwAttached((s) => ({ ...s, [key]: true }))}
                onDetach={(key) => setMwAttached((s) => ({ ...s, [key]: false }))}
                onValues={setMwValues}
              />
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
                      <Switch />
                    </Form.Item>
                    {sbEnabled && (
                      <>
                        {/* 承载来源二选一（SPEC §29.17）：系统记录 或 自带 E2B 凭证，不混用。
                            sandboxSource 是纯 UI 字段，保存时从 feature.sandbox 里剥掉 */}
                        <Form.Item name={['sandbox', 'source']} label="沙箱承载" tooltip="系统记录=用平台在系统配置里维护的沙箱；自带 E2B 凭证=这个 Agent 用它自己的沙箱账号（密钥加密存储、不回显，整套字段自带、不与记录混用）">
                          <Radio.Group
                            optionType="button"
                            buttonStyle="solid"
                            onChange={(e) => onSandboxSourceChange(e.target.value as 'record' | 'custom')}
                          >
                            <Radio value="record">系统记录</Radio>
                            <Radio value="custom">自带 E2B 凭证</Radio>
                          </Radio.Group>
                        </Form.Item>
                        <Row gutter={16}>
                          {sbCustom ? (
                            <>
                              <Col xs={24} sm={8}>
                                <Form.Item
                                  name={['sandbox', 'e2bApiKey']}
                                  label="E2B API Key"
                                  rules={[{
                                    validator: (_, value: string | undefined) =>
                                      value?.trim() || sbCfg?.hasE2bApiKey
                                        ? Promise.resolve()
                                        : Promise.reject(new Error('请填写 E2B API Key')),
                                  }]}
                                >
                                  <Input.Password
                                    placeholder={sbCfg?.hasE2bApiKey ? '已配置，留空不修改' : 'e2b_xxx'}
                                    autoComplete="new-password"
                                  />
                                </Form.Item>
                              </Col>
                              <Col xs={24} sm={8}>
                                <Form.Item
                                  name={['sandbox', 'e2bApiBaseUrl']}
                                  label="控制面地址"
                                  rules={[{ required: true, message: '必填' }]}
                                >
                                  <Input placeholder="https://api.xxx/sandboxes" />
                                </Form.Item>
                              </Col>
                              <Col xs={24} sm={8}>
                                <Form.Item
                                  name={['sandbox', 'e2bDomain']}
                                  label="沙箱域名"
                                  rules={[{ required: true, message: '必填' }]}
                                >
                                  <Input placeholder="xxx.e2b.dev" />
                                </Form.Item>
                              </Col>
                            </>
                          ) : (
                            <Col xs={24} sm={12}>
                              <Form.Item
                                name={['sandbox', 'sandboxRecord']}
                                label="沙箱承载记录"
                                tooltip="链路由记录的 linkType 决定，记录在系统配置 - 沙箱中维护（§22.2）"
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
                          )}
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
                            <Form.Item name={['sandbox', 'persistence']} label="持久化" tooltip={sbCustom ? '自带沙箱不支持 NAS 挂载（挂载点由服务端配置决定）' : undefined}>
                              <Select
                                options={[
                                  { value: 'LOCAL_SNAPSHOT', label: '本地快照（推荐）' },
                                  { value: 'NONE', label: '不持久化' },
                                  ...(sbCustom ? [] : [{ value: 'NAS', label: 'NAS 挂载' }]),
                                ]}
                              />
                            </Form.Item>
                          </Col>
                        </Row>
                        <Row gutter={16}>
                          <Col xs={24} sm={12}>
                            <Form.Item
                              name={['sandbox', 'templateName']}
                              label="沙箱模板"
                              tooltip={sbCustom ? '自带凭证必须指定该账号下的模板，不回落平台默认' : '留空用全局默认模板'}
                              rules={sbCustom ? [{ required: true, message: '自带沙箱必须指定模板' }] : undefined}
                            >
                              <Input placeholder={sbCustom ? '该 E2B 账号下的模板名' : sbOptions?.defaultTemplate || '全局默认模板'} />
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
                  </div>
                )}
              </div>
            )}

            {section === 'mcp' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                <div className="glass-card" style={{ padding: 24 }}>
                  <Form form={form} layout="vertical">
                    <Form.Item
                      name={['mcp', 'enabled']}
                      label="启用 MCP 工具"
                      valuePropName="checked"
                      tooltip="启用后 Agent 可调用 MCP Server 提供的工具能力，支持引用系统记录或自定义配置"
                    >
                      <Switch />
                    </Form.Item>
                  </Form>
                  {mcpEnabled && (
                    <>
                      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}>
                        <span style={{ fontSize: 14, fontWeight: 600 }}>MCP Server 列表</span>
                        <div style={{ flex: 1 }} />
                        <Button size="small" icon={<SparkPlusLine />} onClick={() => openMcpModal()}>
                          添加 Server
                        </Button>
                      </div>
                      {(mcpServers ?? []).length === 0 && (
                        <div style={{ fontSize: 12, color: 'rgba(26,26,29,0.45)', padding: '8px 0' }}>
                          暂无 MCP Server，点击「添加 Server」新增配置
                        </div>
                      )}
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                        {(mcpServers ?? []).map((srv, idx) => (
                          <div
                            key={idx}
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              gap: 10,
                              padding: '10px 14px',
                              borderRadius: 10,
                              border: '1px solid rgba(26,26,29,0.08)',
                              background: 'rgba(255,255,255,0.5)',
                            }}
                          >
                            <Tag color={srv.record ? 'blue' : 'green'} style={{ margin: 0 }}>
                              {srv.record ? '系统记录' : '自定义'}
                            </Tag>
                            <div style={{ minWidth: 0, flex: 1 }}>
                              <div style={{ fontSize: 13, fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                {srv.record || (srv.transport === 'stdio' ? srv.command : srv.url) || '-'}
                              </div>
                              {srv.description && (
                                <div style={{ fontSize: 12, color: 'rgba(26,26,29,0.45)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                  {srv.description}
                                </div>
                              )}
                            </div>
                            <a onClick={() => openMcpModal(idx)}>编辑</a>
                            <Popconfirm
                              title="删除这条 MCP Server 配置？"
                              onConfirm={() => {
                                const cur = [...((form.getFieldValue(['mcp', 'mcpServers']) as AgentMCPServer[]) ?? [])];
                                cur.splice(idx, 1);
                                form.setFieldsValue({ mcp: { ...form.getFieldValue('mcp'), mcpServers: cur } });
                              }}
                            >
                              <a style={{ color: '#cf1322' }}>删除</a>
                            </Popconfirm>
                          </div>
                        ))}
                      </div>
                    </>
                  )}
                </div>

                {/* 新增/编辑 MCP Server 弹窗：复用系统配置 MCP 弹窗表单（§MCP） */}
                <ResponsiveModal
                  title={mcpModal?.mode === 'edit' ? '编辑 MCP Server' : '添加 MCP Server'}
                  open={mcpModal !== null}
                  onOk={onMcpModalOk}
                  onCancel={() => setMcpModal(null)}
                  destroyOnClose
                  width={620}
                >
                  <Form form={mcpModalForm} layout="vertical" style={{ marginTop: 12 }}>
                    <Form.Item name="mode" label="配置来源">
                      <Radio.Group>
                        <Radio.Button value="record">引用系统记录</Radio.Button>
                        <Radio.Button value="inline">自定义配置</Radio.Button>
                      </Radio.Group>
                    </Form.Item>
                    {mcpModalMode === 'record' ? (
                      <Form.Item
                        name="record"
                        label="系统记录"
                        rules={[{ required: true, message: '请选择 MCP Server 记录' }]}
                      >
                        <Select
                          placeholder="选择 MCP Server 记录"
                          options={mcpNames.map((r) => ({
                            value: r.name,
                            label: `${r.name}（${r.transport === 'stdio' ? '本地进程' : r.transport === 'streamable_http' ? 'Streamable HTTP' : 'SSE'}${r.description ? ' - ' + r.description : ''}）`,
                          }))}
                        />
                      </Form.Item>
                    ) : (
                      <MCPConfigFormFields />
                    )}
                  </Form>
                </ResponsiveModal>

                {isAdmin && mcpNames.length > 0 && (
                  <Button type="text" size="small" icon={<SparkSettingLine />} onClick={() => navigate('/system/mcp')}>
                    前往系统配置
                  </Button>
                )}
              </div>
            )}

            {section === 'skills' && (
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
                  <div style={{ fontSize: 20, fontWeight: 700, color: 'rgba(26, 26, 29, 0.92)' }}>Skills</div>
                  <div style={{ flex: 1 }} />
                  <AddSkillButton agentKey={agentKey} onAdded={load} />
                </div>

                {allSkills.length === 0 ? (
                  <div className="glass-card" style={{ padding: 32, textAlign: 'center', color: token.colorTextTertiary, fontSize: 13 }}>
                    还没有可绑定的 Skill，用右上角 Add Skill 导入（zip 上传或 Git 仓库）
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
                            {/* 本体是跨 Agent 共享资产（SPEC §29.10）：开关只解绑，
                                删本体要等没有任何 Agent 绑定时才允许（git 来源后端禁删，不给入口） */}
                            <div style={{ marginTop: 10, display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 8 }}>
                              <span style={{ fontSize: 11, color: 'rgba(26, 26, 29, 0.4)' }}>
                                {(s.boundAgents?.length ?? 0) > 0
                                  ? `${s.boundAgents!.length} 个 Agent 在用`
                                  : '未被任何 Agent 使用'}
                              </span>
                              {isAdmin && (s.boundAgents?.length ?? 0) === 0 && s.source !== 'git' && (
                                <Popconfirm
                                  title={`删除 Skill 本体「${s.name}」？删除后不可恢复。`}
                                  onConfirm={async () => {
                                    await skillDelete(s.name);
                                    message.success(`已删除 ${s.name}`);
                                    load();
                                  }}
                                >
                                  <Button size="small" danger icon={<SparkDeleteLine />}>
                                    删除本体
                                  </Button>
                                </Popconfirm>
                              )}
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
