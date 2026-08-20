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

/** feature.storage 结构（SPEC §22.1：图片存储载体按 Agent 选择） */
export interface AgentStorageConfig {
  /** base64（默认）/ oss（引用 OSS 连接记录） */
  mode: 'base64' | 'oss';
  /** mode=oss 时引用的 OSS 连接记录名 */
  storageRecord?: string;
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

export interface ChatSession {
  id?: number;
  userId: string;
  agentKey: string;
  sessionId: string;
  title?: string;
  createdAt?: string;
  updatedAt?: string;
}
