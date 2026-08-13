import type {
  IAgentScopeRuntimeWebUISession,
  IAgentScopeRuntimeWebUISessionAPI,
} from '@agentscope-ai/chat';
import { sessionClear, sessionCreate, sessionList, sessionRename } from '../api/session';
import { useAuthStore } from '../store/auth';

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

/** 当前登录用户 id：会话索引与消息缓存按用户隔离，杜绝同浏览器切换账号后串号 */
const currentUid = () => useAuthStore.getState().user?.userId || 'anon';

/** 模板会话对象 + 后端时间字段（模板类型未声明，面板展示用） */
export type SessionItem = IAgentScopeRuntimeWebUISession & { updatedAt?: string };

/**
 * 会话索引缓存（模块级、按 userId+agentKey 隔离）。
 * 不能用 bridge 实例私有变量：options useMemo 重算会产生新 bridge 实例，
 * 且 getSessionList（后端请求）与 createSession 存在完成顺序竞态，
 * 后完成的列表响应若直接覆盖会把刚建的会话冲掉。合并策略：
 * 后端列表为准，但保留缓存中后端尚未返回的新会话。
 * 按用户隔离：同浏览器退出/切换账号后，新用户不会合并到旧用户的缓存会话。
 */
const sessionIndexCache = new Map<string, SessionItem[]>();

/**
 * 已创建但暂不展示的会话（懒创建）：
 * AI 首条回复完成后才揭示进 session history（见 revealHiddenSessions）。
 * 仅内存态：页面刷新后后端列表直接展示（消息已发出，展示合理）。
 */
const hiddenSessionIds = new Set<string>();

const visibleOf = (list: SessionItem[]) =>
  list.filter((s) => !hiddenSessionIds.has(s.id as string));

/**
 * 揭示隐藏的懒创建会话（回复完成时调用），
 * 返回其所属 agent 的全量列表供 setSessions 刷新；无隐藏会话时返回 null。
 */
export function revealHiddenSessions(): SessionItem[] | null {
  if (hiddenSessionIds.size === 0) return null;
  const revealedIds = new Set(hiddenSessionIds);
  hiddenSessionIds.clear();
  for (const list of sessionIndexCache.values()) {
    if (list.some((s) => revealedIds.has(s.id as string))) {
      return [...list];
    }
  }
  return null;
}

function cacheMessages(sessionId: string, messages: IAgentScopeRuntimeWebUISession['messages']) {
  try {
    localStorage.setItem(
      MSG_CACHE_PREFIX + currentUid() + ':' + sessionId,
      JSON.stringify((messages || []).slice(-MAX_CACHED_MESSAGES)),
    );
  } catch {
    // localStorage 配额满时忽略，不影响主链路
  }
}

function loadCachedMessages(sessionId: string) {
  try {
    return JSON.parse(localStorage.getItem(MSG_CACHE_PREFIX + currentUid() + ':' + sessionId) || '[]');
  } catch {
    return [];
  }
}

function dropCachedMessages(sessionId: string) {
  try {
    localStorage.removeItem(MSG_CACHE_PREFIX + currentUid() + ':' + sessionId);
  } catch {
    // ignore
  }
}

/** 按 agentKey 创建一个模板会话 API 适配器（切换 agent 时随 options 重建） */
export function createSessionBridge(agentKey: string): IAgentScopeRuntimeWebUISessionAPI {
  const getMirror = () => sessionIndexCache.get(currentUid() + '|' + agentKey) || [];
  const setMirror = (list: SessionItem[]) => {
    sessionIndexCache.set(currentUid() + '|' + agentKey, list);
  };

  return {
    async getSessionList() {
      const prev = getMirror();
      const list = await sessionList(agentKey);
      const fresh = (list || []).map((s) => ({
        id: s.sessionId,
        name: s.title || '新会话',
        messages: [],
        updatedAt: s.updatedAt || s.createdAt,
      }));
      // 保留后端列表尚未返回的本地新会话（list/create 完成顺序竞态）
      const freshIds = new Set(fresh.map((s) => s.id));
      const pending = prev.filter((s) => !freshIds.has(s.id));
      const merged = [...pending, ...fresh];
      setMirror(merged);
      return visibleOf(merged);
    },

    async getSession(sessionId: string) {
      const found = getMirror().find((s) => s.id === sessionId);
      if (!found) return undefined as unknown as IAgentScopeRuntimeWebUISession;
      return { ...found, messages: loadCachedMessages(sessionId) };
    },

    async createSession(session) {
      const lazy = !session.name;
      const title = (session.name || '').slice(0, MAX_TITLE_LEN) || undefined;
      const created = await sessionCreate(agentKey, title);
      // 关键：模板取的是入参对象的 id（见 starter SessionApi），必须写回
      session.id = created.sessionId;
      if (lazy) {
        // 懒创建：标题留空等模板以首条消息回填，先隐藏不进列表
        session.name = '';
        hiddenSessionIds.add(created.sessionId);
      } else {
        session.name = created.title || session.name || '';
      }
      setMirror([
        {
          id: created.sessionId,
          name: session.name,
          messages: [],
          updatedAt: created.updatedAt || created.createdAt || new Date().toISOString(),
        },
        ...getMirror(),
      ]);
      return visibleOf(getMirror());
    },

    async updateSession(session) {
      const mirror = getMirror();
      const idx = mirror.findIndex((s) => s.id === session.id);
      if (idx > -1) {
        const prev = mirror[idx];
        const renamed =
          !!session.name && session.name !== prev.name;
        mirror[idx] = {
          ...prev,
          name: session.name ?? prev.name,
          // 有消息写入即视为活跃，刷新列表时间
          updatedAt: session.messages ? new Date().toISOString() : prev.updatedAt,
        };
        if (session.messages) {
          cacheMessages(session.id as string, session.messages);
        }
        // 标题变更同步后端（懒创建以首条消息回填），失败不阻塞主链路
        if (renamed) {
          sessionRename(session.id as string, (session.name as string).slice(0, MAX_TITLE_LEN)).catch(
            () => undefined,
          );
        }
      }
      return visibleOf(getMirror());
    },

    async removeSession(session) {
      if (session.id) {
        try {
          await sessionClear(session.id);
        } catch {
          // 后端清理失败不阻塞前端移除
        }
        hiddenSessionIds.delete(session.id);
        dropCachedMessages(session.id);
        setMirror(getMirror().filter((s) => s.id !== session.id));
      }
      return visibleOf(getMirror());
    },
  };
}
