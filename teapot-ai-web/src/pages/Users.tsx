import { useCallback, useEffect, useState } from 'react';
import { Space } from 'antd';
import { Button, Form, Input, message, Popconfirm, Select, Table, Tag } from '@agentscope-ai/design';
import { SparkPlusLine } from '@agentscope-ai/icons';
import { userCreate, userDisable, userPage, userUpdate } from '../api/auth';
import { useAuthStore } from '../store/auth';
import type { TeapotUser } from '../types';
import { useIsPhone } from '../hooks/useIsPhone';
import { ResponsiveModal } from '../components/ResponsiveModal';

const ROLE_OPTIONS = [
  { label: 'admin（管理员）', value: 'admin' },
  { label: 'developer（开发者）', value: 'developer' },
  { label: 'viewer（访客）', value: 'viewer' },
];

/** 用户管理（SPEC §12.2，admin 专属） */
export default function Users() {
  const currentUser = useAuthStore((s) => s.user);
  const [list, setList] = useState<TeapotUser[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<TeapotUser | null>(null);
  const [createForm] = Form.useForm();
  const [editForm] = Form.useForm();
  const isPhone = useIsPhone();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const resp = await userPage({ page, size: 10 });
      setList(resp.list || []);
      setTotal(resp.total || 0);
    } catch {
      // 已统一提示
    } finally {
      setLoading(false);
    }
  }, [page]);

  useEffect(() => {
    load();
  }, [load]);

  const onCreate = async () => {
    const values = await createForm.validateFields();
    await userCreate({
      userId: values.userId,
      username: values.username,
      password: values.password,
      realName: values.realName,
      roles: (values.roles as string[]).join(','),
    });
    message.success('创建成功');
    setCreateOpen(false);
    createForm.resetFields();
    load();
  };

  const onEdit = async () => {
    if (!editTarget) return;
    const values = await editForm.validateFields();
    if (values.password && values.password.length < 8) {
      message.error('新密码至少 8 位');
      return;
    }
    await userUpdate(editTarget.userId, {
      realName: values.realName,
      roles: (values.roles as string[]).join(','),
      newPassword: values.password || undefined,
    });
    message.success('保存成功');
    setEditTarget(null);
    load();
  };

  return (
    <div style={{ padding: isPhone ? 12 : 24 }}>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<SparkPlusLine />} onClick={() => setCreateOpen(true)}>
          新建用户
        </Button>
      </Space>
      <div className="mobile-table-scroll">
      <Table<TeapotUser>
        rowKey="userId"
        loading={loading}
        dataSource={list}
        scroll={isPhone ? { x: 720 } : undefined}
        pagination={{
          current: page,
          pageSize: 10,
          total,
          onChange: setPage,
          showSizeChanger: false,
        }}
        columns={[
          { title: '工号', dataIndex: 'userId', width: 110 },
          { title: '用户名', dataIndex: 'username', width: 130 },
          { title: '姓名', dataIndex: 'realName', width: 120 },
          {
            title: '角色',
            dataIndex: 'roles',
            width: 200,
            render: (roles: string) => (
              <Space size={4} wrap>
                {(roles || '').split(',').map((r) => (
                  <Tag key={r} color={r === 'admin' ? 'red' : r === 'developer' ? 'blue' : 'default'}>
                    {r}
                  </Tag>
                ))}
              </Space>
            ),
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 80,
            render: (status: number) => (
              <Tag color={status === 1 ? 'green' : 'default'}>{status === 1 ? '正常' : '停用'}</Tag>
            ),
          },
          {
            title: '操作',
            width: 160,
            render: (_: unknown, record: TeapotUser) => (
              <Space>
                <Button
                  size="small"
                  onClick={() => {
                    editForm.setFieldsValue({
                      realName: record.realName,
                      roles: (record.roles || '').split(',').filter(Boolean),
                      password: '',
                    });
                    setEditTarget(record);
                  }}
                >
                  编辑
                </Button>
                <Popconfirm
                  title={`确认停用用户「${record.username}」？`}
                  disabled={record.userId === currentUser?.userId}
                  onConfirm={async () => {
                    await userDisable(record.userId);
                    message.success('已停用');
                    load();
                  }}
                >
                  <Button size="small" danger disabled={record.userId === currentUser?.userId}>
                    停用
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />
      </div>

      <ResponsiveModal
        title="新建用户"
        open={createOpen}
        onOk={onCreate}
        onCancel={() => setCreateOpen(false)}
        okText="创建"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={createForm} layout="vertical" preserve={false}>
          <Form.Item name="userId" label="工号" rules={[{ required: true, message: '请输入工号' }]}>
            <Input placeholder="如 T1001" />
          </Form.Item>
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="password"
            label="初始密码"
            rules={[{ required: true, message: '请输入初始密码' }, { min: 6, message: '至少 6 位' }]}
          >
            <Input.Password />
          </Form.Item>
          <Form.Item name="realName" label="姓名">
            <Input />
          </Form.Item>
          <Form.Item name="roles" label="角色" rules={[{ required: true, message: '请选择角色' }]}>
            <Select mode="multiple" options={ROLE_OPTIONS} />
          </Form.Item>
        </Form>
      </ResponsiveModal>

      <ResponsiveModal
        title={`编辑用户 ${editTarget?.username || ''}`}
        open={!!editTarget}
        onOk={onEdit}
        onCancel={() => setEditTarget(null)}
        okText="保存"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={editForm} layout="vertical" preserve={false}>
          <Form.Item name="realName" label="姓名">
            <Input />
          </Form.Item>
          <Form.Item name="roles" label="角色" rules={[{ required: true, message: '请选择角色' }]}>
            <Select mode="multiple" options={ROLE_OPTIONS} />
          </Form.Item>
          <Form.Item name="password" label="重置密码" extra="留空表示不修改">
            <Input.Password placeholder="留空表示不修改" />
          </Form.Item>
        </Form>
      </ResponsiveModal>
    </div>
  );
}
