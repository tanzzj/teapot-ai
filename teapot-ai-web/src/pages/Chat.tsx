import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Empty, Space, Spin, Typography } from 'antd';
import { Avatar, Button, Card, Collapse, Select, Tag, message } from '@agentscope-ai/design';
import { ClearOutlined, PlusOutlined, RobotOutlined, UserOutlined } from '@ant-design/icons';
import { useSearchParams } from 'react-router-dom';
import { Conversations, Sender, Markdown } from '@agentscope-ai/chat';
import { agentList } from '../api/agent';
import { sessionClear, sessionCreate, sessionList } from '../api/session';
import { useAguiRun } from '../hooks/useAguiRun';
import type { Agent, ChatSession } from '../types';
import type { ToolCallState } from '../hooks/useAguiRun';

interface LocalMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  reasoning?: string;
  toolCalls?: ToolCallState[];
  error?: string;
  tokenUsage?: Record<string, unknown>;
}

/** 对话页（SPEC §12.2 / §12.3：会话侧栏 + AG-UI 流式对话） */
export default function Chat() {
  const [searchParams, setSearchParams] = useSearchParams();
  const urlAgent = searchParams.get('agent') || '';

  const [agents, setAgents] = useState<Agent[]>([]);
  const [agentKey, setAgentKey] = useState(urlAgent);
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [sessionId, setSessionId] = useState<string>('');
  const [messages, setMessages] = useState<LocalMessage[]>([]);
  const [input, setInput] = useState('');
  const [sessionLoading, setSessionLoading] = useState(false);
  const { ui, run, abort } = useAguiRun();
  const bottomRef = useRef<HTMLDivElement>(null);

  // 加载 Agent 列表
  useEffect(() => {
    agentList({ page: 1, size: 100 }).then((resp) => {
      setAgents(resp.list || []);
      if (!urlAgent && resp.list?.length) {
        setAgentKey(resp.list[0].agentKey);
      }
    });
  }, [urlAgent]);

  // 加载会话列表
  const loadSessions = useCallback(async (key: string) => {
    if (!key) return;
    setSessionLoading(true);
    try {
      const list = await sessionList(key);
      setSessions(list || []);
    } finally {
      setSessionLoading(false);
    }
  }, []);

  useEffect(() => {
    setMessages([]);
    setSessionId('');
    loadSessions(agentKey);
  }, [agentKey, loadSessions]);

  // 流式输出期间：把 ui 状态挂到最后一条 assistant 占位消息上
  const running = !ui.finished && !ui.error;

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, ui.answer, ui.reasoning]);

  // 收尾：流结束后把结果落入消息列表
  useEffect(() => {
    if (ui.finished && (ui.answer || ui.error || ui.reasoning || ui.toolCalls.length > 0)) {
      setMessages((prev) => {
        const last = prev[prev.length - 1];
        const done: LocalMessage = {
          id: `a-${Date.now()}`,
          role: 'assistant',
          content: ui.answer,
          reasoning: ui.reasoning || undefined,
          toolCalls: ui.toolCalls.length ? ui.toolCalls : undefined,
          error: ui.error,
          tokenUsage: ui.tokenUsage,
        };
        if (last && last.id.startsWith('stream-')) {
          return [...prev.slice(0, -1), { ...done, id: last.id }];
        }
        return [...prev, done];
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ui.finished]);

  const ensureSession = useCallback(async (): Promise<string> => {
    if (sessionId) return sessionId;
    const session = await sessionCreate(agentKey);
    setSessionId(session.sessionId);
    loadSessions(agentKey);
    return session.sessionId;
  }, [sessionId, agentKey, loadSessions]);

  const onSend = async (text: string) => {
    if (!agentKey) {
      message.warning('请先选择 Agent');
      return;
    }
    const trimmed = text.trim();
    if (!trimmed) return;
    setInput('');
    setMessages((prev) => [
      ...prev,
      { id: `u-${Date.now()}`, role: 'user', content: trimmed },
      { id: `stream-${Date.now()}`, role: 'assistant', content: '' },
    ]);
    try {
      const sid = await ensureSession();
      await run(agentKey, sid, [{ id: `m-${Date.now()}`, role: 'user', content: trimmed }]);
    } catch {
      message.error('发送失败');
    }
  };

  const conversationItems = useMemo(
    () =>
      sessions.map((s) => ({
        key: s.sessionId,
        label: s.title || `会话 ${s.sessionId.slice(0, 8)}`,
        timestamp: s.createdAt ? new Date(s.createdAt).getTime() : undefined,
      })),
    [sessions],
  );

  return (
    <div style={{ display: 'flex', height: 'calc(100vh - 64px)' }}>
      {/* 左侧：Agent 选择 + 会话列表 */}
      <div
        style={{
          width: 260,
          borderRight: '1px solid #f0f0f0',
          background: '#fff',
          display: 'flex',
          flexDirection: 'column',
          padding: 12,
          gap: 8,
        }}
      >
        <Select
          value={agentKey || undefined}
          placeholder="选择 Agent"
          style={{ width: '100%' }}
          onChange={(v) => {
            setAgentKey(v);
            setSearchParams({ agent: v });
          }}
          options={agents.map((a) => ({ label: a.name, value: a.agentKey }))}
        />
        <Button
          icon={<PlusOutlined />}
          block
          onClick={() => {
            setSessionId('');
            setMessages([]);
          }}
        >
          新会话
        </Button>
        <Spin spinning={sessionLoading} style={{ marginTop: 24 }}>
          {conversationItems.length > 0 ? (
            <Conversations
              items={conversationItems}
              activeKey={sessionId || undefined}
              onActiveChange={(key) => {
                setSessionId(key);
                setMessages([]);
              }}
              menu={[
                {
                  label: '清空记忆',
                  key: 'clear',
                  danger: true,
                  onClick: async (conv) => {
                    await sessionClear(conv.key);
                    message.success('已清空该会话记忆');
                    if (conv.key === sessionId) {
                      setMessages([]);
                    }
                  },
                },
              ]}
            />
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无会话" />
          )}
        </Spin>
      </div>

      {/* 右侧：消息流 + 输入框 */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: 16, minWidth: 0 }}>
        <div style={{ flex: 1, overflowY: 'auto', paddingRight: 8 }}>
          {messages.length === 0 && !running ? (
            <div style={{ textAlign: 'center', marginTop: 120, color: '#999' }}>
              <RobotOutlined style={{ fontSize: 48 }} />
              <div style={{ marginTop: 12 }}>选择 Agent 后开始对话</div>
            </div>
          ) : (
            [...messages].map((m) =>
              m.id.startsWith('stream-') && running ? (
                <StreamingBubble key={m.id} />
              ) : m.id.startsWith('stream-') ? null : (
                <MessageBubble key={m.id} msg={m} />
              ),
            )
          )}
          <div ref={bottomRef} />
        </div>
        <div style={{ marginTop: 12 }}>
          <Sender
            value={input}
            onChange={setInput}
            onSubmit={onSend}
            onCancel={abort}
            loading={running}
            placeholder={agentKey ? `向 ${agentKey} 提问…` : '请先选择 Agent'}
          />
        </div>
      </div>
    </div>
  );

  function StreamingBubble() {
    return (
      <Card size="small" style={{ marginBottom: 12, maxWidth: '85%' }}>
        {ui.reasoning && (
          <Collapse
            size="small"
            style={{ marginBottom: 8 }}
            items={[{ key: 'r', label: '思考过程', children: <pre style={preStyle}>{ui.reasoning}</pre> }]}
          />
        )}
        {ui.toolCalls.map((t) => (
          <Tag key={t.toolCallId} color="processing" style={{ marginBottom: 8 }}>
            🔧 {t.name} {t.result !== undefined ? '✓' : '…'}
          </Tag>
        ))}
        <Markdown content={ui.answer} cursor="dot" />
      </Card>
    );
  }
}

const preStyle: React.CSSProperties = {
  whiteSpace: 'pre-wrap',
  fontSize: 12,
  color: '#888',
  margin: 0,
  maxHeight: 200,
  overflow: 'auto',
};

function MessageBubble({ msg }: { msg: LocalMessage }) {
  const isUser = msg.role === 'user';
  return (
    <div style={{ display: 'flex', marginBottom: 12, justifyContent: isUser ? 'flex-end' : 'flex-start' }}>
      <Space align="start" style={{ flexDirection: isUser ? 'row-reverse' : 'row', maxWidth: '85%' }}>
        <Avatar
          style={{ background: isUser ? '#1677ff' : '#f0f0f0', color: isUser ? '#fff' : '#666' }}
          icon={isUser ? <UserOutlined /> : <RobotOutlined />}
        />
        <Card
          size="small"
          style={{
            background: isUser ? '#e6f4ff' : '#fff',
            borderTopRightRadius: isUser ? 2 : 8,
            borderTopLeftRadius: isUser ? 8 : 2,
          }}
        >
          {msg.reasoning && (
            <Collapse
              size="small"
              style={{ marginBottom: 8, background: '#fafafa' }}
              items={[{ key: 'r', label: '思考过程', children: <pre style={preStyle}>{msg.reasoning}</pre> }]}
            />
          )}
          {msg.toolCalls?.map((t) => (
            <Collapse
              key={t.toolCallId}
              size="small"
              style={{ marginBottom: 8, background: '#fafafa' }}
              items={[
                {
                  key: 't',
                  label: `🔧 ${t.name}`,
                  children: (
                    <div>
                      {t.args && <pre style={preStyle}>参数：{t.args}</pre>}
                      {t.result !== undefined && <pre style={preStyle}>结果：{t.result}</pre>}
                    </div>
                  ),
                },
              ]}
            />
          ))}
          {msg.error ? (
            <Typography.Text type="danger">⚠ {msg.error}</Typography.Text>
          ) : (
            <Markdown content={msg.content} />
          )}
          {msg.tokenUsage && (
            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
              tokens: {JSON.stringify(msg.tokenUsage)}
            </Typography.Text>
          )}
        </Card>
      </Space>
    </div>
  );
}
