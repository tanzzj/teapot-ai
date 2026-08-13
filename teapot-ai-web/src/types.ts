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
  isolationScope?: 'SESSION' | 'USER' | 'AGENT' | 'GLOBAL';
  persistence?: 'NONE' | 'LOCAL_SNAPSHOT' | 'NAS';
  templateName?: string;
  workspaceRoot?: string;
  idleTimeoutSeconds?: number;
  nas?: AgentSandboxNas;
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
