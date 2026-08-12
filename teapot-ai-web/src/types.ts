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
  status: number;
  createdBy?: string;
  createdAt?: string;
  updatedAt?: string;
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

export interface ChatSession {
  id?: number;
  userId: string;
  agentKey: string;
  sessionId: string;
  title?: string;
  createdAt?: string;
  updatedAt?: string;
}
