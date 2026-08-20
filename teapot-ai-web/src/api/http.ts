import axios, { AxiosError } from 'axios';
import { message } from '@agentscope-ai/design';
import type { Result } from '../types';

export const ACCESS_TOKEN_KEY = 'teapot-ai-access-token';
export const REFRESH_TOKEN_KEY = 'teapot-ai-refresh-token';

/** 统一 HTTP 客户端（SPEC §12.1：401 自动 refresh，统一 Result 解包）；
 * 超时放宽到 120s：会话历史回放含图片 base64 时响应体可达数百 KB，弱网下需足够余量 */
export const http = axios.create({
  baseURL: '/',
  timeout: 120000,
});

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(ACCESS_TOKEN_KEY);
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

let refreshing: Promise<string | null> | null = null;

async function tryRefresh(): Promise<string | null> {
  const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
  if (!refreshToken) {
    return null;
  }
  try {
    const resp = await axios.post<Result<{ accessToken: string; refreshToken: string }>>(
      '/api/auth/refresh',
      { refreshToken },
    );
    if (resp.data.code === 0 && resp.data.data?.accessToken) {
      localStorage.setItem(ACCESS_TOKEN_KEY, resp.data.data.accessToken);
      if (resp.data.data.refreshToken) {
        localStorage.setItem(REFRESH_TOKEN_KEY, resp.data.data.refreshToken);
      }
      return resp.data.data.accessToken;
    }
  } catch {
    // refresh 失败走登出
  }
  return null;
}

http.interceptors.response.use(
  (response) => {
    const body = response.data as Result<unknown>;
    if (body && typeof body.code === 'number' && body.code !== 0) {
      // 401 业务码：token 过期由下方 AxiosError 分支处理；其余业务错误直接提示
      if (body.code === 401) {
        redirectToLogin();
      } else {
        message.error(body.message || '操作失败');
      }
      return Promise.reject(new Error(body.message || '操作失败'));
    }
    return response;
  },
  async (error: AxiosError) => {
    const status = error.response?.status;
    const original = error.config as { _retried?: boolean } | undefined;
    if (status === 401 && original && !original._retried) {
      original._retried = true;
      if (!refreshing) {
        refreshing = tryRefresh().finally(() => {
          refreshing = null;
        });
      }
      const newToken = await refreshing;
      if (newToken) {
        return http.request(error.config!);
      }
      redirectToLogin();
    } else if (status === 403) {
      message.error('无权限执行该操作');
    } else {
      const body = error.response?.data as Result<unknown> | undefined;
      message.error(body?.message || `请求失败（${status ?? '网络错误'}）`);
    }
    return Promise.reject(error);
  },
);

export function redirectToLogin() {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  if (!window.location.pathname.startsWith('/login')) {
    window.location.href = '/login';
  }
}

/** Result 解包快捷方法 */
export async function unwrap<T>(promise: Promise<{ data: Result<T> }>): Promise<T> {
  const resp = await promise;
  return resp.data.data;
}
