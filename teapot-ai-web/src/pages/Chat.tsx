import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useSearchParams } from 'react-router-dom';
import { Empty } from 'antd';
import { Drawer, IconButton } from '@agentscope-ai/design';
import { SparkHistoryLine } from '@agentscope-ai/icons';
import {
  AgentScopeRuntimeWebUI,
  useChatAnywhereInput,
  useChatAnywhereSessions,
  useChatAnywhereSessionsState,
} from '@agentscope-ai/chat';
import type { IAgentScopeRuntimeWebUIOptions } from '@agentscope-ai/chat';
import { agentList } from '../api/agent';
import { aguiResponseParser, createAguiFetch } from '../chat/aguiBridge';
import { createSessionBridge, revealHiddenSessions } from '../chat/sessionBridge';
import SessionPanel from '../chat/SessionPanel';
import { newChatCoordinator } from '../chat/newChatCoordinator';
import type { Agent } from '../types';

// 必须与模板内置 narrowMode 断点一致（ahooks useResponsive 的 lg=992px）：
// 低于 992 时模板会改渲染内置会话列表（带 "Runtime WebUI" 品牌头部），
// 若本断点取更小值（如 768），768–991 区间会出现内置列表与自定义逻辑不一致
const MOBILE_BP = 992;

/**
 * 桥接组件：渲染在 ChatAnywhere Provider 内部（rightHeader 插槽，桌面/移动均常驻挂载）。
 * 1) 把模板当前的 sessionId getter 暴露给外层 fetch 闭包（作 AG-UI threadId）；
 * 2) 懒创建会话接线：上报当前会话、注册建会话能力；
 * 3) 揭示时机：AI 首条回复完成（loading true→false）后把隐藏会话放入列表；
 * 4) 移动端会话历史入口：经 Portal 挂到全局顶栏。
 *    注意：Portal 必须从本组件（Provider 内部）发起——SessionPanel 依赖
 *    ChatAnywhere 的 React Context，若放到 Provider 外部渲染会拿不到会话状态。
 */
