import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Drawer, Menu, Segmented, Space, message, theme } from 'antd';
import { Button, Dropdown, Avatar } from '@agentscope-ai/design';
import {
  RobotOutlined,
  ThunderboltOutlined,
  MessageOutlined,
  SettingOutlined,
  UserOutlined,
  LogoutOutlined,
  MenuOutlined,
  CameraOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/auth';
import { uploadUserAvatar } from '../api/avatar';
import AgentSelector from './AgentSelector';

const MOBILE_BP = 768;

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
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [isMobile, setIsMobile] = useState(window.innerWidth < MOBILE_BP);
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
    const onResize = () => setIsMobile(window.innerWidth < MOBILE_BP);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const items = useMemo(() => {
    const list: { key: string; label: string; icon: React.ReactNode }[] = [
      { key: '/chat', label: '对话', icon: <MessageOutlined /> },
    ];
    if (hasRole('admin', 'developer')) {
      list.push({ key: '/agents', label: 'Agent', icon: <RobotOutlined /> });
      list.push({ key: '/skills', label: 'Skill', icon: <ThunderboltOutlined /> });
    }
    if (hasRole('admin')) {
      // 系统配置一站式管理台（SPEC §21）：模型/用户/存储/沙箱统一入口
      list.push({ key: '/system', label: '系统配置', icon: <SettingOutlined /> });
    }
    return list;
  }, [hasRole]);

  const selectedKey = items.find((i) => location.pathname.startsWith(i.key))?.key || '/chat';

  const onMenuClick = useCallback(
    ({ key }: { key: string }) => {
      navigate(key);
      setDrawerOpen(false);
    },
    [navigate],
  );

  // 路由切换时关闭 Drawer
  useEffect(() => {
    setDrawerOpen(false);
  }, [location.pathname]);

  return (
    <div style={{ height: '100dvh', padding: isMobile ? 6 : 12 }}>
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
          {isMobile && (
            <MenuOutlined
              style={{ fontSize: 17, cursor: 'pointer', color: token.colorText }}
              onClick={() => setDrawerOpen(true)}
            />
          )}

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
            {!isMobile && (
              <span style={{ fontWeight: 700, fontSize: 16, color: token.colorText }}>
                Teapot AI
              </span>
            )}
            {!isMobile && (
              <span style={{ fontSize: 10, color: '#bbb', marginLeft: 2 }}>v0820a</span>
            )}
          </div>

          {/* 分段标签导航（Barley Chat/Agent 切换样式） */}
          {!isMobile && (
            <Segmented
              value={selectedKey}
              onChange={(v) => navigate(String(v))}
              options={items.map((i) => ({ value: i.key, label: i.label, icon: i.icon }))}
            />
          )}

          <div style={{ flex: 1 }} />

          {/* 移动端会话历史入口的 Portal 挂载点（Chat 页注入，见 Chat.tsx） */}
          <div id="topbar-history-slot" style={{ display: 'flex', alignItems: 'center' }} />

          {/* 对话页顶栏 Agent 选择器（原聊天区右上角，移至全局 header） */}
          <AgentSelector />

          <Dropdown
            trigger={['click']}
            menu={{
              items: [
                { key: 'roles', label: `角色：${user?.roles || '-'}`, disabled: true },
                { type: 'divider' },
                {
                  key: 'avatar',
                  label: avatarUploading ? '头像上传中…' : '更换头像',
                  icon: <CameraOutlined />,
                  disabled: avatarUploading,
                },
                { key: 'logout', label: '退出登录', icon: <LogoutOutlined /> },
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
              <Avatar size="small" src={user?.avatar || undefined} icon={<UserOutlined />} />
              {!isMobile && <span>{user?.realName || user?.username || '未登录'}</span>}
            </Space>
          </Dropdown>
          {/* 头像选择器（SPEC §23）：Dropdown 菜单项触发 */}
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

      {/* 移动端：Drawer 菜单 */}
      {isMobile && (
        <Drawer
          placement="left"
          open={drawerOpen}
          onClose={() => setDrawerOpen(false)}
          width={240}
          styles={{ body: { padding: 0 } }}
          closable={false}
        >
          <div style={{ padding: '16px 16px 8px', display: 'flex', alignItems: 'center', gap: 8 }}>
            <img src="/logo.png" alt="Teapot AI" style={{ width: 24, height: 24, borderRadius: 999 }} />
            <span style={{ fontWeight: 700, fontSize: 16, color: token.colorPrimary }}>Teapot AI</span>
          </div>
          <Menu
            mode="vertical"
            selectedKeys={[selectedKey]}
            items={items}
            onClick={onMenuClick}
            style={{ borderRight: 'none' }}
          />
          <div style={{ padding: '12px 16px', borderTop: '1px solid #f0f0f0', marginTop: 8 }}>
            <Space>
              <Avatar size="small" src={user?.avatar || undefined} icon={<UserOutlined />} />
              <span style={{ fontSize: 13 }}>{user?.realName || user?.username || '未登录'}</span>
            </Space>
            <div style={{ fontSize: 12, color: '#999', marginTop: 4 }}>
              角色：{user?.roles || '-'}
            </div>
            <Button
              block
              style={{ marginTop: 12 }}
              icon={<LogoutOutlined />}
              onClick={() => {
                logout();
                setDrawerOpen(false);
                navigate('/login');
              }}
            >
              退出登录
            </Button>
          </div>
        </Drawer>
      )}
    </div>
  );
}
