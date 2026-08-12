import { useCallback, useEffect, useState } from 'react';
import { Col, Row, Space, Spin } from 'antd';
import { Button, Card, Empty, message, Popconfirm, Tag } from '@agentscope-ai/design';
import { PlusOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { skillDelete, skillList } from '../api/skill';
import type { SkillListItem } from '../types';

/** Skill 工坊列表（SPEC §12.2） */
export default function Skills() {
  const navigate = useNavigate();
  const [list, setList] = useState<SkillListItem[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setList(await skillList());
    } catch {
      // 已统一提示
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div style={{ padding: window.innerWidth < 768 ? 12 : 24 }}>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/skills/new')}>
          新建 Skill
        </Button>
      </Space>
      <Spin spinning={loading}>
        {list.length === 0 && !loading ? (
          <Empty description="暂无 Skill，点击右上角新建" />
        ) : (
          <Row gutter={[16, 16]}>
            {list.map((skill) => (
              <Col key={skill.name} xs={24} sm={12} lg={8} xl={6}>
                <Card hoverable onClick={() => navigate(`/skills/${skill.name}`)}>
                  <Space direction="vertical" size={4} style={{ width: '100%' }}>
                    <Space>
                      <ThunderboltOutlined style={{ color: '#faad14' }} />
                      <span style={{ fontWeight: 600 }}>{skill.name}</span>
                    </Space>
                    <div
                      style={{
                        color: '#666',
                        fontSize: 13,
                        height: 40,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                      }}
                    >
                      {skill.description || '（无描述）'}
                    </div>
                    <Space onClick={(e) => e.stopPropagation()}>
                      <Tag>{skill.source || 'custom'}</Tag>
                      <Button size="small" onClick={() => navigate(`/skills/${skill.name}`)}>
                        编辑
                      </Button>
                      <Popconfirm
                        title={`确认删除 Skill「${skill.name}」？将级联解绑所有 Agent。`}
                        onConfirm={async () => {
                          await skillDelete(skill.name);
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
    </div>
  );
}
