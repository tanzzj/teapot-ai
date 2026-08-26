import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { Drawer, IconButton } from '@agentscope-ai/design';
import { SparkHistoryLine } from '@agentscope-ai/icons';
import {
  AgentScopeRuntimeWebUI,
  useChatAnywhereSessions,
  useChatAnywhereSessionsState,
} from '@agentscope-ai/chat';
import type { IAgentScopeRuntimeWebUIOptions } from '@agentscope-ai/chat';
import { createHistorySessionBridge } from './sessionBridge';
import SessionPanel from './SessionPanel';
import type { SessionItem } from './sessionBridge';

// 左栏会话列表 vs 抽屉的切换断点：更窄的手机屏放不下两栏
const NARROW_BP = 768;
/** 左栏 Portal 挂载点（单实例页面，id 唯一） */
const LEFT_SLOT_ID = 'teapot-history-left-slot';

/**
 * 桥接组件：渲染在模板 Provider 内部（rightHeader 插槽），保证子树能拿到会话 Context。
 * 1) 进入分区自动选中首个会话（模板默认停欢迎页，历史场景直接看第一条更合理）；
 * 2) 宽屏：把 SessionPanel 经 Portal 挂到 HistoryChatPanel 自布的左栏容器
 *    （模板左列只在 ≥992 且非 hideBuiltInSessionList 时渲染，中宽屏不可用；
 *    Portal 跟随 React 树传递 Context，DOM 位置可任意）；
 * 3) 窄屏（<768）：经 Portal 把历史按钮 + Drawer 挂到全局顶栏 #topbar-history-slot。
 */
function HistoryBridge(props: {
  narrow: boolean;
  drawerOpen: boolean;
  onDrawerOpen: (open: boolean) => void;
}) {
  const { sessions, currentSessionId } = useChatAnywhereSessionsState();
  const { changeCurrentSessionId } = useChatAnywhereSessions();
  const [leftSlot, setLeftSlot] = useState<HTMLElement | null>(null);
  const [topSlot, setTopSlot] = useState<HTMLElement | null>(null);

  useEffect(() => {
    setLeftSlot(document.getElementById(LEFT_SLOT_ID));
    setTopSlot(document.getElementById('topbar-history-slot'));
  }, [props.narrow]);

  useEffect(() => {
    const list = (sessions || []) as SessionItem[];
    if (!currentSessionId && list.length > 0) {
      changeCurrentSessionId(list[0].id as string);
    }
  }, [sessions, currentSessionId, changeCurrentSessionId]);

  if (!props.narrow && leftSlot) {
    return createPortal(<SessionPanel title="会话历史" readonly />, leftSlot);
  }
  if (props.narrow && topSlot) {
    return createPortal(
      <>
        <IconButton
          bordered={false}
          icon={<SparkHistoryLine />}
          onClick={() => props.onDrawerOpen(true)}
        />
        <Drawer
          open={props.drawerOpen}
          onClose={() => props.onDrawerOpen(false)}
          placement="left"
          width="80vw"
          title={null}
          closable={false}
          styles={{ body: { padding: 0, height: '100%' } }}
        >
          <SessionPanel
            title="会话历史"
            readonly
            onNavigate={() => props.onDrawerOpen(false)}
          />
        </Drawer>
      </>,
      topSlot,
    );
  }
  return null;
}

/**
 * 会话历史分区（SPEC §24.9，仅 admin）：完全复用 chat 页的 SessionPanel + 聊天面板——
 * 挂载一个只读 AgentScopeRuntimeWebUI 实例，会话桥接走 session-history 全量接口
 * （Web + 渠道 union，跨用户）。宽屏左栏常驻 SessionPanel（同 chat 页视觉），
 * 窄屏改顶栏按钮 + Drawer；输入区经 CSS 隐藏、beforeSubmit 兜底拦截。
 */
export default function HistoryChatPanel({ agentKey }: { agentKey: string }) {
  const [narrow, setNarrow] = useState(window.innerWidth < NARROW_BP);
  // 窄屏会话列表藏在 Drawer 里，顶栏图标入口不直观——进入分区自动展开，列表直接可见
  const [drawerOpen, setDrawerOpen] = useState(() => window.innerWidth < NARROW_BP);

  useEffect(() => {
    const onResize = () => setNarrow(window.innerWidth < NARROW_BP);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const options = useMemo<IAgentScopeRuntimeWebUIOptions>(() => ({
    api: {
      // 只读回放：不允许发送；beforeSubmit 已兜底拦截，fetch 不会被调用
      fetch: async () => {
        throw new Error('session history is read-only');
      },
    },
    session: {
      multiple: true,
      api: createHistorySessionBridge(agentKey),
      // 会话列表由本分区自布左栏/Drawer 提供，模板内置列表全宽隐藏
      hideBuiltInSessionList: true,
    },
    theme: {
      locale: 'cn',
      colorPrimary: '#1a1a1d',
      rightHeader: (
        <HistoryBridge narrow={narrow} drawerOpen={drawerOpen} onDrawerOpen={setDrawerOpen} />
      ),
    },
    // 必传：模板 DefaultResponseRender 以 v.welcome.avatar/nick 取头像昵称，
    // welcome 缺失时 selector 抛错被兜底为 {}，{} 被当 React child 渲染直接崩溃（React #31）
    welcome: {
      greeting: '会话历史',
      nick: '只读回放',
      description: '全量用户对话（Web + 渠道），只读回放。',
    },
    sender: {
      placeholder: '只读回放，不可发送',
      beforeSubmit: async () => false,
    },
  }), [agentKey, narrow, drawerOpen]);

  return (
    <div
      className="teapot-history-chat"
      style={{ display: 'flex', gap: 12, height: '100%', minHeight: 480 }}
    >
      {/* 宽屏左栏：SessionPanel 经 Portal 挂入（见 HistoryBridge） */}
      {!narrow && (
        <div
          id={LEFT_SLOT_ID}
          className="glass-card"
          style={{ width: 280, flexShrink: 0, minHeight: 0, overflow: 'hidden' }}
        />
      )}
      <div style={{ flex: 1, minWidth: 0 }}>
        <AgentScopeRuntimeWebUI key={agentKey} options={options} />
      </div>
    </div>
  );
}
