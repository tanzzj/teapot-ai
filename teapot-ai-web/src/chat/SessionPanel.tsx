import { Button, Popconfirm } from '@agentscope-ai/design';
import { SparkDeleteLine, SparkPlusLine } from '@agentscope-ai/icons';
import {
  useChatAnywhereSessions,
  useChatAnywhereSessionsState,
} from '@agentscope-ai/chat';
import type { SessionItem } from './sessionBridge';

/**
 * 自定义会话面板（桌面接管模板 leftHeader 插槽 / 移动端置于自定义抽屉）。
 * 懒创建与揭示接线在 ChatBridge（常驻挂载），这里纯展示与交互。
 */

function formatTime(ts?: string) {
  if (!ts) return '';
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return '';
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  return d.toDateString() === now.toDateString()
    ? hm
    : `${d.getMonth() + 1}/${d.getDate()} ${hm}`;
}

export default function SessionPanel(props: { title: string; onNavigate?: () => void }) {
  const { sessions, currentSessionId } = useChatAnywhereSessionsState();
  const { changeCurrentSessionId, removeSession } = useChatAnywhereSessions();
  const list = (sessions || []) as SessionItem[];

  // New Chat 不立即创建后端会话：清空当前会话回到欢迎页，
  // 用户发送第一条消息时由 beforeSubmit 触发创建（标题由模板改为首条消息）
  const handleNewChat = () => {
    changeCurrentSessionId(undefined as unknown as string);
    props.onNavigate?.();
  };

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        minHeight: 0,
        padding: '12px 14px',
        gap: 10,
      }}
    >
      <div style={{ fontWeight: 700, fontSize: 15, padding: '0 4px' }}>
        {props.title}
      </div>

      <Button
        block
        type="primary"
        className="teapot-new-chat-btn"
        icon={<SparkPlusLine />}
        onClick={handleNewChat}
      >
        New Chat
      </Button>

      <div
        style={{
          flex: 1,
          minHeight: 0,
          overflowY: 'auto',
          display: 'flex',
          flexDirection: 'column',
          gap: 6,
          // 给选中态白卡 + 阴影留出呼吸空间，避免贴边/被裁剪的观感
          padding: '2px 2px',
        }}
      >
        {list.length === 0 && (
          <div style={{ color: '#999', fontSize: 12, textAlign: 'center', padding: '24px 0' }}>
            暂无会话，点击上方新建
          </div>
        )}
        {list.map((s) => {
          const active = s.id === currentSessionId;
          return (
            <div
              key={s.id}
              className="teapot-session-item"
              data-active={active ? '1' : '0'}
              onClick={() => {
                changeCurrentSessionId(s.id);
                props.onNavigate?.();
              }}
              style={{
                position: 'relative',
                padding: '8px 12px',
                borderRadius: 10,
                cursor: 'pointer',
              }}
            >
              <div
                style={{
                  fontSize: 13.5,
                  fontWeight: active ? 600 : 400,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  paddingRight: 22,
                }}
              >
                {s.name || '新会话'}
              </div>
              {s.updatedAt && (
                <div style={{ fontSize: 11.5, color: '#999', marginTop: 2 }}>
                  {formatTime(s.updatedAt)}
                </div>
              )}
              <Popconfirm
                title="删除该会话？"
                okText="删除"
                cancelText="取消"
                onConfirm={() => removeSession({ id: s.id })}
              >
                <span
                  className="teapot-session-del"
                  onClick={(e) => e.stopPropagation()}
                  style={{
                    position: 'absolute',
                    right: 8,
                    top: 9,
                    color: '#bbb',
                    display: 'inline-flex',
                  }}
                >
                  <SparkDeleteLine size={14} />
                </span>
              </Popconfirm>
            </div>
          );
        })}
      </div>
    </div>
  );
}
