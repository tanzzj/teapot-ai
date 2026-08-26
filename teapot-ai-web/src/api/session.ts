import { http, unwrap } from './http';
import type { ChatSession, Result } from '../types';

/** 会话历史消息条目（后端按内容块拆分）：
 *  - user + type=text / type=image / type=video：用户文本与图片/视频（url 为 data URL、http URL 或取媒体端点引用）
 *  - assistant + type=text：文本消息
 *  - type=reasoning：深度思考
 *  - type=tool_call / tool_call_output：工具调用与结果 */
export interface SessionMessageItem {
  role: string;
  type: string;
  text?: string;
  toolCallId?: string;
  toolName?: string;
  arguments?: string;
  output?: string;
  imageUrl?: string;
  videoUrl?: string;
  /** 消息时间戳（epoch millis，后端从 Msg.timestamp 解析；旧数据可能缺失） */
  timestamp?: number;
}

export function sessionList(agentKey?: string) {
  return unwrap<ChatSession[]>(
    http.get<Result<ChatSession[]>>('/api/chat/session/list', { params: { agentKey } }),
  );
}

/** 会话按日统计（Profile 热力图数据源） */
export function sessionStats(agentKey: string) {
  return unwrap<{ date: string; count: number }[]>(
    http.get<Result<{ date: string; count: number }[]>>('/api/chat/session/stats', { params: { agentKey } }),
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

export function sessionRename(sessionId: string, title: string) {
  return unwrap<void>(http.put<Result<void>>('/api/chat/session/rename', { sessionId, title }));
}

export function sessionMessages(sessionId: string) {
  return unwrap<SessionMessageItem[]>(
    http.get<Result<SessionMessageItem[]>>(`/api/chat/session/messages/${sessionId}`),
  );
}
