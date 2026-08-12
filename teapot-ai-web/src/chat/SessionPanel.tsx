import { Button, Popconfirm } from '@agentscope-ai/design';
import { SparkDeleteLine, SparkPlusLine } from '@agentscope-ai/icons';
import {
  useChatAnywhereSessions,
  useChatAnywhereSessionsState,
} from '@agentscope-ai/chat';
import type { SessionItem } from './sessionBridge';

/**
 * 自定义会话面板（接管模板 leftHeader 插槽）。
 * 模板内置列表项依赖 IntersectionObserver 渲染且无时间槽位，
 * 这里自渲染普通 div：点击必然触发切换，并展示最后活跃时间。
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

export default function SessionPanel(props: { title: string }) {
  const { sessions, currentSessionId } = useChatAnywhereSessionsState();
  const { createSession, changeCurrentSessionId, removeSession } =
    useChatAnywhereSessions();
  const list = (sessions || []) as SessionItem[];

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        minHeight: 0,
        padding: '12px 10px',
        gap: 10,
      }}
    >
      <div style={{ fontWeight: 700, fontSize: 15, padding: '0 4px' }}>
        {props.title}
      </div>

      <Button
        block
        type="primary"
        icon={<SparkPlusLine />}
        onClick={() => createSession()}
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
          gap: 4,
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
              onClick={() => changeCurrentSessionId(s.id)}
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
