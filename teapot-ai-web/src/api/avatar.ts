import { http, unwrap } from './http';
import type { Result } from '../types';

/** Agent 头像上传（SPEC §23）：multipart 直传，服务端转存 OSS 记录并落 t_agent.avatar */
export function uploadAgentAvatar(agentKey: string, file: Blob | File) {
  const fd = new FormData();
  fd.append('file', file);
  return unwrap<{ url: string }>(
    http.post<Result<{ url: string }>>(`/api/avatar/agent/${encodeURIComponent(agentKey)}`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }),
  );
}

/** 当前登录用户头像上传（SPEC §23）：落 t_user.avatar（仅本人） */
export function uploadUserAvatar(file: Blob | File) {
  const fd = new FormData();
  fd.append('file', file);
  return unwrap<{ url: string }>(
    http.post<Result<{ url: string }>>('/api/avatar/user', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }),
  );
}
