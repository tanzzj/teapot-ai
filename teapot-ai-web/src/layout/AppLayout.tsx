import { useEffect, useMemo, useState } from 'react';
import { Menu, Segmented, theme, Dropdown } from 'antd';
import { Button } from '@agentscope-ai/design';
import {
  SparkRoboticsLine,
  SparkMagicWandLine,
  SparkMessageLine,
  SparkSettingLine,
  SparkCircleArrowDownLine,
} from '@agentscope-ai/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/auth';
import { useMobileUIStore } from '../store/mobileUI';
import { PHONE_BP } from '../theme/breakpoints';
import AgentSelector from './AgentSelector';
import UserMenu from './UserMenu';

/**
 * 主框架布局（Barley 设计语言复刻）：
 * 整站一张悬浮毛玻璃大圆角卡片；顶栏 = 汉堡(移动) + Logo + 分段标签导航 + 用户菜单。
 * SPEC §12.1：菜单按角色显隐；移动端汉堡菜单 + Drawer。
 */
export default function AppLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { hasRole } = useAuthStore();
  const { token } = theme.useToken();
  const { mobileView, sessionTitle } = useMobileUIStore();
  const [isMobile, setIsMobile] = useState(window.innerWidth < PHONE_BP);
  /** 可视视口高度（visualViewport）：部分手机浏览器（Edge 等）底部地址栏浮在页面上方，
   * 100dvh 仍按全高计算导致底部内容被遮挡；改用实际可视高度保证底部可见 */
  const [vvHeight, setVvHeight] = useState<number | null>(null);

  useEffect(() => {
    const onResize = () => setIsMobile(window.innerWidth < PHONE_BP);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  useEffect(() => {
    const vv = window.visualViewport;
    if (!vv) {
      return;
    }
    const update = () => setVvHeight(vv.height);
    update();
    vv.addEventListener('resize', update);
    vv.addEventListener('scroll', update);
    return () => {
      vv.removeEventListener('resize', update);
      vv.removeEventListener('scroll', update);
    };
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

  /** 对话页：用户信息 + 系统配置入口已下沉到左栏底部（UserFooter），顶栏导航同步去掉「系统配置」 */
  const isChatPage = location.pathname.startsWith('/chat');
  const navItems = isChatPage ? items.filter((i) => i.key !== '/system') : items;

  /** 移动端下拉菜单选项（对话/Agent/Skill/系统配置） */
  const mobileMenuItems = useMemo(() => {
    return navItems.map((i) => ({
      key: i.key,
      label: i.label,
      icon: i.icon,
      onClick: () => navigate(i.key),
    }));
  }, [navItems, navigate]);

  // 移动端导航已改顶栏下拉菜单，无 Drawer 打开态需随路由关闭

  return (
    <div style={{ height: vvHeight ?? '100dvh', padding: isMobile ? 6 : 12, boxSizing: 'border-box' }}>
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
                    {navItems.find((i) => i.key === selectedKey)?.label || '对话'}
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
                options={navItems.map((i) => ({ value: i.key, label: i.label, icon: i.icon }))}
              />
            </>
          )}

          {/* 手机聊天态：标题容器已 flex:1 吃满剩余宽度，占位符不再参与分推，避免标题被提前截断 */}
          <div style={{ flex: isMobile && mobileView === 'chat' ? 'none' : 1 }} />

          {/* 会话历史入口的 Portal 挂载点（始终存在，内容根据 mobileView 变化） */}
          <div id="topbar-history-slot" style={{ display: 'flex', alignItems: 'center' }} />

          {/* 对话页顶栏 Agent 选择器（原聊天区右上角，移至全局 header） */}
          <AgentSelector />

          {/* 用户菜单：对话页已下沉到左栏底部 UserFooter（连同系统配置入口），顶栏不再展示 */}
          {!isChatPage && !(isMobile && mobileView === 'chat') && (
            <UserMenu showName={!isMobile} />
          )}
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
