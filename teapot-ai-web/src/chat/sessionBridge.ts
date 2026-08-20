import type {
  IAgentScopeRuntimeWebUISession,
  IAgentScopeRuntimeWebUISessionAPI,
} from '@agentscope-ai/chat';
import { sessionClear, sessionCreate, sessionList, sessionMessages, sessionRename } from '../api/session';
import type { SessionMessageItem } from '../api/session';
import { http } from '../api/http';
import { useAuthStore } from '../store/auth';

/**
 * 会话桥接（sparkdesign chat template ↔ Teapot 后端）：
 * - 会话索引（列表/创建/删除）走后端 /api/chat/session（SPEC §9）
 * - 消息体以后端 agentscope_sessions（StateStore）为事实源：每次打开会话都拉
 *   后端历史接口 /api/chat/session/messages/{sessionId}（保证跨设备同步），
 *   localStorage 仅作离线/后端故障兜底与首屏加速缓存（按 sessionId 隔离）
 */

const MSG_CACHE_PREFIX = 'teapot-chat-msgs:v5:';
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

// 一次性清理 v4 缓存：旧版把图片 base64 内联存进 localStorage，
// 单条可达数百 KB，已被 v5（取图端点引用）取代，直接删掉释放配额
try {
  const staleKeys: string[] = [];
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i);
    if (k && k.startsWith('teapot-chat-msgs:v4:')) staleKeys.push(k);
  }
  staleKeys.forEach((k) => localStorage.removeItem(k));
} catch {
  // ignore
}

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
      JSON.stringify(normalizeForCache((messages || []).slice(-MAX_CACHED_MESSAGES) as never)),
    );
  } catch {
    // localStorage 配额满时忽略，不影响主链路
  }
}

/**
 * 入缓存前把无法持久化的图片地址置空：blob: 是页面生命周期内的临时 URL，
 * 刷新后失效，留着会造成破图；data:（实时发送链路产物）保留，作本机
 * 离线兜底。下次打开会话后端优先拉取会重建正确引用，此处丢失不影响主链路。
 */
function normalizeForCache(messages: Record<string, unknown>[]) {
  return messages.map((m) => {
    const cards = m?.cards as { data?: { input?: { content?: { type?: string; image_url?: string }[] }[] } }[] | undefined;
    for (const card of cards ?? []) {
      for (const i of card?.data?.input ?? []) {
        for (const c of i?.content ?? []) {
          if (c?.type === 'image' && typeof c.image_url === 'string'
            && c.image_url.startsWith('blob:')) {
            c.image_url = '';
          }
        }
      }
    }
    return m;
  });
}

/** 取图端点引用 → blob URL：图片接口需 Bearer 鉴权，<img> 无法带头，
 * 故用 http 客户端拉二进制再转 object URL（模块级缓存，同图不重复拉取） */
const objectUrlCache = new Map<string, string>();

