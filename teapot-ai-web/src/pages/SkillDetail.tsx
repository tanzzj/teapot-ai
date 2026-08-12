import { useCallback, useEffect, useState } from 'react';
import { Col, Divider, Row, Space, Spin, Typography } from 'antd';
import {
  Breadcrumb,
  Button,
  Card,
  Form,
  Input,
  message,
} from '@agentscope-ai/design';
import { DeleteOutlined, EyeOutlined, PlusOutlined, SaveOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { skillDetail, skillPreview, skillSave } from '../api/skill';
import type { SkillResourceItem } from '../types';

/** Skill 编辑器（SPEC §12.2：name/description/instructions + 资源 + 实时预览） */
export default function SkillDetailPage() {
  const { name } = useParams();
  const isNew = !name || name === 'new';
  const navigate = useNavigate();
  const [form] = Form.useForm();
  const [resources, setResources] = useState<SkillResourceItem[]>([]);
  const [loading, setLoading] = useState(!isNew);
  const [saving, setSaving] = useState(false);
  const [preview, setPreview] = useState('');

  useEffect(() => {
    if (isNew) return;
    setLoading(true);
    skillDetail(name!)
      .then((detail) => {
        form.setFieldsValue({
          name: detail.name,
          description: detail.description,
          instructions: detail.instructions,
        });
        setResources(detail.resources || []);
      })
      .finally(() => setLoading(false));
  }, [name, isNew, form]);

  const buildPayload = useCallback(async () => {
    const values = await form.validateFields();
    return {
      name: values.name.trim(),
      description: values.description.trim(),
      instructions: values.instructions || '',
      resources: resources.filter((r) => r.path.trim()),
    };
  }, [form, resources]);

  const onPreview = async () => {
    const payload = await buildPayload();
    setPreview(await skillPreview(payload));
  };

  const onSave = async () => {
    const payload = await buildPayload();
    setSaving(true);
    try {
      await skillSave(payload);
      message.success('保存成功');
      navigate('/skills');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div style={{ padding: 24 }}>
      <Breadcrumb
        style={{ marginBottom: 16 }}
        items={[
          { title: <a onClick={() => navigate('/skills')}>Skill 工坊</a> },
          { title: isNew ? '新建 Skill' : name },
        ]}
      />
      <Spin spinning={loading}>
        <Row gutter={16}>
          <Col xs={24} lg={12}>
            <Card title={isNew ? '新建 Skill' : `编辑 ${name}`}>
              <Form form={form} layout="vertical">
                <Form.Item
                  name="name"
                  label="Skill 名称"
                  rules={[
                    { required: true, message: '请输入名称' },
                    { pattern: /^[a-z][a-z0-9-]{1,63}$/, message: '小写字母开头，仅含小写字母/数字/短横线' },
                  ]}
                >
                  <Input disabled={!isNew} placeholder="如 code-review" />
                </Form.Item>
                <Form.Item
                  name="description"
                  label="描述"
                  rules={[{ required: true, message: '请输入描述' }]}
                  extra="Agent 据此判断何时调用该 Skill，请写清适用场景"
                >
                  <Input.TextArea rows={2} />
                </Form.Item>
                <Form.Item
                  name="instructions"
                  label="指令正文（instructions）"
                  extra="SKILL.md 正文，描述具体执行步骤；保存时与 name/description 合并为 frontmatter"
                >
                  <Input.TextArea rows={12} style={{ fontFamily: 'monospace' }} />
                </Form.Item>
              </Form>

              <Divider orientation="left" plain>
                附加资源（可选，单文件 ≤ 1MB）
              </Divider>
              {resources.map((r, idx) => (
                <Card
                  key={idx}
                  size="small"
                  style={{ marginBottom: 8 }}
                  title={
                    <Input
                      size="small"
                      placeholder="资源路径，如 templates/report.md"
                      value={r.path}
                      onChange={(e) => {
                        setResources((prev) => prev.map((p, i) => (i === idx ? { ...p, path: e.target.value } : p)));
                      }}
                    />
                  }
                  extra={
                    <Button
                      size="small"
                      danger
                      icon={<DeleteOutlined />}
                      onClick={() => setResources((prev) => prev.filter((_, i) => i !== idx))}
                    />
                  }
                >
                  <Input.TextArea
                    rows={4}
                    style={{ fontFamily: 'monospace' }}
                    value={r.content}
                    onChange={(e) => {
                      setResources((prev) =>
                        prev.map((p, i) => (i === idx ? { ...p, content: e.target.value } : p)),
                      );
                    }}
                  />
                </Card>
              ))}
              <Button
                icon={<PlusOutlined />}
                onClick={() => setResources((prev) => [...prev, { path: '', content: '' }])}
              >
                添加资源
              </Button>

              <div style={{ marginTop: 16 }}>
                <Space>
                  <Button type="primary" icon={<SaveOutlined />} onClick={onSave} loading={saving}>
                    保存
                  </Button>
                  <Button icon={<EyeOutlined />} onClick={onPreview}>
                    预览 SKILL.md
                  </Button>
                </Space>
              </div>
            </Card>
          </Col>
          <Col xs={24} lg={12}>
            <Card title="SKILL.md 预览">
              {preview ? (
                <pre
                  style={{
                    whiteSpace: 'pre-wrap',
                    fontSize: 12,
                    margin: 0,
                    maxHeight: '70vh',
                    overflow: 'auto',
                  }}
                >
                  {preview}
                </pre>
              ) : (
                <Typography.Text type="secondary">点击「预览 SKILL.md」查看组装结果</Typography.Text>
              )}
            </Card>
          </Col>
        </Row>
      </Spin>
    </div>
  );
}
