import { useEffect } from 'react';
import { Navigate, Outlet, Route, Routes } from 'react-router-dom';
import { Result, Spin } from 'antd';
import { profile } from './api/auth';
import { useAuthStore } from './store/auth';
import AppLayout from './layout/AppLayout';
import Login from './pages/Login';
import Chat from './pages/Chat';
import Agents from './pages/Agents';
import AgentDetailPage from './pages/AgentDetail';
import Skills from './pages/Skills';
import SkillDetailPage from './pages/SkillDetail';
import Users from './pages/Users';
import Models from './pages/Models';

/** 登录守卫：无 token 跳登录；有 token 但刷新丢失用户态时拉 profile 恢复 */
function RequireAuth() {
  const { loggedIn, user, setSession } = useAuthStore();

  useEffect(() => {
    if (loggedIn && !user) {
      profile()
        .then((u) => {
          useAuthStore.setState({ user: u });
        })
        .catch(() => undefined);
    }
  }, [loggedIn, user, setSession]);

  if (!loggedIn) {
    return <Navigate to="/login" replace />;
  }
  if (loggedIn && !user) {
    return (
      <div style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Spin size="large" tip="加载用户信息…" />
      </div>
    );
  }
  return <Outlet />;
}

/** 角色守卫 */
function RequireRole({ roles }: { roles: string[] }) {
  const hasRole = useAuthStore((s) => s.hasRole);
  if (!hasRole(...roles)) {
    return <Result status="403" title="403" subTitle="您没有权限访问该页面" />;
  }
  return <Outlet />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route index element={<Navigate to="/chat" replace />} />
          <Route path="/chat" element={<Chat />} />
          <Route element={<RequireRole roles={['admin', 'developer']} />}>
            <Route path="/agents" element={<Agents />} />
            <Route path="/agents/:agentKey" element={<AgentDetailPage />} />
            <Route path="/skills" element={<Skills />} />
            <Route path="/skills/new" element={<SkillDetailPage />} />
            <Route path="/skills/:name" element={<SkillDetailPage />} />
          </Route>
          <Route element={<RequireRole roles={['admin']} />}>
            <Route path="/users" element={<Users />} />
            <Route path="/models" element={<Models />} />
          </Route>
          <Route path="*" element={<Navigate to="/chat" replace />} />
        </Route>
      </Route>
    </Routes>
  );
}
