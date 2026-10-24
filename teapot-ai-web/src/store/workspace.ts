import { create } from 'zustand';
import { workspaceList } from '../api/workspace';
import type { Workspace } from '../types';

const LS_KEY = 'teapot:activeWorkspace';

/** 读取持久化的当前空间；'' / 非法 = 个人空间（null，SPEC §28.3 默认视图） */
function readStoredId(): number | null {
  const raw = localStorage.getItem(LS_KEY);
  if (!raw) {
    return null;
  }
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

interface WorkspaceState {
  workspaces: Workspace[];
  /** null = 个人空间（默认，可能尚未落库） */
  activeId: number | null;
  loaded: boolean;
  load: () => Promise<void>;
  setActive: (id: number | null) => void;
}

/**
 * 当前业务空间上下文（SPEC §28.6）：顶栏切换后 Agent 列表等按 activeId 作用域重查。
 * 个人空间为合成默认项（activeId=null），不附带任何 Agent，首个 Agent 创建时才落库。
 */
export const useWorkspaceStore = create<WorkspaceState>((set, get) => ({
  workspaces: [],
  activeId: readStoredId(),
  loaded: false,

  load: async () => {
    try {
      const list = await workspaceList();
      // 持久化的空间若已不可见则回落个人空间（null）
      const active = get().activeId;
      let nextActive = active;
      if (active !== null && !list.some((w) => w.id === active)) {
        nextActive = null;
        localStorage.removeItem(LS_KEY);
      }
      set({ workspaces: list, activeId: nextActive, loaded: true });
    } catch {
      set({ loaded: true });
    }
  },

  setActive: (id) => {
    if (id === null || id === undefined) {
      localStorage.removeItem(LS_KEY);
    } else {
      localStorage.setItem(LS_KEY, String(id));
    }
    set({ activeId: id });
  },
}));
