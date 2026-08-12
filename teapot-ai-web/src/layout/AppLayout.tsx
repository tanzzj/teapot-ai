import { useMemo } from 'react';
import { Layout, Menu, Space, theme } from 'antd';
import { Dropdown, Avatar } from '@agentscope-ai/design';
import {
  ApiOutlined,
  RobotOutlined,
  ThunderboltOutlined,
  MessageOutlined,
  TeamOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/auth';

const { Header, Content } = Layout;

/** 主框架布局（SPEC §12.1：菜单按角色显隐） */
export default function AppLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout, hasRole } = useAuthStore();
  const { token } = theme.useToken();

  const items = useMemo(() => {
    const list: { key: string; label: string; icon: React.ReactNode }[] = [
      { key: '/chat', label: '对话', icon: <MessageOutlined /> },
    ];
    if (hasRole('admin', 'developer')) {
      list.push({ key: '/agents', label: 'Agent 管理', icon: <RobotOutlined /> });
      list.push({ key: '/skills', label: 'Skill 工坊', icon: <ThunderboltOutlined /> });
    }
    if (hasRole('admin')) {
      list.push({ key: '/models', label: '模型入口', icon: <ApiOutlined /> });
      list.push({ key: '/users', label: '用户管理', icon: <TeamOutlined /> });
    }
    return list;
  }, [hasRole]);

  const selectedKey = items.find((i) => location.pathname.startsWith(i.key))?.key || '/chat';

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header
        style={{
          display: 'flex',
          alignItems: 'center',
          background: 'rgba(255, 255, 255, 0.72)',
          backdropFilter: 'blur(16px)',
          WebkitBackdropFilter: 'blur(16px)',
          borderBottom: `1px solid ${token.colorBorderSecondary}`,
          paddingInline: 24,
          position: 'sticky',
          top: 0,
          zIndex: 100,
        }}
      >
        <div style={{ fontWeight: 700, fontSize: 18, marginRight: 32, color: token.colorPrimary }}>
          Teapot AI
        </div>
        <Menu
          mode="horizontal"
          selectedKeys={[selectedKey]}
          items={items}
          onClick={({ key }) => navigate(key)}
          style={{ flex: 1, minWidth: 0, borderBottom: 'none' }}
        />
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
            <span>{user?.realName || user?.username || '未登录'}</span>
          </Space>
        </Dropdown>
      </Header>
      <Content style={{ background: token.colorBgLayout }}>
        <Outlet />
      </Content>
    </Layout>
  );
}
