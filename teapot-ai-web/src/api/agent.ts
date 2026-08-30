import { http, unwrap } from './http';
import type { Agent, AgentDetail, MemoryUserGroup, PageData, Result, SessionHistoryItem, SessionMessageItem } from '../types';

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

/** Agent 全量会话历史列表（SPEC §24.9，仅 admin）：Web + 渠道两索引 union */
export function sessionHistory(agentKey: string, params: { page?: number; size?: number; keyword?: string }) {
  return unwrap<SessionHistoryItem[]>(
    http.get<Result<SessionHistoryItem[]>>(`/api/agent/${agentKey}/session-history`, { params }),
  );
}

/** 会话全文回放（SPEC §24.9，仅 admin）：source=web|dingtalk 区分图片引用策略 */
export function sessionHistoryMessages(agentKey: string, userId: string, sessionId: string, source: string) {
  return unwrap<SessionMessageItem[]>(
    http.get<Result<SessionMessageItem[]>>(
      `/api/agent/${agentKey}/session-history/${encodeURIComponent(userId)}/${encodeURIComponent(sessionId)}/messages`,
      { params: { source } },
    ),
  );
}

/** 删除单条历史会话（SPEC §24.9，仅 admin）：stateStore 状态 + 索引表 */
export function deleteSessionHistory(agentKey: string, userId: string, sessionId: string, source: string) {
  return unwrap<void>(
    http.delete<Result<void>>(
      `/api/agent/${agentKey}/session-history/${encodeURIComponent(userId)}/${encodeURIComponent(sessionId)}`,
      { params: { source } },
    ),
  );
}

/** Redis 记忆内容查询（SPEC §27 记忆管理）：按命名空间 uid 分组返回记忆文件（含正文） */
export function memoryItems(agentKey: string) {
  return unwrap<MemoryUserGroup[]>(
    http.get<Result<MemoryUserGroup[]>>(`/api/agent/${agentKey}/memory-items`),
  );
}

/** Redis 记忆逐条删除（SPEC §27 记忆管理） */
export function deleteMemoryItem(agentKey: string, uid: string, path: string) {
  return unwrap<void>(
    http.delete<Result<void>>(`/api/agent/${agentKey}/memory-item`, { params: { uid, path } }),
  );
}
