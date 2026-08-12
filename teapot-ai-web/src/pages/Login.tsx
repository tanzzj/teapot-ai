import { useState } from 'react';
import { Button, Card, Form, Input, message, Modal } from '@agentscope-ai/design';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { login, userUpdate } from '../api/auth';
import { useAuthStore } from '../store/auth';

/** 登录页（SPEC §12.2：登录后若 usingDefaultPassword 引导改密） */
export default function Login() {
  const navigate = useNavigate();
  const setSession = useAuthStore((s) => s.setSession);
  const [loading, setLoading] = useState(false);

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      const resp = await login(values.username, values.password);
      setSession(resp.accessToken, resp.refreshToken, resp.user);
      if (resp.usingDefaultPassword) {
        Modal.confirm({
          title: '您正在使用默认密码',
          content: '为保障账户安全，建议立即修改密码。现在修改吗？',
          okText: '修改密码',
          cancelText: '稍后再说',
          onOk: () => showChangePasswordModal(resp.user.userId),
          onCancel: () => navigate('/chat'),
        });
      } else {
        navigate('/chat');
      }
    } catch {
      // http 拦截器已统一提示
    } finally {
      setLoading(false);
    }
  };

  const showChangePasswordModal = (userId: string) => {
    let newPassword = '';
    let confirmPassword = '';
    Modal.confirm({
      title: '修改密码',
      content: (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <Input.Password
            placeholder="新密码"
            onChange={(e) => {
              newPassword = e.target.value;
            }}
          />
          <Input.Password
            placeholder="确认新密码"
            onChange={(e) => {
              confirmPassword = e.target.value;
            }}
          />
        </div>
      ),
      okText: '确认修改',
      cancelText: '跳过',
      onOk: async () => {
        if (!newPassword || newPassword.length < 8) {
          message.error('新密码至少 8 位');
          return Promise.reject();
        }
        if (newPassword !== confirmPassword) {
          message.error('两次输入的密码不一致');
          return Promise.reject();
        }
        await userUpdate(userId, { newPassword });
        message.success('密码修改成功');
        navigate('/chat');
      },
      onCancel: () => navigate('/chat'),
    });
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(135deg, #f0f5ff 0%, #e6fffb 100%)',
      }}
    >
      <Card style={{ width: 380, boxShadow: '0 8px 24px rgba(0,0,0,0.08)' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <div style={{ fontSize: 26, fontWeight: 700, color: '#1677ff' }}>Teapot AI</div>
          <div style={{ color: '#999', marginTop: 4 }}>Agent 平台 · 登录</div>
        </div>
        <Form onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" autoFocus />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>
            登录
          </Button>
        </Form>
      </Card>
    </div>
  );
}
