import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Empty } from 'antd';
import { Select } from '@agentscope-ai/design';
import { SparkAgentLine } from '@agentscope-ai/icons';
import {
  AgentScopeRuntimeWebUI,
  useChatAnywhereSessions,
} from '@agentscope-ai/chat';
import type { IAgentScopeRuntimeWebUIOptions } from '@agentscope-ai/chat';
import { agentList } from '../api/agent';
import { aguiResponseParser, createAguiFetch } from '../chat/aguiBridge';
import { createSessionBridge } from '../chat/sessionBridge';
import SessionPanel from '../chat/SessionPanel';
import type { Agent } from '../types';

const MOBILE_BP = 768;

/**
 * 会话 id 桥接器：渲染在 ChatAnywhere Provider 内部（rightHeader 插槽），
 * 把模板当前的 sessionId getter 暴露给外层 fetch 闭包（作 AG-UI threadId）。
 */
function SessionIdBridge(props: { register: (getter: () => string | undefined) => void }) {
  const { getCurrentSessionId } = useChatAnywhereSessions();
  const { register } = props;
  useEffect(() => {
    register(getCurrentSessionId);
  }, [register, getCurrentSessionId]);
  return null;
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

  /** 模板内部 sessionId 的 getter（由 SessionIdBridge 注入） */
  const sessionGetterRef = useRef<(() => string | undefined) | null>(null);
  const registerSessionGetter = useCallback((getter: () => string | undefined) => {
    sessionGetterRef.current = getter;
  }, []);

  useEffect(() => {
    const onResize = () => setIsMobile(window.innerWidth < MOBILE_BP);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
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
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <SessionIdBridge register={registerSessionGetter} />
        <SparkAgentLine size={16} style={{ color: '#666' }} />
        <Select
          value={currentAgent}
          options={agents.map((a) => ({ label: a.name, value: a.agentKey }))}
          onChange={(v) => setSearchParams({ agent: v })}
          style={{ width: isMobile ? 140 : 220 }}
          size={isMobile ? 'small' : 'middle'}
        />
      </div>
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
      },
    };
  }, [currentAgent, activeAgent, agents, isMobile, registerSessionGetter, setSearchParams]);

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
