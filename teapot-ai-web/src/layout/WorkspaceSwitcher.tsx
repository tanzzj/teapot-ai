import { useEffect, useState } from 'react';
import { Dropdown, theme } from 'antd';
import { Button, Form, Input, message, Select } from '@agentscope-ai/design';
import { ResponsiveModal } from '../components/ResponsiveModal';
import UserPicker from '../components/UserPicker';
import { useWorkspaceStore } from '../store/workspace';
import { workspaceCreate } from '../api/workspace';
import { agentMoveWorkspace } from '../api/agent';

const PERSONAL = 'personal';

/**
 * 顶栏业务空间簇（SPEC §28.6，图二红框）：空间下拉 + 「＋」新建空间 + 「…」移动空间。
 * 默认「个人空间」= activeId null（合成项，首个 Agent 创建时才落库）。
 */
export default function WorkspaceSwitcher() {
  const { token } = theme.useToken();
  const { workspaces, activeId, loaded, load, setActive } = useWorkspaceStore();
  const [createOpen, setCreateOpen] = useState(false);
  const [moveOpen, setMoveOpen] = useState(false);
  const [moveTarget, setMoveTarget] = useState<string>(PERSONAL);
  const [form] = Form.useForm();

  useEffect(() => {
    if (!loaded) {
      load();
    }
  }, [loaded, load]);

  const activeValue = activeId === null ? PERSONAL : String(activeId);
  const options = workspaces.map((w) => ({
    value: w.id === null || w.id === undefined ? PERSONAL : String(w.id),
    label: w.workspaceName,
  }));

  const onCreate = async () => {
    const values = await form.validateFields();
    const created = await workspaceCreate({
      workspaceName: values.workspaceName,
      workspaceCode: values.workspaceCode,
      description: values.description,
      memberUserIds: values.memberUserIds || [],
    });
    message.success('空间已创建');
    setCreateOpen(false);
    form.resetFields();
    await load();
    if (created?.id) {
      setActive(created.id);
    }
  };

  const onMove = async () => {
    const agentKey = localStorage.getItem('teapot:lastAgent') || '';
    if (!agentKey) {
      message.warning('请先在对话页选择要移动的 Agent');
      return;
    }
    const target = moveTarget === PERSONAL ? null : Number(moveTarget);
    await agentMoveWorkspace(agentKey, target);
    message.success(`已将 ${agentKey} 移动到目标空间`);
    setMoveOpen(false);
  };

  const menuItems = [
    { key: 'move', label: '移动空间', onClick: () => setMoveOpen(true) },
  ];

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
      <Select
        value={activeValue}
        options={options}
        onChange={(v) => setActive(v === PERSONAL ? null : Number(v))}
        style={{ width: 140 }}
        size="small"
      />
      <Button type="text" size="small" onClick={() => setCreateOpen(true)} title="新建空间">
        ＋
      </Button>
      <Dropdown menu={{ items: menuItems }} trigger={['click']}>
        <Button type="text" size="small" style={{ color: token.colorText }} title="空间操作">
          ⋯
        </Button>
      </Dropdown>

      {/* 新建空间（必填字段 + 拉用户查询组件加成员，SPEC §28.6） */}
      <ResponsiveModal
        title="新建业务空间"
        open={createOpen}
        onOk={onCreate}
        onCancel={() => setCreateOpen(false)}
        okText="创建"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="workspaceName" label="空间名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 研发一组" maxLength={64} />
          </Form.Item>
          <Form.Item
            name="workspaceCode"
            label="空间编码"
            rules={[
              { required: true, message: '请输入编码' },
              { pattern: /^[a-z][a-z0-9-]{2,63}$/, message: '小写字母开头，仅含小写字母/数字/短横线，3-64 位' },
            ]}
            extra="全局唯一，创建后不可修改"
          >
            <Input placeholder="如 dev-team-1" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="可选" maxLength={512} />
          </Form.Item>
          <Form.Item name="memberUserIds" label="成员">
            <UserPicker />
          </Form.Item>
        </Form>
      </ResponsiveModal>

      {/* 移动当前 Agent 到目标空间（SPEC §28.6 需求 5） */}
      <ResponsiveModal
        title="移动当前 Agent 到空间"
        open={moveOpen}
        onOk={onMove}
        onCancel={() => setMoveOpen(false)}
        okText="移动"
        cancelText="取消"
      >
        <div style={{ marginBottom: 8, color: token.colorTextSecondary, fontSize: 13 }}>
          当前 Agent：{localStorage.getItem('teapot:lastAgent') || '（未选择，请到对话页选一个）'}
        </div>
        <Select
          value={moveTarget}
          onChange={(v) => setMoveTarget(String(v))}
          style={{ width: '100%' }}
          options={[
            { value: PERSONAL, label: '个人空间' },
            ...workspaces
              .filter((w) => w.id !== null && w.id !== undefined)
              .map((w) => ({ value: String(w.id), label: w.workspaceName })),
          ]}
        />
      </ResponsiveModal>
    </div>
  );
}
