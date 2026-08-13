import { useCallback, useEffect, useState } from 'react';
import { Col, Row, Space, Spin, Typography } from 'antd';
import { Button, Card, Empty, message, Popconfirm, Tag } from '@agentscope-ai/design';
import { PlusOutlined, SyncOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { skillDelete, skillGitStatus, skillGitSync, skillList } from '../api/skill';
import { useAuthStore } from '../store/auth';
import type { SkillGitStatus, SkillListItem } from '../types';

/** Skill 工坊列表（SPEC §12.2 + §15.13：双来源 source 标签 / git 只读 / 同步状态条） */
export default function Skills() {
  const navigate = useNavigate();
  const [list, setList] = useState<SkillListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [gitStatus, setGitStatus] = useState<SkillGitStatus | null>(null);
  const [syncing, setSyncing] = useState(false);
  const canSync = useAuthStore((s) => s.hasRole('admin', 'developer'));

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [skills, git] = await Promise.all([
        skillList(),
        skillGitStatus().catch(() => null),
      ]);
      setList(skills);
      setGitStatus(git);
    } catch {
      // 已统一提示
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const onSync = async () => {
    setSyncing(true);
    try {
      const status = await skillGitSync();
      setGitStatus(status);
      message.success(`同步完成，Git 来源 ${status.skillCount} 个 skill`);
      load();
    } finally {
      setSyncing(false);
    }
  };

  return (
    <div style={{ padding: window.innerWidth < 768 ? 12 : 24 }}>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/skills/new')}>
          新建 Skill
        </Button>
      </Space>
      {gitStatus?.enabled && (
        <div
          className="glass-card"
          style={{
            padding: '10px 16px',
            marginBottom: 16,
            display: 'flex',
            alignItems: 'center',
            gap: 12,
            flexWrap: 'wrap',
            fontSize: 13,
            color: 'rgba(26,26,29,0.65)',
          }}
        >
          <Tag color="green">Git 仓库</Tag>
          <span>分支 {gitStatus.branch || '—'}</span>
          <span>·</span>
          <span>{gitStatus.skillCount} 个 skill</span>
          <span>·</span>
          <span>{gitStatus.remoteMasked || '—'}</span>
          {gitStatus.lastSyncAt && (
            <>
              <span>·</span>
              <span>上次手动同步 {gitStatus.lastSyncAt.replace('T', ' ').slice(0, 19)}</span>
            </>
          )}
          <div style={{ flex: 1 }} />
          {canSync && (
            <Button size="small" icon={<SyncOutlined spin={syncing} />} onClick={onSync} loading={syncing}>
              立即同步
            </Button>
          )}
        </div>
      )}
      <Spin spinning={loading}>
        {list.length === 0 && !loading ? (
          <Empty description="暂无 Skill，点击右上角新建" />
        ) : (
          <Row gutter={[16, 16]}>
            {list.map((skill) => {
              const fromGit = skill.source === 'git';
              return (
                <Col key={skill.name} xs={24} sm={12} lg={8} xl={6}>
                  <Card hoverable onClick={() => navigate(`/skills/${skill.name}`)}>
                    <Space direction="vertical" size={4} style={{ width: '100%' }}>
                      <Space>
                        <ThunderboltOutlined style={{ color: fromGit ? '#52c41a' : '#faad14' }} />
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
                        <Tag color={fromGit ? 'green' : undefined}>{skill.source || 'platform'}</Tag>
                        {fromGit ? (
                          /* Git 来源只读（SPEC §15.13）：修改走 PR，隐藏编辑/删除 */
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            Git 管控 · 只读
                          </Typography.Text>
                        ) : (
                          <>
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
                          </>
                        )}
                      </Space>
                    </Space>
                  </Card>
                </Col>
              );
            })}
          </Row>
        )}
      </Spin>
    </div>
  );
}
