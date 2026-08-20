import { create } from 'zustand';
import type { TeapotUser } from '../types';
import { ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY } from '../api/http';

interface AuthState {
  user: TeapotUser | null;
  loggedIn: boolean;
  setSession: (accessToken: string, refreshToken: string, user: TeapotUser) => void;
  /** 局部更新当前用户信息（如头像上传后回填，SPEC §23） */
  setUserPatch: (patch: Partial<TeapotUser>) => void;
  logout: () => void;
  hasRole: (...roles: string[]) => boolean;
}

/** 登录态（SPEC §12.1：zustand auth store） */
export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  loggedIn: !!localStorage.getItem(ACCESS_TOKEN_KEY),

  setSession: (accessToken, refreshToken, user) => {
    localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
    set({ user, loggedIn: true });
  },

  setUserPatch: (patch) => {
    const current = get().user;
    if (current) {
      set({ user: { ...current, ...patch } });
    }
  },

  logout: () => {
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    set({ user: null, loggedIn: false });
  },

  hasRole: (...roles) => {
    const user = get().user;
    if (!user) {
      return false;
    }
    const owned = (user.roles || '').split(',').map((r) => r.trim());
    return roles.some((r) => owned.includes(r));
  },
}));
