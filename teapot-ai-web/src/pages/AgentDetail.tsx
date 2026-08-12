import { useCallback, useEffect, useState } from 'react';
import { Col, Divider, Row, Space, Spin, Typography } from 'antd';
import {
  Breadcrumb,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  message,
  Select,
  Tag,
} from '@agentscope-ai/design';
import { LinkOutlined, PlusOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { agentBindSkill, agentDetail, agentUnbindSkill, agentUpdate, modelPresets } from '../api/agent';
import { skillList } from '../api/skill';
import type { SkillListItem } from '../types';

/** Agent 详情编辑（SPEC §12.2：sysPrompt/模型/压缩参数/Skill 绑定） */
export default function AgentDetailPage() {
  const { agentKey = '' } = useParams();
  const navigate = useNavigate();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [models, setModels] = useState<string[]>([]);
  const [boundSkills, setBoundSkills] = useState<string[]>([]);
  const [allSkills, setAllSkills] = useState<SkillListItem[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [detail, presets, skills] = await Promise.all([
        agentDetail(agentKey),
        modelPresets(),
        skillList(),
      ]);
      form.setFieldsValue({
        name: detail.agent.name,
        description: detail.agent.description,
        modelId: detail.agent.modelId,
        compactionTrigger: detail.agent.compactionTrigger,
        compactionKeep: detail.agent.compactionKeep,
        sysPrompt: detail.agent.sysPrompt,
      });
      setBoundSkills(detail.skillNames || []);
      setModels(presets || []);
      setAllSkills(skills || []);
    } catch {
      // 已统一提示
    } finally {
      setLoading(false);
    }
  }, [agentKey, form]);

  useEffect(() => {
    load();
  }, [load]);

  const onSave = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await agentUpdate(agentKey, values);
      message.success('保存成功');
    } finally {
      setSaving(false);
    }
  };

  const unboundSkills = allSkills.filter((s) => !boundSkills.includes(s.name));

  return (
    <div style={{ padding: 24 }}>
      <Breadcrumb
        style={{ marginBottom: 16 }}
        items={[
          { title: <a onClick={() => navigate('/agents')}>Agent 管理</a> },
          { title: agentKey },
        ]}
      />
      <Spin spinning={loading}>
        <Row gutter={16}>
          <Col xs={24} lg={14}>
            <Card title="基础配置">
              <Form form={form} layout="vertical">
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item name="name" label="名称" rules={[{ required: true }]}>
                      <Input />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="modelId" label="模型" rules={[{ required: true }]}>
                      <Select options={models.map((m) => ({ label: m, value: m }))} />
                    </Form.Item>
                  </Col>
                </Row>
                <Form.Item name="description" label="描述">
                  <Input.TextArea rows={2} />
                </Form.Item>
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item
                      name="compactionTrigger"
                      label="压缩触发轮数"
                      tooltip="会话历史超过该轮数时触发记忆压缩"
                    >
                      <InputNumber min={1} max={200} style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item
                      name="compactionKeep"
                      label="压缩保留轮数"
                      tooltip="压缩后保留的最近轮数"
                    >
                      <InputNumber min={0} max={100} style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                </Row>
                <Form.Item name="sysPrompt" label="系统提示词">
                  <Input.TextArea rows={12} style={{ fontFamily: 'monospace' }} />
                </Form.Item>
                <Button type="primary" onClick={onSave} loading={saving}>
                  保存修改
                </Button>
              </Form>
            </Card>
          </Col>
          <Col xs={24} lg={10}>
            <Card
              title="绑定 Skill"
              extra={
                <Button type="link" size="small" onClick={() => navigate('/skills')}>
                  去 Skill 工坊
                </Button>
              }
            >
              <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                绑定后 Agent 运行时自动加载 Skill 内容（修改 Skill 后重新会话生效）。
              </Typography.Paragraph>
              <Divider orientation="left" plain style={{ margin: '8px 0' }}>
                已绑定
              </Divider>
              {boundSkills.length === 0 ? (
                <Typography.Text type="secondary">暂无绑定</Typography.Text>
              ) : (
                <Space wrap>
                  {boundSkills.map((name) => (
                    <Tag
                      key={name}
                      closable
                      color="blue"
                      onClose={async (e) => {
                        e.preventDefault();
                        await agentUnbindSkill(agentKey, name);
                        message.success(`已解绑 ${name}`);
                        setBoundSkills((prev) => prev.filter((n) => n !== name));
                      }}
                    >
                      {name}
                    </Tag>
                  ))}
                </Space>
              )}
              <Divider orientation="left" plain style={{ margin: '12px 0 8px' }}>
                可绑定
              </Divider>
              {unboundSkills.length === 0 ? (
                <Typography.Text type="secondary">没有更多可用 Skill</Typography.Text>
              ) : (
                <Space wrap>
                  {unboundSkills.map((s) => (
                    <Tag
                      key={s.name}
                      style={{ cursor: 'pointer' }}
                      onClick={async () => {
                        await agentBindSkill(agentKey, s.name);
                        message.success(`已绑定 ${s.name}`);
                        setBoundSkills((prev) => [...prev, s.name]);
                      }}
                    >
                      <PlusOutlined /> <LinkOutlined /> {s.name}
                    </Tag>
                  ))}
                </Space>
              )}
            </Card>
          </Col>
        </Row>
      </Spin>
    </div>
  );
}
