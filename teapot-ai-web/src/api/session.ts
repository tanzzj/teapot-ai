import { http, unwrap } from './http';
import type { ChatSession, Result } from '../types';

export function sessionList(agentKey?: string) {
  return unwrap<ChatSession[]>(
    http.get<Result<ChatSession[]>>('/api/chat/session/list', { params: { agentKey } }),
  );
}

export function sessionCreate(agentKey: string, title?: string) {
  return unwrap<ChatSession>(
    http.post<Result<ChatSession>>('/api/chat/session/create', { agentKey, title }),
  );
}

export function sessionClear(sessionId: string) {
  return unwrap<void>(http.delete<Result<void>>(`/api/chat/session/clear/${sessionId}`));
}