function ChatBridge(props: {
  register: (getter: () => string | undefined) => void;
  isMobile: boolean;
  slot: HTMLElement | null;
  drawerOpen: boolean;
  onDrawerOpen: (open: boolean) => void;
  agentName: string;
}) {
  const { getCurrentSessionId, createSession } = useChatAnywhereSessions();
  const { currentSessionId, setSessions } = useChatAnywhereSessionsState();
  const loading = useChatAnywhereInput((v) => ({ loading: v.loading })).loading;

  useEffect(() => {
    props.register(getCurrentSessionId);
  }, [props.register, getCurrentSessionId]);

  // 上报当前会话 id，供 beforeSubmit 判断是否需要懒创建
  useEffect(() => {
    newChatCoordinator.reportSession(currentSessionId);
  }, [currentSessionId]);

  // 注册懒创建能力：提交前无会话时先建会话，再等模板 loader 冲刷完成
  useEffect(() => {
    newChatCoordinator.registerEnsure(async () => {
      await createSession({ name: '' });
      await new Promise((r) => setTimeout(r, 250));
    });
    return () => newChatCoordinator.registerEnsure(undefined);
  }, [createSession]);

  // 懒创建会话的揭示：AI 首条回复完成（loading true→false）后才进列表
  const prevLoadingRef = useRef(loading);
  useEffect(() => {
    if (prevLoadingRef.current && !loading) {
      const revealed = revealHiddenSessions();
      if (revealed) setSessions(revealed);
    }
    prevLoadingRef.current = loading;
  }, [loading, setSessions]);

  // 揭示兜底：重新挂载（如切换 agent 后回来）时若仍有隐藏会话，直接展示
  useEffect(() => {
    const revealed = revealHiddenSessions();
    if (revealed) setSessions(revealed);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return props.isMobile && props.slot
    ? createPortal(
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
              title={props.agentName}
              onNavigate={() => props.onDrawerOpen(false)}
            />
          </Drawer>
        </>,
        props.slot,
      )
    : null;
}

/**
 * 对话页（Spark Design chat template：AgentScopeRuntimeWebUI，SPEC §12.1）。
 * - 会话列表 / 消息气泡 / 工具调用 / 思考过程 / Welcome 屏 / 窄屏 Drawer 均由模板内置
 * - 后端协议经 aguiBridge（AG-UI ↔ Runtime SSE）与 sessionBridge（会话索引）适配
 */
export default function Chat() {
  const [searchParams, setSearchParams] = useSearchParams();
  const currentAgent = searchParams.get('agent') || '';

  const [agents, setAgents] = useState<Agent[]>([]);
  const [loadingAgents, setLoadingAgents] = useState(true);
  const [isMobile, setIsMobile] = useState(window.innerWidth < MOBILE_BP);
  const [sessionDrawerOpen, setSessionDrawerOpen] = useState(false);
  /** 顶栏历史入口 Portal 挂载点（AppLayout header 内的空 div） */
  const [historySlot, setHistorySlot] = useState<HTMLElement | null>(null);

  /** 模板内部 sessionId 的 getter（由 ChatBridge 注入） */
  const sessionGetterRef = useRef<(() => string | undefined) | null>(null);
  const registerSessionGetter = useCallback((getter: () => string | undefined) => {
    sessionGetterRef.current = getter;
  }, []);

  useEffect(() => {
    const onResize = () => setIsMobile(window.innerWidth < MOBILE_BP);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  useEffect(() => {
    setHistorySlot(document.getElementById('topbar-history-slot'));
  }, []);

  // 加载可用 Agent 列表；未指定时默认选第一个
  useEffect(() => {
    (async () => {
      try {
        const page = await agentList({ page: 1, size: 100 });
        const list = page.list || [];
        setAgents(list);
        if (!currentAgent && list.length > 0) {
          setSearchParams({ agent: list[0].agentKey }, { replace: true });
        }
      } finally {
        setLoadingAgents(false);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const activeAgent = useMemo(
    () => agents.find((a) => a.agentKey === currentAgent),
    [agents, currentAgent],
  );

  const options = useMemo<IAgentScopeRuntimeWebUIOptions | null>(() => {
    if (!currentAgent) return null;

    const rightHeader = (
      <ChatBridge
        register={registerSessionGetter}
        isMobile={isMobile}
        slot={historySlot}
        drawerOpen={sessionDrawerOpen}
        onDrawerOpen={setSessionDrawerOpen}
        agentName={activeAgent?.name || 'Teapot AI'}
      />
    );

    return {
      api: {
        fetch: createAguiFetch({
          agentKey: currentAgent,
          getSessionId: () => sessionGetterRef.current?.(),
        }),
        // 模板实际按「每行 SSE data 字符串」调用（类型标注为 Response 是上游笔误）
        responseParser: aguiResponseParser as never,
      },
      session: {
        multiple: true,
        api: createSessionBridge(currentAgent),
        // 移动端隐藏模板内置会话列表（其头部带 "Runtime WebUI" 品牌且抽屉非自定义面板），
        // 改用 rightHeader 内的自定义抽屉；桌面端左侧栏不受影响
        hideBuiltInSessionList: isMobile,
      },
      theme: {
        locale: 'cn',
        // Carbon 黑色主题（与全局 carbonTheme 一致）
        colorPrimary: '#1a1a1d',
        // leftHeader 插槽接管为自定义会话面板（可点击切换 + 时间展示）
        leftHeader: <SessionPanel title={activeAgent?.name || 'Teapot AI'} />,
        rightHeader,
      },
      welcome: {
        greeting: '你好，有什么可以帮你？',
        nick: activeAgent?.name || 'AI 助手',
        description: activeAgent?.description || '基于 AgentScope 的智能体，支持工具调用与多轮对话。',
        prompts: [
          { value: '请介绍一下你自己' },
          { value: '你能做什么？' },
          { value: '帮我写一段 Python 快排' },
        ],
      },
      sender: {
        placeholder: '输入消息，Enter 发送',
        maxLength: 10000,
        disclaimer: '内容由 AI 生成，请注意甄别',
        // 懒创建会话：提交前无会话则先创建（含等待 loader 冲刷），规避模板内部竞态
        beforeSubmit: () =>
          newChatCoordinator.ensureSessionBeforeSubmit().then(() => true),
      },
    };
  }, [currentAgent, activeAgent, agents, isMobile, historySlot, sessionDrawerOpen, registerSessionGetter, setSearchParams]);

  if (loadingAgents) {
    return null;
  }

  if (agents.length === 0) {
    return (
      <div
        style={{
          height: '100%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <Empty description="暂无可用 Agent，请先在「Agent 管理」中创建" />
      </div>
    );
  }

  if (!options) {
    return null;
  }

  return (
    <div style={{ height: '100%' }}>
      {/* key=agentKey：切换 Agent 时整体重建，会话列表随之按新 Agent 重载 */}
      <AgentScopeRuntimeWebUI key={currentAgent} options={options} />
    </div>
  );
}
