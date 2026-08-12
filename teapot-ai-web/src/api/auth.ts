import { http, unwrap } from './http';
import type { LoginResponse, Result, TeapotUser } from '../types';

export function login(username: string, password: string) {
  return unwrap<LoginResponse>(
    http.post<Result<LoginResponse>>('/api/auth/login', { username, password }),
  );
}

export function profile() {
  return unwrap<TeapotUser>(http.get<Result<TeapotUser>>('/api/user/profile'));
}

export interface UserPageQuery {
  page?: number;
  size?: number;
}

export function userCreate(payload: {
  userId: string;
  username: string;
  password: string;
  realName?: string;
  roles: string;
}) {
  return unwrap<TeapotUser>(http.post<Result<TeapotUser>>('/api/user/create', payload));
}

export function userUpdate(userId: string, payload: { realName?: string; roles?: string; newPassword?: string }) {
  return unwrap<TeapotUser>(http.put<Result<TeapotUser>>(`/api/user/${userId}`, payload));
}

export function userDisable(userId: string) {
  return unwrap<void>(http.delete<Result<void>>(`/api/user/${userId}`));
}

export function userPage(params: UserPageQuery) {
  return unwrap<{ total: number; list: TeapotUser[] }>(
    http.get<Result<{ total: number; list: TeapotUser[] }>>('/api/user/list', { params }),
  );
}
