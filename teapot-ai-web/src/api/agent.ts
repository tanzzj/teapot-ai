import { http, unwrap } from './http';
import type { Agent, AgentDetail, PageData, Result, SandboxOptions } from '../types';

export function agentList(params: { page?: number; size?: number; keyword?: string; includeDisabled?: boolean }) {
  return unwrap<PageData<Agent>>(http.get<Result<PageData<Agent>>>('/api/agent/list', { params }));
}

export function agentDetail(agentKey: string) {
  return unwrap<AgentDetail>(http.get<Result<AgentDetail>>(`/api/agent/detail/${agentKey}`));
}

export interface AgentCreatePayload {
  agentKey: string;
  name: string;
  description?: string;
  sysPrompt: string;
  modelId: string;
  compactionTrigger?: number;
  compactionKeep?: number;
  skillNames?: string[];
  feature?: Record<string, unknown>;
}

export function agentCreate(payload: AgentCreatePayload) {
  return unwrap<Agent>(http.post<Result<Agent>>('/api/agent/create', payload));
}

export function agentUpdate(agentKey: string, payload: Partial<Omit<AgentCreatePayload, 'agentKey'>>) {
  return unwrap<Agent>(http.put<Result<Agent>>(`/api/agent/update/${agentKey}`, payload));
}

export function agentDelete(agentKey: string) {
  return unwrap<void>(http.delete<Result<void>>(`/api/agent/delete/${agentKey}`));
}

export function agentBindSkill(agentKey: string, skillName: string) {
  return unwrap<void>(http.post<Result<void>>(`/api/agent/bindSkill/${agentKey}`, { skillName }));
}

export function agentUnbindSkill(agentKey: string, skillName: string) {
  return unwrap<void>(http.post<Result<void>>(`/api/agent/unbindSkill/${agentKey}`, { skillName }));
}

/** 同步调试对话（SPEC §7.1 /api/agent/chat） */
export function agentChat(agentKey: string, messageText: string, sessionId?: string) {
  return unwrap<string>(
    http.post<Result<string>>(`/api/agent/chat/${agentKey}`, { message: messageText, sessionId }),
  );
}

export function modelPresets() {
  return unwrap<string[]>(http.get<Result<string[]>>('/api/model/presets'));
}

/** 沙箱选项与全局接入状态（SPEC §16.11） */
export function sandboxOptions() {
  return unwrap<SandboxOptions>(http.get<Result<SandboxOptions>>('/api/config/sandbox-options'));
}

/** 写入全局 AgentRun 接入凭证（仅 admin，SPEC §16.5.1） */
export function updateSandboxConfig(payload: Record<string, string>) {
  return unwrap<SandboxOptions>(http.put<Result<SandboxOptions>>('/api/config/sandbox', payload));
}