async function resolveImageUrls(messages: Record<string, unknown>[]) {
  const parts: { type?: string; image_url?: string }[] = [];
  for (const m of messages) {
    const cards = (m as { cards?: { data?: { input?: { content?: unknown[] }[] } }[] })?.cards;
    for (const card of cards ?? []) {
      for (const i of card?.data?.input ?? []) {
        for (const c of (i?.content ?? []) as { type?: string; image_url?: string }[]) {
          if (c?.type === 'image' && typeof c.image_url === 'string') parts.push(c);
        }
      }
    }
  }
  await Promise.all(parts.map(async (c) => {
    const url = c.image_url as string;
    if (!url.startsWith('/api/chat/session/image/')) {
      // http(s) URL 源、data URL、空串均无需处理；blob: 已在入缓存时清理
      return;
    }
    let obj = objectUrlCache.get(url);
    if (!obj) {
      try {
        const resp = await http.get(url, { responseType: 'blob' });
        obj = URL.createObjectURL(resp.data as Blob);
        objectUrlCache.set(url, obj);
      } catch {
        c.image_url = '';
        return;
      }
    }
    c.image_url = obj;
  }));
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

/**
 * 后端历史条目（按内容块拆分）→ 模板消息卡片结构：
 * - user/text+image：同一用户轮次的连续条目合并为一张 AgentScopeRuntimeRequestCard
 *   （data.input 的 content parts 含 text 与 image，与实时发送链路一致）
 * - assistant 轮的 reasoning/tool_call/tool_call_output/text 块合并进同一张
 *   AgentScopeRuntimeResponseCard 的 output 数组，与实时回放渲染完全一致；
 * 直接塞裸消息体会被渲染成 [object Object]。
 */
function toTemplateMessages(items: SessionMessageItem[]) {
  const now = Math.floor(Date.now() / 1000);
  const messages: Record<string, unknown>[] = [];
  let seq = 0;
  let output: Record<string, unknown>[] | null = null;
  let userContent: Record<string, unknown>[] | null = null;

  // 把当前积累的助手轮次块封装为一张响应卡片
  const flushAssistant = () => {
    if (output && output.length) {
      const id = `hist-${seq++}`;
      messages.push({
        id,
        role: 'assistant',
        cards: [{
          code: 'AgentScopeRuntimeResponseCard',
          data: { id: `${id}-resp`, object: 'response', status: 'completed', created_at: now, output },
        }],
      });
    }
    output = null;
  };

  // 把当前积累的用户轮次内容块封装为一张请求卡片
  const flushUser = () => {
    if (userContent && userContent.length) {
      const id = `hist-${seq++}`;
      messages.push({
        id,
        role: 'user',
        cards: [{
          code: 'AgentScopeRuntimeRequestCard',
          data: {
            created_at: now,
            input: [{ role: 'user', type: 'message', content: userContent }],
          },
        }],
      });
    }
    userContent = null;
  };

  for (const m of items) {
    if (m.role === 'user') {
      flushAssistant();
      if (!userContent) userContent = [];
      if (m.type === 'image' && m.imageUrl) {
        userContent.push({ type: 'image', image_url: m.imageUrl, status: 'created' });
      } else if (m.type === 'text' && m.text) {
        userContent.push({ type: 'text', text: m.text, status: 'created' });
      }
      continue;
    }
    flushUser();
    if (!output) output = [];
    const mid = `hist-${seq++}-m`;
    if (m.type === 'reasoning' && m.text) {
      output.push({
        id: mid, role: 'assistant', type: 'reasoning', status: 'completed',
        content: [{ object: 'content', type: 'text', text: m.text, msg_id: mid, status: 'completed' }],
      });
    } else if (m.type === 'tool_call') {
      const callId = m.toolCallId || mid;
      output.push({
        id: callId, role: 'assistant', type: 'tool_call', status: 'completed',
        content: [{
          object: 'content', type: 'data', msg_id: callId, status: 'completed',
          data: { name: m.toolName, call_id: callId, arguments: m.arguments || '' },
        }],
      });
    } else if (m.type === 'tool_call_output') {
      const callId = m.toolCallId || mid;
      output.push({
        id: `${callId}-output`, role: 'assistant', type: 'tool_call_output', status: 'completed',
        content: [{
          object: 'content', type: 'data', msg_id: `${callId}-output`, status: 'completed',
          data: { name: m.toolName, call_id: callId, output: m.output || '' },
        }],
      });
    } else if (m.type === 'text' && m.text) {
      output.push({
        id: mid, role: 'assistant', type: 'message', status: 'completed',
        content: [{ object: 'content', type: 'text', text: m.text, msg_id: mid, status: 'completed' }],
      });
    }
  }
  flushUser();
  flushAssistant();
  return messages;
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
      // 后端 agentscope_sessions 为唯一事实源：每次打开都拉后端，保证跨设备/多标签页
      // 能看到其他端发的最新消息（旧实现仅缓存缺失时才回源，导致本地缓存永久过期）；
      // 后端拉取失败/为空时才退回本地缓存兜底
      let messages = loadCachedMessages(sessionId);
      try {
        const items = await sessionMessages(sessionId);
        const fresh = toTemplateMessages(items || []);
        if (fresh.length) {
          messages = fresh;
          // 先缓存（JSON.stringify 同步快照，存的是取图端点引用），再转 blob URL 供渲染
          cacheMessages(sessionId, fresh as never);
        }
      } catch {
        // 后端历史拉取失败不阻塞主链路，用本地缓存兜底
      }
      await resolveImageUrls(messages);
      return { ...found, messages };
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
