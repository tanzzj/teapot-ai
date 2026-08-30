import { AlertDialog, Button, MobileAlertDialog, message } from '@agentscope-ai/design';
import { SparkCopyLine, SparkDeleteLine, SparkMoreLine, SparkPlusLine } from '@agentscope-ai/icons';
import { Dropdown } from 'antd';
import { useMemo } from 'react';
import {
  useChatAnywhereSessions,
  useChatAnywhereSessionsState,
} from '@agentscope-ai/chat';
import type { SessionItem } from './sessionBridge';
import { useIsPhone } from '../hooks/useIsPhone';

/**
 * 自定义会话面板（桌面接管模板 leftHeader 插槽 / 移动端置于自定义抽屉）。
 * 懒创建与揭示接线在 ChatBridge（常驻挂载），这里纯展示与交互。
 */

/** 复制会话 id：http 非安全上下文无 navigator.clipboard，execCommand 兜底 */
function copyText(text: string) {
  const done = () => message.success('已复制 Session ID');
  const fallback = () => {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
    done();
  };
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(text).then(done).catch(fallback);
  } else {
    fallback();
  }
}

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

/** 会话时间分组标签：按自然日距今天数划分（无时间归入更早） */
function groupLabel(ts: number) {
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return '更早';
  const now = new Date();
  const dayStart = (dt: Date) => new Date(dt.getFullYear(), dt.getMonth(), dt.getDate()).getTime();
  const diffDays = Math.floor((dayStart(now) - dayStart(d)) / 86400000);
  if (diffDays <= 0) return '今天';
  if (diffDays === 1) return '昨天';
  if (diffDays < 7) return '最近 7 天';
  if (diffDays < 30) return '最近 30 天';
  return '更早';
}

export default function SessionPanel(props: { title: string; onNavigate?: () => void; readonly?: boolean; flat?: boolean; footer?: React.ReactNode }) {
  const { sessions, currentSessionId } = useChatAnywhereSessionsState();
  const { changeCurrentSessionId, removeSession } = useChatAnywhereSessions();
  const list = (sessions || []) as SessionItem[];
  const isPhone = useIsPhone();

  // 按更新时间倒序（后端返回顺序不保证），再按时间分组展示（今天/昨天/最近 7 天…）
  const sorted = useMemo(
    () =>
      [...list].sort(
        (a, b) =>
          Date.parse(b.updatedAt || '') - Date.parse(a.updatedAt || '') || 0,
      ),
    [list],
  );

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
      {props.title && (
        <div style={{ fontWeight: 700, fontSize: 15, padding: '0 4px' }}>
          {props.title}
        </div>
      )}

      {!props.readonly && (
        <Button
          block
          type="primary"
          className="teapot-new-chat-btn"
          icon={<SparkPlusLine />}
          onClick={handleNewChat}
        >
          New Chat
        </Button>
      )}

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
        {sorted.length === 0 && (
          <div style={{ color: '#999', fontSize: 12, textAlign: 'center', padding: '24px 0' }}>
            暂无会话，点击上方新建
          </div>
        )}
        {(() => {
          let lastGroup = '';
          return sorted.map((s) => {
            const group = groupLabel(Date.parse(s.updatedAt || ''));
            const header = group !== lastGroup;
            lastGroup = group;
            const active = s.id === currentSessionId;
            return (
              <div key={s.id}>
                {header && (
                  <div style={{ fontSize: 11, color: '#999', padding: '6px 4px 0' }}>
                    {group}
                  </div>
                )}
                <div
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
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, paddingRight: 44, minWidth: 0 }}>
                <div
                  style={{
                    fontSize: 13.5,
                    // 移动端首页扁平样式（flat）：不区分选中态字重；选中背景已由 CSS 媒体查询取消，
                    // hover 背景同理。仅保留字重差异给桌面端选中卡（非 flat）使用。
                    fontWeight: !props.flat && active ? 600 : 400,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                    flex: 1,
                    minWidth: 0,
                  }}
                >
                  {s.name || '新会话'}
                </div>
                {/* 会话历史场景：渠道来源用小胶囊标注（Web 不打标） */}
                {s.source && s.source !== 'web' && (
                  <span
                    style={{
                      flexShrink: 0,
                      fontSize: 10,
                      lineHeight: '16px',
                      padding: '0 7px',
                      borderRadius: 999,
                      background: 'rgba(26, 26, 29, 0.06)',
                      color: 'rgba(26, 26, 29, 0.55)',
                    }}
                  >
                    {s.source === 'discord' ? 'Discord' : s.source === 'dingtalk' ? '钉钉' : s.source}
                  </span>
                )}
              </div>
              {s.updatedAt && (
                <div
                  style={{
                    fontSize: 11.5,
                    color: '#999',
                    marginTop: 2,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {s.source ? `${s.userId} · ${formatTime(s.updatedAt)}` : formatTime(s.updatedAt)}
                </div>
              )}
              {/* hover 操作组：… 更多按钮，下拉选择复制 Session ID / 删除（历史场景走后端真删） */}
              <span
                className="teapot-session-actions"
                onClick={(e) => e.stopPropagation()}
                style={{
                  position: 'absolute',
                  right: 6,
                  top: 6,
                  color: '#bbb',
                  display: 'inline-flex',
                  alignItems: 'center',
                }}
              >
                <Dropdown
                  trigger={['click']}
                  menu={{
                    items: [
                      { key: 'copy', label: '复制', icon: <SparkCopyLine size={14} /> },
                      { key: 'delete', label: '删除', icon: <SparkDeleteLine size={14} />, danger: true },
                    ],
                    onClick: ({ key, domEvent }) => {
                      domEvent.stopPropagation();
                      if (key === 'copy') {
                        copyText(s.sessionId || (s.id as string));
                      } else if (key === 'delete') {
                        // 删除确认（SPEC-mobile M3）：design 包 AlertDialog，
                        // 手机端走 MobileAlertDialog（自带滚动锁与移动端规格）
                        const confirm = isPhone ? MobileAlertDialog.confirm : AlertDialog.confirm;
                        confirm({
                          title: '删除该会话？',
                          okText: '删除',
                          cancelText: '取消',
                          okButtonProps: { danger: true },
                          onOk: () => removeSession({ id: s.id }),
                        });
                      }
                    },
                  }}
                >
                  <span
                    title="更多操作"
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      width: 24,
                      height: 24,
                      borderRadius: 6,
                      cursor: 'pointer',
                    }}
                  >
                    <SparkMoreLine size={16} />
                  </span>
                </Dropdown>
              </span>
                </div>
              </div>
            );
          });
        })()}
      </div>
      {/* 底部插槽：对话页挂用户信息 + 系统配置入口（UserFooter），历史/只读场景不传 */}
      {props.footer}
    </div>
  );
}
