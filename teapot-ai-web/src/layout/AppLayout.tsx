import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Menu, Segmented, Space, message, theme, Dropdown } from 'antd';
import { Button, Avatar } from '@agentscope-ai/design';
import {
  SparkRoboticsLine,
  SparkMagicWandLine,
  SparkMessageLine,
  SparkSettingLine,
  SparkUserLine,
  SparkEscapeLine,
  SparkMenuLine,
  SparkCameraLine,
  SparkCircleArrowDownLine,
} from '@agentscope-ai/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/auth';
import { useMobileUIStore } from '../store/mobileUI';
import { uploadUserAvatar } from '../api/avatar';
import { PHONE_BP } from '../theme/breakpoints';
import AgentSelector from './AgentSelector';

/**
 * 主框架布局（Barley 设计语言复刻）：
 * 整站一张悬浮毛玻璃大圆角卡片；顶栏 = 汉堡(移动) + Logo + 分段标签导航 + 用户菜单。
 * SPEC §12.1：菜单按角色显隐；移动端汉堡菜单 + Drawer。
 */
export default function AppLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout, hasRole, setUserPatch } = useAuthStore();
  const { token } = theme.useToken();
  const { mobileView, sessionTitle } = useMobileUIStore();
  const [isMobile, setIsMobile] = useState(window.innerWidth < PHONE_BP);
  const [avatarUploading, setAvatarUploading] = useState(false);
  const avatarInputRef = useRef<HTMLInputElement>(null);

  /** 用户头像上传（SPEC §23）：客户端预检 2MB/图片类型，服务端复检 */
  const onAvatarFile = useCallback(
    async (file: File | undefined) => {
      if (!file || avatarUploading) {
        return;
      }
      if (!file.type.startsWith('image/')) {
        message.error('仅支持图片格式头像');
        return;
      }
      if (file.size > 2 * 1024 * 1024) {
        message.error('头像超过 2MB 限制');
        return;
      }
      setAvatarUploading(true);
      try {
        const { url } = await uploadUserAvatar(file);
        setUserPatch({ avatar: url });
        message.success('头像已更新');
      } catch (e) {
        message.error(e instanceof Error ? e.message : '头像上传失败');
      } finally {
        setAvatarUploading(false);
        if (avatarInputRef.current) {
          avatarInputRef.current.value = '';
        }
      }
    },
    [avatarUploading, setUserPatch],
  );

  useEffect(() => {
    const onResize = () => setIsMobile(window.innerWidth < PHONE_BP);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const items = useMemo(() => {
    const list: { key: string; label: string; icon: React.ReactNode }[] = [
      { key: '/chat', label: '对话', icon: <SparkMessageLine /> },
    ];
    if (hasRole('admin', 'developer')) {
      list.push({ key: '/agents', label: 'Agent', icon: <SparkRoboticsLine /> });
      list.push({ key: '/skills', label: 'Skill', icon: <SparkMagicWandLine /> });
    }
    if (hasRole('admin')) {
      // 系统配置一站式管理台（SPEC §21）：模型/用户/存储/沙箱统一入口
      list.push({ key: '/system', label: '系统配置', icon: <SparkSettingLine /> });
    }
    return list;
  }, [hasRole]);

  const selectedKey = items.find((i) => location.pathname.startsWith(i.key))?.key || '/chat';

  /** 移动端下拉菜单选项（对话/Agent/Skill/系统配置） */
  const mobileMenuItems = useMemo(() => {
    return items.map((i) => ({
      key: i.key,
      label: i.label,
      icon: i.icon,
      onClick: () => navigate(i.key),
    }));
  }, [items, navigate]);

  const onMenuClick = useCallback(
    ({ key }: { key: string }) => {
      navigate(key);
    },
    [navigate],
  );

  // 移动端导航已改顶栏下拉菜单，无 Drawer 打开态需随路由关闭

  return (
    <div style={{ height: '100dvh', padding: isMobile ? 6 : 12, boxSizing: 'border-box' }}>
      {/* 悬浮毛玻璃大卡片（Barley 应用容器） */}
      <div
        className="glass-panel"
        style={{
          height: '100%',
          borderRadius: isMobile ? 14 : 20,
          overflow: 'hidden',
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        {/* 顶栏 */}
        <header
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: isMobile ? 8 : 16,
            padding: isMobile ? '0 10px' : '0 20px',
            height: 56,
            flexShrink: 0,
            borderBottom: '1px solid rgba(255, 255, 255, 0.55)',
          }}
        >
          {/* 移动端：Logo + 下拉切换菜单（首页态）或 返回按钮 + 会话标题（聊天态） */}
          {isMobile && mobileView === 'home' && (
            <>
              <img
                src="/logo.png"
                alt="Teapot AI"
                style={{ width: 28, height: 28, borderRadius: 999, display: 'block', flexShrink: 0 }}
              />
              <Dropdown
                menu={{ items: mobileMenuItems }}
                trigger={['click']}
                overlayClassName="teapot-mobile-menu"
                overlayStyle={{ minWidth: 240 }}
              >
                <Button
                  type="text"
                  style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '8px 10px', fontSize: 16 }}
                >
                  <span style={{ fontWeight: 600 }}>
                    {items.find((i) => i.key === selectedKey)?.label || '对话'}
                  </span>
                  <SparkCircleArrowDownLine size={14} />
                </Button>
              </Dropdown>
            </>
          )}
          {isMobile && mobileView === 'chat' && (
            <div style={{ flex: 1, minWidth: 0, display: 'flex', alignItems: 'center', gap: 8 }}>
              <div id="topbar-chat-slot" style={{ display: 'flex', alignItems: 'center' }} />
              <span
                style={{
                  flex: 1,
                  minWidth: 0,
                  fontSize: 15,
                  fontWeight: 600,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
                title={sessionTitle}
              >
                {sessionTitle}
              </span>
            </div>
          )}

          {/* 桌面端：汉堡菜单（无）+ Logo + Segmented 导航 */}
          {!isMobile && (
            <>
              {/* Logo */}
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  cursor: 'pointer',
                  whiteSpace: 'nowrap',
                }}
                onClick={() => navigate('/chat')}
              >
                <img
                  src="/logo.png"
                  alt="Teapot AI"
                  style={{ width: 30, height: 30, borderRadius: 999, display: 'block' }}
                />
                <span style={{ fontWeight: 700, fontSize: 16, color: token.colorText }}>
                  Teapot AI
                </span>
                <span style={{ fontSize: 10, color: '#bbb', marginLeft: 2 }}>v0820a</span>
              </div>

              {/* 分段标签导航（Barley Chat/Agent 切换样式） */}
              <Segmented
                value={selectedKey}
                onChange={(v) => navigate(String(v))}
                options={items.map((i) => ({ value: i.key, label: i.label, icon: i.icon }))}
              />
            </>
          )}

          {/* 手机聊天态：标题容器已 flex:1 吃满剩余宽度，占位符不再参与分推，避免标题被提前截断 */}
          <div style={{ flex: isMobile && mobileView === 'chat' ? 'none' : 1 }} />

          {/* 会话历史入口的 Portal 挂载点（始终存在，内容根据 mobileView 变化） */}
          <div id="topbar-history-slot" style={{ display: 'flex', alignItems: 'center' }} />

          {/* 对话页顶栏 Agent 选择器（原聊天区右上角，移至全局 header） */}
          <AgentSelector />

          {/* 移动端聊天态：隐藏头像（SPEC：mobile chat 模式不需要展示登录人状态） */}
          {!(isMobile && mobileView === 'chat') && (
            <Dropdown
              trigger={['click']}
              menu={{
                items: [
                  { key: 'roles', label: `角色：${user?.roles || '-'}`, disabled: true },
                  { type: 'divider' },
                  {
                    key: 'avatar',
                    label: avatarUploading ? '头像上传中…' : '更换头像',
                    icon: <SparkCameraLine />,
                    disabled: avatarUploading,
                  },
                  { key: 'logout', label: '退出登录', icon: <SparkEscapeLine /> },
                ],
                onClick: ({ key }: { key: string }) => {
                  if (key === 'logout') {
                    logout();
                    navigate('/login');
                  } else if (key === 'avatar') {
                    avatarInputRef.current?.click();
                  }
                },
              }}
            >
              <Space style={{ cursor: 'pointer' }}>
                <Avatar size="small" src={user?.avatar || undefined} icon={<SparkUserLine />} />
                {!isMobile && <span>{user?.realName || user?.username || '未登录'}</span>}
              </Space>
            </Dropdown>
          )}
          {/* 头像选择器（SPEC §23）：Dropdown 菜单项触发；
              原生 input[file] 为浏览器文件选择唯一通道，豁免于禁用原生表单标签规则（SPEC-mobile M8） */}
          <input
            ref={avatarInputRef}
            type="file"
            accept="image/jpeg,image/png,image/webp,image/gif"
            style={{ display: 'none' }}
            onChange={(e) => onAvatarFile(e.target.files?.[0])}
          />
        </header>

        {/* 内容区 */}
        <main style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
          <Outlet />
        </main>
      </div>

      {/* 移动端导航采用顶栏下拉菜单（产品决策，见 SPEC-mobile A3） */}
    </div>
  );
}
