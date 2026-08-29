import { useCallback, useEffect, useState } from 'react';
import { Col, Dropdown, Input, Row, Space, Spin, Typography, Upload } from 'antd';
import type { UploadFile } from 'antd';
import { Button, Empty, message, Popconfirm, Radio, Tag } from '@agentscope-ai/design';
import {
  SparkDownArrowLine,
  SparkPlusLine,
  SparkRefreshLine,
  SparkMagicWandLine,
  SparkEditLine,
  SparkDeleteLine,
} from '@agentscope-ai/icons';
import { useNavigate } from 'react-router-dom';
import {
  skillDelete,
  skillGitStatus,
  skillGitSync,
  skillImport,
  skillImportFromGit,
  skillList,
  skillOssStatus,
} from '../api/skill';
import { useAuthStore } from '../store/auth';
import { useIsPhone } from '../hooks/useIsPhone';
import { ResponsiveModal } from '../components/ResponsiveModal';
import type { SkillGitStatus, SkillListItem, SkillOssStatus } from '../types';

/** Skill 工坊列表（SPEC §12.2 + §15.13 + §22.3 扩展：三来源 source 标签 / git·oss 只读 / zip 导入） */

export default function Skills() {
  const navigate = useNavigate();
  const [list, setList] = useState<SkillListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [gitStatus, setGitStatus] = useState<SkillGitStatus | null>(null);
  const [ossStatus, setOssStatus] = useState<SkillOssStatus | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [importFileList, setImportFileList] = useState<UploadFile[]>([]);
  const [importTarget, setImportTarget] = useState<'oss' | 'mysql'>('oss');
  const [importing, setImporting] = useState(false);
  const [gitImportOpen, setGitImportOpen] = useState(false);
  const [gitImportUrl, setGitImportUrl] = useState('');
  const [gitImportBranch, setGitImportBranch] = useState('');
  const [gitImporting, setGitImporting] = useState(false);
  const canSync = useAuthStore((s) => s.hasRole('admin', 'developer'));
  const isPhone = useIsPhone();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [skills, git, oss] = await Promise.all([
        skillList(),
        skillGitStatus().catch(() => null),
        skillOssStatus().catch(() => null),
      ]);
      setList(skills);
      setGitStatus(git);
      setOssStatus(oss);
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

  const onImport = async () => {
    const file = importFileList[0]?.originFileObj as File | undefined;
    if (!file) {
      message.warning('请选择 zip 文件');
      return;
    }
    setImporting(true);
    try {
      const result = await skillImport(file, importTarget);
      message.success(`导入完成（${result.target === 'oss' ? 'OSS' : '平台库'}）：${result.imported.join('、')}`);
      setImportOpen(false);
      setImportFileList([]);
      load();
    } finally {
      setImporting(false);
    }
  };

  const onGitImport = async () => {
    if (!gitImportUrl.trim()) {
      message.warning('请输入 Git 仓库地址');
      return;
    }
    setGitImporting(true);
    try {
      // 落点固定平台数据库（agentscope_skills）：导入即内容入库，无需选择存储位置
      const result = await skillImportFromGit(gitImportUrl.trim(), gitImportBranch.trim(), 'mysql');
      message.success(`导入完成：${result.imported.join('、')}`);
      setGitImportOpen(false);
      setGitImportUrl('');
      setGitImportBranch('');
      load();
    } finally {
      setGitImporting(false);
    }
  };

  return (
    <div style={{ padding: isPhone ? 12 : 24 }}>
      <Space style={{ marginBottom: 16 }}>
        {/* 新建/导入收进单个下拉：新建 / zip 导入 / git 导入（任意仓库地址） */}
        <Dropdown
          trigger={['click']}
          menu={{
            items: [
              { key: 'new', label: '新建', icon: <SparkPlusLine size={14} /> },
              ...(canSync && ossStatus?.enabled !== false
                ? [{ key: 'zip', label: 'zip 导入', icon: <SparkMagicWandLine size={14} /> }]
                : []),
              ...(canSync
                ? [{ key: 'git', label: 'git 导入', icon: <SparkRefreshLine size={14} /> }]
                : []),
            ],
            onClick: ({ key }) => {
              if (key === 'new') {
                navigate('/skills/new');
              } else if (key === 'zip') {
                setImportFileList([]);
                setImportTarget('oss');
                setImportOpen(true);
              } else if (key === 'git') {
                setGitImportUrl('');
                setGitImportBranch('');
                setGitImportOpen(true);
              }
            },
          }}
        >
          <Button type="primary" icon={<SparkPlusLine />}>
            新建 Skill <SparkDownArrowLine size={12} />
          </Button>
        </Dropdown>
      </Space>
      <Spin spinning={loading}>
        {list.length === 0 && !loading ? (
          <Empty description="暂无 Skill，点击右上角新建" />
        ) : (
          <Row gutter={[16, 16]}>
            {list.map((skill) => {
              const fromGit = skill.source === 'git';
              const fromOss = skill.source === 'oss';
              // Git 仓库信息收进 tooltip（分支/远端/上次同步），卡面不再占行，保证各行等高
              const gitTooltip =
                fromGit && gitStatus?.enabled
                  ? `分支 ${gitStatus.branch || '—'} · ${gitStatus.remoteMasked || '—'}${
                      gitStatus.lastSyncAt ? ` · 上次同步 ${gitStatus.lastSyncAt.replace('T', ' ').slice(0, 19)}` : ''
                    }`
                  : undefined;
              return (
                <Col key={skill.name} xs={24} sm={12} lg={8} xl={6} style={{ display: 'flex' }}>
                  {/* 卡片样式对齐 Agent 卡片：glass-card + 底部横线分隔，标签/操作沉底 */}
                  <div
                    className="glass-card"
                    style={{
                      padding: 20,
                      cursor: 'pointer',
                      display: 'flex',
                      flexDirection: 'column',
                      gap: 12,
                      flex: 1,
                      minWidth: 0,
                    }}
                    onClick={() => navigate(`/skills/${skill.name}`)}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                      <SparkMagicWandLine
                        style={{ color: fromGit ? '#52c41a' : fromOss ? '#1677ff' : '#faad14', flexShrink: 0 }}
                      />
                      <span
                        style={{
                          fontWeight: 600,
                          fontSize: 15,
                          color: 'rgba(26, 26, 29, 0.92)',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}
                      >
                        {skill.name}
                      </span>
                    </div>
                    {/* 描述：固定两行高度，卡片等高不受长短文案影响 */}
                    <div
                      title={skill.description || undefined}
                      style={{
                        color: 'rgba(26, 26, 29, 0.6)',
                        fontSize: 13,
                        height: 40,
                        overflow: 'hidden',
                        display: '-webkit-box',
                        WebkitLineClamp: 2,
                        WebkitBoxOrient: 'vertical',
                      }}
                    >
                      {skill.description || '（无描述）'}
                    </div>

                    {/* 底部标签 + 操作（横线分隔，沉底对齐，对齐 Agent 卡片） */}
                    <div
                      style={{
                        borderTop: '1px solid rgba(0, 0, 0, 0.06)',
                        paddingTop: 12,
                        marginTop: 'auto',
                        display: 'flex',
                        alignItems: 'center',
                        gap: 8,
                        flexWrap: 'wrap',
                      }}
                      onClick={(e) => e.stopPropagation()}
                    >
                      <Tag
                        color={fromGit ? 'green' : fromOss ? 'blue' : undefined}
                        style={{ margin: 0 }}
                      >
                        {skill.source || 'platform'}
                      </Tag>
                      {fromGit ? (
                        <>
                          {/* Git 来源只读（SPEC §15.13）：修改走 PR；同步按钮下沉到卡片级（§22.3，仍为仓库级同步） */}
                          <Typography.Text
                            type="secondary"
                            style={{ fontSize: 12, flex: 1, minWidth: 0 }}
                            ellipsis={gitTooltip ? { tooltip: gitTooltip } : undefined}
                          >
                            Git 管控 · 只读
                          </Typography.Text>
                          {canSync && (
                            <Button
                              size="small"
                              icon={<SparkRefreshLine spin={syncing} />}
                              onClick={onSync}
                              loading={syncing}
                            >
                              立即同步
                            </Button>
                          )}
                        </>
                      ) : fromOss ? (
                        <>
                          {/* OSS 来源：zip 导入托管，表单只读，重导入覆盖，可删除 */}
                          <Typography.Text
                            type="secondary"
                            style={{ fontSize: 12, flex: 1, minWidth: 0 }}
                            ellipsis
                          >
                            OSS 挂载 · 重导入覆盖
                          </Typography.Text>
                          <Popconfirm
                            title={`确认删除 OSS Skill「${skill.name}」？将清空对象并级联解绑所有 Agent。`}
                            onConfirm={async () => {
                              await skillDelete(skill.name);
                              message.success('已删除');
                              load();
                            }}
                          >
                            <Button size="small" danger icon={<SparkDeleteLine />}>
                              删除
                            </Button>
                          </Popconfirm>
                        </>
                      ) : (
                        <>
                          <span style={{ flex: 1 }} />
                          <Button
                            size="small"
                            icon={<SparkEditLine />}
                            onClick={() => navigate(`/skills/${skill.name}`)}
                          >
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
                            <Button size="small" danger icon={<SparkDeleteLine />}>
                              删除
                            </Button>
                          </Popconfirm>
                        </>
                      )}
                    </div>
                  </div>
                </Col>
              );
            })}
          </Row>
        )}
      </Spin>

      <ResponsiveModal
        title="导入 Skill zip"
        open={importOpen}
        onOk={onImport}
        onCancel={() => setImportOpen(false)}
        okText="导入"
        confirmLoading={importing}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Upload.Dragger
            accept=".zip"
            maxCount={1}
            fileList={importFileList}
            beforeUpload={(file) => {
              setImportFileList([file as unknown as UploadFile]);
              return false;
            }}
            onRemove={() => setImportFileList([])}
          >
            <p style={{ marginBottom: 4 }}>点击或拖拽 zip 到此处</p>
            <p style={{ color: '#999', fontSize: 12, margin: 0 }}>
              单 skill（根级 SKILL.md）或多 skill（首层目录各含 SKILL.md）；≤ 20MB
            </p>
          </Upload.Dragger>
          <Radio.Group
            value={importTarget}
            onChange={(e) => setImportTarget(e.target.value)}
            options={[
              { label: '存入 OSS（同名覆盖）', value: 'oss' },
              { label: '存入平台库（同名更新）', value: 'mysql' },
            ]}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            OSS 落点写入 {ossStatus?.bucket || '—'}/{ossStatus?.prefix || 'teapot-ai/skills/'}；
            同名导入即覆盖，导入后绑定该 skill 的 Agent 下一轮生效。
          </Typography.Text>
        </Space>
      </ResponsiveModal>

      <ResponsiveModal
        title="从 Git 仓库导入 Skill"
        open={gitImportOpen}
        onOk={onGitImport}
        onCancel={() => setGitImportOpen(false)}
        okText="导入"
        confirmLoading={gitImporting}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div>
            <div style={{ fontSize: 13, marginBottom: 4 }}>仓库地址</div>
            <Input
              placeholder="https://github.com/xxx/skills.git"
              value={gitImportUrl}
              onChange={(e) => setGitImportUrl(e.target.value)}
              allowClear
            />
          </div>
          <div>
            <div style={{ fontSize: 13, marginBottom: 4 }}>分支（可选）</div>
            <Input
              placeholder="默认仓库主分支"
              value={gitImportBranch}
              onChange={(e) => setGitImportBranch(e.target.value)}
              allowClear
            />
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            仅支持可公开访问的仓库；需含 SKILL.md（根级或首层子目录）。
            导入时从远程拉取一次，内容存入平台数据库，同名再次导入即更新。
          </Typography.Text>
        </Space>
      </ResponsiveModal>
    </div>
  );
}
