import { useCallback, useEffect, useState } from 'react';
import { Col, Row, Space, Spin } from 'antd';
import {
  Button,
  Card,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Pagination,
  Popconfirm,
  Select,
  Switch,
  Tag,
} from '@agentscope-ai/design';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { agentCreate, agentDelete, agentList, modelPresets } from '../api/agent';
import type { Agent } from '../types';

/** Agent 管理列表（SPEC §12.2） */
export default function Agents() {
  const navigate = useNavigate();
  const [list, setList] = useState<Agent[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [includeDisabled, setIncludeDisabled] = useState(false);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [models, setModels] = useState<string[]>([]);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const resp = await agentList({ page, size: 12, keyword: keyword || undefined, includeDisabled });
      setList(resp.list || []);
      setTotal(resp.total || 0);
    } catch {
      // 已统一提示
    } finally {
      setLoading(false);
    }
  }, [page, keyword, includeDisabled]);

  useEffect(() => {
    load();
  }, [load]);

  const onCreate = async () => {
    const values = await form.validateFields();
    await agentCreate({
      agentKey: values.agentKey,
      name: values.name,
      description: values.description,
      sysPrompt: values.sysPrompt || '你是一个乐于助人的 AI 助手。',
      modelId: values.modelId,
    });
    message.success('创建成功');
    setCreateOpen(false);
    form.resetFields();
    load();
  };

  return (
    <div
      style={{
        margin: 16,
        padding: 24,
        minHeight: 'calc(100vh - 96px)',
        borderRadius: 16,
        background:
          'linear-gradient(135deg, rgba(97, 92, 237, 0.08) 0%, rgba(255, 255, 255, 0) 45%), ' +
          'radial-gradient(ellipse at top right, rgba(97, 92, 237, 0.10), rgba(255, 255, 255, 0) 55%)',
      }}
    >
      <Space style={{ marginBottom: 16 }} wrap>
        <Input
          placeholder="按名称/标识搜索"
          prefix={<SearchOutlined />}
          allowClear
          style={{ width: 240 }}
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value);
            setPage(1);
          }}
        />
        <span>
          显示已停用：
          <Switch
            checked={includeDisabled}
            onChange={(v) => {
              setIncludeDisabled(v);
              setPage(1);
            }}
          />
        </span>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            modelPresets().then(setModels).catch(() => undefined);
            setCreateOpen(true);
          }}
        >
          新建 Agent
        </Button>
      </Space>

      <Spin spinning={loading}>
        {list.length === 0 && !loading ? (
          <Empty description="暂无 Agent" />
        ) : (
          <Row gutter={[16, 16]}>
            {list.map((agent) => (
              <Col key={agent.agentKey} xs={24} sm={12} lg={8} xl={6}>
                <Card
                  hoverable
                  onClick={() => navigate(`/agents/${agent.agentKey}`)}
                  styles={{ body: { padding: 16 } }}
                  style={{
                    background: 'rgba(255, 255, 255, 0.55)',
                    backdropFilter: 'blur(14px)',
                    WebkitBackdropFilter: 'blur(14px)',
                    border: '1px solid rgba(255, 255, 255, 0.7)',
                    boxShadow: '0 4px 16px rgba(97, 92, 237, 0.08)',
                  }}
                >
                  <Space direction="vertical" size={4} style={{ width: '100%' }}>
                    <Space>
                      <span style={{ fontWeight: 600, fontSize: 15 }}>{agent.name}</span>
                      <Tag color={agent.status === 1 ? 'green' : 'default'}>
                        {agent.status === 1 ? '启用' : '停用'}
                      </Tag>
                    </Space>
                    <div style={{ color: 'rgba(38, 36, 76, 0.45)', fontSize: 12 }}>
                      {agent.agentKey} · {agent.modelId}
                    </div>
                    <div
                      style={{
                        color: 'rgba(38, 36, 76, 0.65)',
                        fontSize: 13,
                        height: 40,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                      }}
                    >
                      {agent.description || '（无描述）'}
                    </div>
                    <Space size={4} onClick={(e) => e.stopPropagation()}>
                      <Button size="small" onClick={() => navigate(`/chat?agent=${agent.agentKey}`)}>
                        对话
                      </Button>
                      <Button size="small" onClick={() => navigate(`/agents/${agent.agentKey}`)}>
                        编辑
                      </Button>
                      <Popconfirm
                        title="确认停用并删除该 Agent？"
                        onConfirm={async () => {
                          await agentDelete(agent.agentKey);
                          message.success('已删除');
                          load();
                        }}
                      >
                        <Button size="small" danger>
                          删除
                        </Button>
                      </Popconfirm>
                    </Space>
                  </Space>
                </Card>
              </Col>
            ))}
          </Row>
        )}
      </Spin>

      <div style={{ marginTop: 16, textAlign: 'right' }}>
        <Pagination current={page} pageSize={12} total={total} onChange={setPage} showSizeChanger={false} />
      </div>

      <Modal
        title="新建 Agent"
        open={createOpen}
        onOk={onCreate}
        onCancel={() => setCreateOpen(false)}
        okText="创建"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            name="agentKey"
            label="Agent 标识"
            rules={[
              { required: true, message: '请输入标识' },
              { pattern: /^[a-z][a-z0-9-]{1,63}$/, message: '小写字母开头，仅含小写字母/数字/短横线，2-64 位' },
            ]}
            extra="创建后不可修改，将作为 AG-UI 路由 key"
          >
            <Input placeholder="如 general-assistant" />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 通用助手" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="一句话描述该 Agent 的用途" />
          </Form.Item>
          <Form.Item name="modelId" label="模型" rules={[{ required: true, message: '请选择模型' }]}>
            <Select options={models.map((m) => ({ label: m, value: m }))} placeholder="选择模型" />
          </Form.Item>
          <Form.Item name="sysPrompt" label="系统提示词">
            <Input.TextArea rows={4} placeholder="可留空使用默认提示词" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
