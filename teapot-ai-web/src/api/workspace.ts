import { http, unwrap } from './http';
import type { Result, Workspace, WorkspaceMember } from '../types';

/** 当前用户可见空间（顶栏下拉数据源，SPEC §28.3/§28.4） */
export function workspaceList() {
  return unwrap<Workspace[]>(http.get<Result<Workspace[]>>('/api/workspace/list'));
}

/** 全部启用团队空间（仅 admin，模型开放范围数据源，SPEC §28.11） */
export function workspaceAll() {
  return unwrap<Workspace[]>(http.get<Result<Workspace[]>>('/api/workspace/all'));
}

export interface WorkspaceCreatePayload {
  workspaceName: string;
  workspaceCode: string;
  description?: string;
  memberUserIds?: string[];
}

/** 新建团队空间（必填校验 + 批量加成员，SPEC §28.4） */
export function workspaceCreate(payload: WorkspaceCreatePayload) {
  return unwrap<Workspace>(http.post<Result<Workspace>>('/api/workspace/create', payload));
}

/** 空间成员列表（跨空间隔离：非成员 403，SPEC §28.3） */
export function workspaceMembers(id: number) {
  return unwrap<WorkspaceMember[]>(http.get<Result<WorkspaceMember[]>>(`/api/workspace/${id}/members`));
}

/** 加成员（加成员即授权，SPEC §28.4） */
export function workspaceAddMember(id: number, userId: string) {
  return unwrap<void>(http.post<Result<void>>(`/api/workspace/${id}/member`, { userId }));
}

/** 移成员（仅 owner/白名单，SPEC §28.4） */
export function workspaceRemoveMember(id: number, userId: string) {
  return unwrap<void>(http.delete<Result<void>>(`/api/workspace/${id}/member/${encodeURIComponent(userId)}`));
}

/** 停用空间（仅 owner/白名单，SPEC §28.4） */
export function workspaceDelete(id: number) {
  return unwrap<void>(http.delete<Result<void>>(`/api/workspace/${id}`));
}
