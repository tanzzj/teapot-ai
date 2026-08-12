import { useCallback, useEffect, useMemo, useState } from 'react';
import { Drawer, Menu, Segmented, Space, theme } from 'antd';
import { Dropdown, Avatar } from '@agentscope-ai/design';
import {
  ApiOutlined,
  RobotOutlined,
  ThunderboltOutlined,
  MessageOutlined,
  TeamOutlined,
  UserOutlined,
  LogoutOutlined,
  MenuOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/auth';

const MOBILE_BP = 768;

/**
 * 主框架布局（Barley 设计语言复刻）：
 * 整站一张悬浮毛玻璃大圆角卡片；顶栏 = 汉堡(移动) + Logo + 分段标签导航 + 用户菜单。
 * SPEC §12.1：菜单按角色显隐；移动端汉堡菜单 + Drawer。
 */
export default function AppLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout, hasRole } = useAuthStore();
  const { token } = theme.useToken();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [isMobile, setIsMobile] = useState(window.innerWidth < MOBILE_BP);

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
      list.push({ key: '/models', label: '模型', icon: <ApiOutlined /> });
      list.push({ key: '/users', label: '用户', icon: <TeamOutlined /> });
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
            <span
              style={{
                width: 28,
                height: 28,
                borderRadius: 999,
                background: 'linear-gradient(135deg, #2b2b31, #1a1a1d)',
                color: '#fff',
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 15,
              }}
            >
              <RobotOutlined />
            </span>
            {!isMobile && (
              <span style={{ fontWeight: 700, fontSize: 16, color: token.colorText }}>
                Teapot AI
              </span>
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

          <Dropdown
            menu={{
              items: [
                { key: 'roles', label: `角色：${user?.roles || '-'}`, disabled: true },
                { type: 'divider' },
                { key: 'logout', label: '退出登录', icon: <LogoutOutlined /> },
              ],
              onClick: ({ key }: { key: string }) => {
                if (key === 'logout') {
                  logout();
                  navigate('/login');
                }
              },
            }}
          >
            <Space style={{ cursor: 'pointer' }}>
              <Avatar size="small" icon={<UserOutlined />} />
              {!isMobile && <span>{user?.realName || user?.username || '未登录'}</span>}
            </Space>
          </Dropdown>
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
          <div style={{ padding: '16px 16px 8px', fontWeight: 700, fontSize: 16, color: token.colorPrimary }}>
            Teapot AI
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
              <Avatar size="small" icon={<UserOutlined />} />
              <span style={{ fontSize: 13 }}>{user?.realName || user?.username || '未登录'}</span>
            </Space>
            <div style={{ fontSize: 12, color: '#999', marginTop: 4 }}>
              角色：{user?.roles || '-'}
            </div>
          </div>
        </Drawer>
      )}
    </div>
  );
}
