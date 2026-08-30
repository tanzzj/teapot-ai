/** 后端统一响应（Result<T>） */
export interface Result<T> {
  code: number;
  message: string;
  data: T;
}

export interface PageData<T> {
  total: number;
  list: T[];
}

export interface TeapotUser {
  id?: number;
  userId: string;
  username: string;
  realName?: string;
  mobile?: string;
  email?: string;
  roles: string;
  status: number;
  /** 头像 OSS 直链（SPEC §23） */
  avatar?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  user: TeapotUser;
  usingDefaultPassword: boolean;
}

export interface Agent {
  id?: number;
  agentKey: string;
  name: string;
  description?: string;
  /** 头像 OSS 直链（SPEC §23） */
  avatar?: string;
  sysPrompt: string;
  modelId: string;
  compactionTrigger?: number;
  compactionKeep?: number;
  /** 扩展功能配置 JSON（一期仅 sandbox 命名空间，SPEC §16.6） */
  feature?: string;
  status: number;
  createdBy?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** feature.sandbox 结构（SPEC §16.6） */
export interface AgentSandboxNas {
  serverAddr: string;
  mountDir: string;
  remotePath?: string;
  enableTLS?: boolean;
}

export interface AgentSandboxConfig {
  enabled: boolean;
  /** 沙箱承载记录（SPEC §22.2）：引用沙箱连接记录名，链路由记录 linkType 决定 */
  sandboxRecord?: string;
  /** 沙箱链路（存量兼容，SPEC §21.4）：auto 跟随全局 / e2b / agentrun */
  link?: 'auto' | 'e2b' | 'agentrun';
  isolationScope?: 'SESSION' | 'USER' | 'AGENT' | 'GLOBAL';
  persistence?: 'NONE' | 'LOCAL_SNAPSHOT' | 'NAS';
  templateName?: string;
  workspaceRoot?: string;
  idleTimeoutSeconds?: number;
  nas?: AgentSandboxNas;
}

/** feature.runtime 结构：Agent 高级配置（Basic Info + Tool & Advanced），字段缺省 = 回落默认 */
export interface AgentRuntimeConfig {
  /** 思考模式（仅 DashScope 生效） */
  thinkingMode?: boolean;
  /** 采样温度 0–2 */
  temperature?: number;
  /** 核采样 0–1 */
  topP?: number;
  /** 最大生成 tokens */
  maxTokens?: number;
  /** 计划模式 */
  enablePlanMode?: boolean;
  /** shell 工具开关；未配置时跟随沙箱启用 */
  enableShell?: boolean;
  /** OSS 文件上传/下载工具开关（upload_file / download_file） */
  enableOssFile?: boolean;
  /** MCP 配置查询工具开关（list_mcp_servers / get_mcp_server） */
  enableMcpConfig?: boolean;
  /** 生图/生视频工具开关（DashScope，SPEC-media-gen §4.3） */
  enableMediaGen?: boolean;
  /** 权限模式（AgentScope permission system）：EXPLORE 只读探索 / BLOCK_DANGEROUS 阻止危险命令 / BYPASS 全部放行；未配置 = 不设权限上下文 */
  permissionMode?: 'EXPLORE' | 'BLOCK_DANGEROUS' | 'BYPASS';
  /** 工具白名单（空 = 不限制） */
  allowedTools?: string[];
  /** ReAct 最大迭代轮数 1–100；留空 = 默认 */
  maxIterations?: number;
}

/** feature.storage 结构（SPEC §22.1：图片存储载体按 Agent 选择） */
export interface AgentStorageConfig {
  /** base64（默认）/ oss（引用 OSS 连接记录） */
  mode: 'base64' | 'oss';
  /** mode=oss 时引用的 OSS 连接记录名 */
  storageRecord?: string;
}

/** feature.multiagent 结构（SPEC §25：MultiAgent/Subagent 开关，缺省该命名空间 = 启用） */
export interface AgentMultiAgentConfig {
  /** false = 禁用 subagent 与动态 subagent 生成 */
  enabled?: boolean;
}

/** feature.memory 结构（SPEC §25：记忆模式配置，缺省该命名空间 = 启用） */
export interface AgentMemoryConfig {
  /** false = 关闭记忆落盘钩子与 memory_search 等记忆工具 */
  enabled?: boolean;
  /** 记忆落盘策略：always（每轮，默认）/ never / throttled（节流） */
  flushTrigger?: 'always' | 'never' | 'throttled';
  /** 仅 flushTrigger=throttled 生效：落盘节流间隔（分钟，1–1440） */
  flushThrottleMinutes?: number;
}

/** feature.mcp 结构：Agent 级 MCP Server 配置（引用系统记录 或 内联完整配置，不依赖系统配置） */
export interface AgentMCPConfig {
  /** 是否启用 MCP 工具 */
  enabled: boolean;
  /** MCP Server 列表：每条可引用系统记录（record）或内联完整配置（transport + ...） */
  mcpServers?: AgentMCPServer[];
}

/** 单条 MCP Server 配置（record 与 inline 二选一） */
export interface AgentMCPServer {
  /** 引用 t_mcp_config 记录名（与 inline 配置二选一） */
  record?: string;
  /** 传输协议：stdio / streamable_http / sse（inline 必填） */
  transport?: MCPTransport;
  /** stdio 启动命令（transport=stdio 时必填） */
  command?: string;
  /** stdio 命令参数 */
  args?: string[];
  /** 环境变量 */
  env?: Record<string, string>;
  /** HTTP/SSE 远程 URL（transport=streamable_http/sse 时必填） */
  url?: string;
  /** HTTP 请求头 */
  headers?: Record<string, string>;
  /** 描述（仅展示用） */
  description?: string;
}

/** feature.channel 结构（SPEC §24.5：Agent 渠道连接器配置，可与 sandbox 共存） */
export interface AgentChannelConfig {
  enabled: boolean;
  /** 引用的渠道连接记录名（§24.4），enabled=true 必填 */
  channelRecord?: string;
  /** 会话隔离粒度：缺省 PER_CHANNEL_PEER */
  dmScope?: 'MAIN' | 'PER_PEER' | 'PER_CHANNEL_PEER';
}

/** 渠道连接记录（SPEC §24.4，GET /api/channel-config/list） */
export interface ChannelRecord {
  name: string;
  channelType: string;
  appKey?: string;
  robotCode?: string;
  remark?: string;
  /** 列表行不回明文，只回掩码 */
  appSecretMasked?: string;
  /** GitHub webhook secret 掩码（仅 github 类型，§24 修订） */
  webhookSecretMasked?: string;
  configured?: boolean;
  updatedAt?: string;
}

/** GET /api/channel-config/list 出参（§24.4） */
export interface ChannelListData {
  records: ChannelRecord[];
}

/** 渠道记录轻量名单行（GET /api/channel-config/registry，developer/viewer 可读） */
export interface ChannelRecordName {
  name: string;
  channelType: string;
}

/** 连接器测试连接结果（POST /api/channel-config/test，§24.10） */
export interface ChannelTestResult {
  success: boolean;
  message: string;
}

/** Agent 全量会话历史列表条目（SPEC §24.9，admin 视图） */
export interface SessionHistoryItem {
  /** web / dingtalk（后续渠道枚举） */
  source: string;
  /** 平台用户 / 渠道 peer 标识 */
  userId: string;
  sessionId: string;
  title?: string;
  lastActiveAt?: string;
}

/** 会话回放消息条目（agentscope_sessions 状态回放，按序渲染） */
export interface SessionMessageItem {
  role: string;
  /** text / image / reasoning / tool_call / tool_call_output */
  type: string;
  text?: string;
  toolCallId?: string;
  toolName?: string;
  arguments?: string;
  output?: string;
  imageUrl?: string;
}

/** GET /api/config/sandbox-options 出参（SPEC §16.11 / 修订：e2b·agentrun 双链路） */
export interface SandboxOptions {
  configured: boolean;
  /** E2B 兼容链路凭证是否齐备 */
  e2bConfigured?: boolean;
  /** 首选链路：e2b / agentrun（sandbox.link 配置项） */
  link?: string;
  e2bEnabled?: boolean;
  agentrunEnabled?: boolean;
  region?: string;
  defaultTemplate?: string;
  defaultWorkspaceRoot?: string;
  defaultIdleTimeoutSeconds?: number;
  apiKeyMasked?: string;
  accountIdMasked?: string;
  mcpServerUrl?: string;
  e2bApiKeyMasked?: string;
  e2bApiBaseUrl?: string;
  e2bDomain?: string;
  e2bDefaultTemplate?: string;
}

/** GET /api/config/storage-options 出参（SPEC §20.5） */
export interface StorageOptions {
  /** 管理员配置的策略：base64 | oss */
  strategy: 'base64' | 'oss';
  /** 生效策略（凭证不齐/开关关闭时回落 base64） */
  effectiveStrategy: 'base64' | 'oss';
  ossEnabled: boolean;
  ossConfigured: boolean;
  /** 激活的 OSS 连接记录名（§20.12 多记录） */
  active?: string | null;
  region?: string;
  bucket?: string;
  endpoint?: string;
  customDomain?: string;
  keyPrefix?: string;
  accessKeyIdMasked?: string;
  accessKeySecretMasked?: string;
}

/** OSS 连接记录（SPEC §20.12 多记录，GET /api/config/storage-list） */
export interface StorageRecord {
  name: string;
  region?: string;
  bucket?: string;
  endpoint?: string;
  customDomain?: string;
  keyPrefix?: string;
  remark?: string;
  /** 列表行不回明文，只回布尔 */
  accessKeyConfigured?: boolean;
  updatedAt?: string;
}

/** GET /api/config/storage-list 出参（§20.12） */
export interface StorageListData {
  active?: string | null;
  records: StorageRecord[];
}

/** 记录轻量名单行（§22：Agent 配置下拉选择用，仅名称类信息） */
export interface StorageRecordName {
  name: string;
  region?: string;
  bucket?: string;
}

export interface SandboxRecordName {
  name: string;
  linkType: 'e2b' | 'agentrun';
}

/** 沙箱连接记录（SPEC §22.2，GET /api/config/sandbox-list） */
export interface SandboxRecord {
  name: string;
  linkType: 'e2b' | 'agentrun';
  e2bApiBaseUrl?: string;
  e2bDomain?: string;
  e2bDefaultTemplate?: string;
  region?: string;
  defaultTemplate?: string;
  mcpServerUrl?: string;
  remark?: string;
  /** 列表行不回明文，只回布尔 */
  e2bConfigured?: boolean;
  agentrunConfigured?: boolean;
  updatedAt?: string;
}

/** GET /api/config/sandbox-list 出参（§22.2） */
export interface SandboxListData {
  records: SandboxRecord[];
}

export interface AgentDetail {
  agent: Agent;
  skillNames: string[];
}

export interface SkillListItem {
  name: string;
  description?: string;
  source?: string;
}

export interface SkillResourceItem {
  path: string;
  content: string;
}

export interface SkillDetail {
  name: string;
  description?: string;
  instructions?: string;
  source?: string;
  skillContent?: string;
  resources?: SkillResourceItem[];
}

export interface SkillSaveRequest {
  name: string;
  description: string;
  instructions: string;
  resources?: SkillResourceItem[];
}

/** GET /api/skill/git/status 出参（SPEC §15.9） */
export interface SkillGitStatus {
  enabled: boolean;
  remoteMasked?: string;
  branch?: string;
  skillCount: number;
  lastSyncAt?: string;
}

/** GET /api/skill/oss/status 出参：OSS skill 来源状态 */
export interface SkillOssStatus {
  enabled: boolean;
  bucket?: string;
  prefix?: string;
  skillCount: number;
  lastRefreshAt?: string;
}

/** POST /api/skill/import 出参 */
export interface SkillImportResult {
  target: 'oss' | 'mysql';
  imported: string[];
}

export interface ChatSession {
  id?: number;
  userId: string;
  agentKey: string;
  sessionId: string;
  title?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** Redis 记忆单条文件（SPEC §27 记忆管理，GET /api/agent/{agentKey}/memory-items） */
export interface MemoryFileItem {
  path: string;
  size: number;
  modifiedAt?: string;
  content?: string;
}

/** Redis 记忆按命名空间 uid 分组 */
export interface MemoryUserGroup {
  uid: string;
  files: MemoryFileItem[];
}

/* ---------------- MCP Server 配置（参考 QwenPaw MCP 配置模型） ---------------- */

/** MCP 传输协议 */
export type MCPTransport = 'stdio' | 'streamable_http' | 'sse';

/** MCP Server 配置记录（GET /api/mcp-config/list） */
export interface MCPRecord {
  name: string;
  transport: MCPTransport;
  command?: string;
  args?: string[];
  env?: Record<string, string>;
  url?: string;
  headers?: Record<string, string>;
  enabled: boolean;
  description?: string;
  remark?: string;
  updatedAt?: string;
}

/** GET /api/mcp-config/list 出参 */
export interface MCPListData {
  records: MCPRecord[];
}

/** MCP 记录轻量名单行（developer/viewer 可读，Agent 配置下拉用） */
export interface MCPRecordName {
  name: string;
  transport: MCPTransport;
  enabled: boolean;
  description?: string;
}
