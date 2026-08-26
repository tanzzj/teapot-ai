import { useCallback, useEffect, useState } from 'react';
import { Col, Row, Spin } from 'antd';
import {
  Button,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Pagination,
  Popconfirm,
  Select,
  Switch,
} from '@agentscope-ai/design';
import {
  SparkDeleteLine,
  SparkEditLine,
  SparkMessageLine,
  SparkPlusLine,
  SparkSearchLine,
} from '@agentscope-ai/icons';
import { useNavigate } from 'react-router-dom';
import { agentCreate, agentDelete, agentList, modelPresets } from '../api/agent';
import type { Agent } from '../types';

/**
 * Agent 管理列表（Barley 设计语言复刻：大标题 + 三列毛玻璃卡片网格）。
 * SPEC §12.2
 */
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
    <div style={{ padding: '24px 28px', minHeight: '100%' }}>
      {/* 标题行（Barley：大标题 + 副标题 + 右侧黑色主按钮） */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 20, flexWrap: 'wrap' }}>
        <h2 style={{ margin: 0, fontSize: 26, fontWeight: 700, color: 'rgba(26, 26, 29, 0.92)' }}>
          Agents
        </h2>
        <span style={{ color: 'rgba(26, 26, 29, 0.45)', fontSize: 13 }}>
          管理与配置你的 AI Agent
        </span>
        <div style={{ flex: 1 }} />
        <Input
          placeholder="按名称/标识搜索"
          prefix={<SparkSearchLine />}
          allowClear
          style={{ width: 200 }}
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value);
            setPage(1);
          }}
        />
        <span style={{ color: 'rgba(26, 26, 29, 0.55)', fontSize: 13, whiteSpace: 'nowrap' }}>
          显示已停用
          <Switch
            size="small"
            style={{ marginLeft: 6 }}
            checked={includeDisabled}
            onChange={(v) => {
              setIncludeDisabled(v);
              setPage(1);
            }}
          />
        </span>
        <Button
          type="primary"
          icon={<SparkPlusLine />}
          onClick={() => {
            modelPresets().then(setModels).catch(() => undefined);
            setCreateOpen(true);
          }}
        >
          New Agent
        </Button>
      </div>

      <Spin spinning={loading}>
        {list.length === 0 && !loading ? (
          <Empty description="暂无 Agent" style={{ marginTop: 60 }} />
        ) : (
          <Row gutter={[16, 16]}>
            {list.map((agent) => (
              <Col key={agent.agentKey} xs={24} md={12} xl={8} style={{ display: 'flex' }}>
                <div
                  className="glass-card"
                  style={{ padding: 20, cursor: 'pointer', display: 'flex', flexDirection: 'column', gap: 12, flex: 1, minWidth: 0 }}
                  onClick={() => navigate(`/agents/${agent.agentKey}`)}
                >
                  {/* 卡片头：头像（SPEC §23：有头像图则展示，否则首字母占位） + 名称/key + 类型标签 */}
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    {agent.avatar ? (
                      <img
                        src={agent.avatar}
                        alt={agent.name}
                        style={{ width: 44, height: 44, borderRadius: 999, objectFit: 'cover', flexShrink: 0 }}
                      />
                    ) : (
                      <span
                        style={{
                          width: 44,
                          height: 44,
                          borderRadius: 999,
                          background: 'linear-gradient(135deg, #2b2b31, #1a1a1d)',
                          color: '#fff',
                          display: 'inline-flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          fontSize: 18,
                          fontWeight: 700,
                          flexShrink: 0,
                        }}
                      >
                        {(agent.name || agent.agentKey).charAt(0).toUpperCase()}
                      </span>
                    )}
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <div style={{ fontWeight: 600, fontSize: 15, color: 'rgba(26, 26, 29, 0.92)' }}>
                        {agent.name}
                      </div>
                      <div
                        style={{
                          fontFamily: 'Menlo, Consolas, monospace',
                          fontSize: 11,
                          color: 'rgba(26, 26, 29, 0.55)',
                          background: 'rgba(0, 0, 43, 0.05)',
                          borderRadius: 6,
                          padding: '1px 8px',
                          display: 'inline-block',
                          marginTop: 2,
                        }}
                      >
                        {agent.agentKey}
                      </div>
                    </div>
                    {agent.status !== 1 && (
                      <span style={{ fontSize: 12, fontWeight: 500, color: 'rgba(26, 26, 29, 0.35)', whiteSpace: 'nowrap' }}>
                        已停用
                      </span>
                    )}
                  </div>

                  {/* 描述：单行省略 */}
                  <div
                    style={{
                      color: 'rgba(26, 26, 29, 0.6)',
                      fontSize: 13,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {agent.description || '（无描述）'}
                  </div>

                  {/* 底部操作（Barley：Start Chat / New Automation 风格） */}
                  <div
                    style={{
                      borderTop: '1px solid rgba(0, 0, 0, 0.06)',
                      paddingTop: 12,
                      display: 'flex',
                      gap: 18,
                      alignItems: 'center',
                      marginTop: 'auto',
                    }}
                    onClick={(e) => e.stopPropagation()}
                  >
                    <span
                      style={{ display: 'inline-flex', gap: 6, alignItems: 'center', color: 'rgba(26, 26, 29, 0.88)', fontWeight: 500, fontSize: 13, cursor: 'pointer' }}
                      onClick={() => navigate(`/chat?agent=${agent.agentKey}`)}
                    >
                      <SparkMessageLine /> 对话
                    </span>
                    <span
                      style={{ display: 'inline-flex', gap: 6, alignItems: 'center', color: 'rgba(26, 26, 29, 0.55)', fontSize: 13, cursor: 'pointer' }}
                      onClick={() => navigate(`/agents/${agent.agentKey}`)}
                    >
                      <SparkEditLine /> 编辑
                    </span>
                    <Popconfirm
                      title="确认停用并删除该 Agent？"
                      onConfirm={async () => {
                        await agentDelete(agent.agentKey);
                        message.success('已删除');
                        load();
                      }}
                    >
                      <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center', color: 'rgba(26, 26, 29, 0.35)', fontSize: 13, cursor: 'pointer' }}>
                        <SparkDeleteLine /> 删除
                      </span>
                    </Popconfirm>
                  </div>
                </div>
              </Col>
            ))}
          </Row>
        )}
      </Spin>

      <div style={{ marginTop: 20, textAlign: 'right' }}>
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
