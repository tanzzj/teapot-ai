import type {
  IAgentScopeRuntimeWebUISession,
  IAgentScopeRuntimeWebUISessionAPI,
} from '@agentscope-ai/chat';
import { sessionClear, sessionCreate, sessionList } from '../api/session';

/**
 * 会话桥接（sparkdesign chat template ↔ Teapot 后端）：
 * - 会话索引（列表/创建/删除）走后端 /api/chat/session（SPEC §9）
 * - 消息体以后端 agentscope_sessions（StateStore）为事实源，模板渲染所需的消息快照
 *   缓存在 localStorage（按 sessionId 隔离，仅用于切换会话后恢复画面）
 */

const MSG_CACHE_PREFIX = 'teapot-chat-msgs:';
const MAX_CACHED_MESSAGES = 100;
/** 会话标题入库截断长度 */
const MAX_TITLE_LEN = 50;

/** 模板会话对象 + 后端时间字段（模板类型未声明，面板展示用） */
export type SessionItem = IAgentScopeRuntimeWebUISession & { updatedAt?: string };

function cacheMessages(sessionId: string, messages: IAgentScopeRuntimeWebUISession['messages']) {
  try {
    localStorage.setItem(
      MSG_CACHE_PREFIX + sessionId,
      JSON.stringify((messages || []).slice(-MAX_CACHED_MESSAGES)),
    );
  } catch {
    // localStorage 配额满时忽略，不影响主链路
  }
}

function loadCachedMessages(sessionId: string) {
  try {
    return JSON.parse(localStorage.getItem(MSG_CACHE_PREFIX + sessionId) || '[]');
  } catch {
    return [];
  }
}

function dropCachedMessages(sessionId: string) {
  try {
    localStorage.removeItem(MSG_CACHE_PREFIX + sessionId);
  } catch {
    // ignore
  }
}

/** 按 agentKey 创建一个模板会话 API 适配器（切换 agent 时随 options 重建） */
export function createSessionBridge(agentKey: string): IAgentScopeRuntimeWebUISessionAPI {
  // 内存镜像：避免每次操作都回源列表接口
  let mirror: SessionItem[] = [];

  return {
    async getSessionList() {
      const list = await sessionList(agentKey);
      mirror = (list || []).map((s) => ({
        id: s.sessionId,
        name: s.title || '新会话',
        messages: [],
        updatedAt: s.updatedAt || s.createdAt,
      }));
      return [...mirror];
    },

    async getSession(sessionId: string) {
      const found = mirror.find((s) => s.id === sessionId);
      if (!found) return undefined as unknown as IAgentScopeRuntimeWebUISession;
      return { ...found, messages: loadCachedMessages(sessionId) };
    },

    async createSession(session) {
      const title = (session.name || '').slice(0, MAX_TITLE_LEN) || undefined;
      const created = await sessionCreate(agentKey, title);
      // 关键：模板取的是入参对象的 id（见 starter SessionApi），必须写回
      session.id = created.sessionId;
      session.name = created.title || session.name || '';
      mirror = [
        {
          id: created.sessionId,
          name: session.name,
          messages: [],
          updatedAt: created.updatedAt || created.createdAt || new Date().toISOString(),
        },
        ...mirror,
      ];
      return [...mirror];
    },

    async updateSession(session) {
      const idx = mirror.findIndex((s) => s.id === session.id);
      if (idx > -1) {
        mirror[idx] = {
          ...mirror[idx],
          name: session.name ?? mirror[idx].name,
          // 有消息写入即视为活跃，刷新列表时间
          updatedAt: session.messages ? new Date().toISOString() : mirror[idx].updatedAt,
        };
        if (session.messages) {
          cacheMessages(session.id as string, session.messages);
        }
      }
      return [...mirror];
    },

    async removeSession(session) {
      if (session.id) {
        try {
          await sessionClear(session.id);
        } catch {
          // 后端清理失败不阻塞前端移除
        }
        dropCachedMessages(session.id);
        mirror = mirror.filter((s) => s.id !== session.id);
      }
      return [...mirror];
    },
  };
}
