import { useCallback, useEffect, useMemo, useState } from 'react';
import { Dropdown, Input, Popconfirm, Space, Typography, Upload } from 'antd';
import type { UploadFile } from 'antd';
import { Button, message, Radio } from '@agentscope-ai/design';
import {
  SparkDownArrowLine,
  SparkLinkLine,
  SparkMagicWandLine,
  SparkPlusLine,
  SparkRefreshLine,
} from '@agentscope-ai/icons';
import { agentBindSkill } from '../api/agent';
import {
  skillDelete,
  skillGitStatus,
  skillGitSync,
  skillImport,
  skillImportFromGit,
  skillOssStatus,
} from '../api/skill';
import { useAuthStore } from '../store/auth';
import { ResponsiveModal } from './ResponsiveModal';
import type { SkillGitStatus, SkillListItem, SkillOssStatus } from '../types';

/**
 * Agent 内的 Add Skill（SPEC §29.10/§33）：平台不再提供独立的 Skill 工坊，
 * skill 本体只能在这里进来（zip 上传 / 任意 Git 仓库），导入成功即绑定到当前 Agent。
 * 落点权限沿用工坊的判定：zip 导入要 OSS 开着且是 admin/developer，git 导入要 admin/developer。
 *
 * <p>第三个入口「绑定已有 Skill」是 Skills 分区收窄成"只显示本 Agent 已绑定的"之后
 * 唯一的取用口：库里没绑进来的条目、以及"谁都没用了、该删本体"的孤儿，都只在这里处理。
 *
 * @param candidates 库里尚未绑定到本 Agent 的 skill（父级按 agentKey 口径算好）
 */
export function AddSkillButton({
  agentKey,
  onAdded,
  candidates,
}: {
  agentKey: string;
  onAdded: () => void;
  candidates: SkillListItem[];
}) {
  const canImport = useAuthStore((s) => s.hasRole('admin', 'developer'));
  const isAdmin = useAuthStore((s) => s.hasRole('admin'));
  const [ossStatus, setOssStatus] = useState<SkillOssStatus | null>(null);
  const [gitStatus, setGitStatus] = useState<SkillGitStatus | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [target, setTarget] = useState<'oss' | 'mysql'>('oss');
  const [busy, setBusy] = useState(false);
  const [gitOpen, setGitOpen] = useState(false);
  const [gitUrl, setGitUrl] = useState('');
  const [gitBranch, setGitBranch] = useState('');
  const [syncing, setSyncing] = useState(false);
  const [pickOpen, setPickOpen] = useState(false);
  const [keyword, setKeyword] = useState('');

  useEffect(() => {
    if (!canImport) {
      return;
    }
    skillOssStatus().then(setOssStatus).catch(() => setOssStatus(null));
    skillGitStatus().then(setGitStatus).catch(() => setGitStatus(null));
  }, [canImport]);

  /** 导入即绑定：一个 skill 都没绑上也要刷新列表，让导入结果可见 */
  const bindAll = useCallback(
    async (names: string[]) => {
      const failed: string[] = [];
      for (const name of names) {
        try {
          await agentBindSkill(agentKey, name);
        } catch {
          failed.push(name);
        }
      }
      if (failed.length) {
        message.warning(`已导入，但绑定失败：${failed.join('、')}（请在列表里手动打开开关）`);
      } else {
        message.success(`已导入并绑定：${names.join('、')}`);
      }
      onAdded();
    },
    [agentKey, onAdded],
  );

  const onImportZip = async () => {
    const file = fileList[0]?.originFileObj as File | undefined;
    if (!file) {
      message.warning('请选择 zip 文件');
      return;
    }
    setBusy(true);
    try {
      const result = await skillImport(file, target);
      setImportOpen(false);
      setFileList([]);
      await bindAll(result.imported);
    } finally {
      setBusy(false);
    }
  };

  const onImportGit = async () => {
    if (!gitUrl.trim()) {
      message.warning('请输入 Git 仓库地址');
      return;
    }
    setBusy(true);
    try {
      // 落点固定平台库：导入即内容入库，不需要再选存储位置
      const result = await skillImportFromGit(gitUrl.trim(), gitBranch.trim(), 'mysql');
      setGitOpen(false);
      setGitUrl('');
      setGitBranch('');
      await bindAll(result.imported);
    } finally {
      setBusy(false);
    }
  };

  /** 全局 Git 仓库手动同步（原工坊的「立即同步」）：只刷新仓库来源，不涉及绑定 */
  const onSyncGit = async () => {
    setSyncing(true);
    try {
      const status = await skillGitSync();
      setGitStatus(status);
      message.success(`同步完成，Git 来源 ${status.skillCount} 个 skill`);
      onAdded();
    } finally {
      setSyncing(false);
    }
  };

  /** 绑定弹窗里点「绑定」：成功后不关窗，条目随父级刷新自动消失，可连着绑多个 */
  const onPickBind = async (name: string) => {
    await agentBindSkill(agentKey, name);
    message.success(`已绑定 ${name}`);
    onAdded();
  };

  const onDeleteBody = async (name: string) => {
    await skillDelete(name);
    message.success(`已删除 ${name}`);
    onAdded();
  };

  const picked = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    if (!kw) {
      return candidates;
    }
    return candidates.filter(
      (s) => s.name.toLowerCase().includes(kw) || (s.description ?? '').toLowerCase().includes(kw),
    );
  }, [candidates, keyword]);

  const zipEnabled = canImport && ossStatus?.enabled !== false;
  const gitEnabled = canImport;
  if (!zipEnabled && !gitEnabled) {
    return null;
  }

  return (
    <>
      <Dropdown
        trigger={['click']}
        menu={{
          items: [
            ...(zipEnabled
              ? [{ key: 'zip', label: '上传 Skill zip', icon: <SparkMagicWandLine size={14} /> }]
              : []),
            ...(gitEnabled
              ? [{ key: 'git', label: '从 Git 仓库导入', icon: <SparkRefreshLine size={14} /> }]
              : []),
            // 分区只列已绑定的之后，这是"把库里现成的 skill 拿给本 Agent 用"的唯一入口
            ...(candidates.length > 0
              ? [{ key: 'pick', label: '绑定已有 Skill', icon: <SparkLinkLine size={14} /> }]
              : []),
          ],
          onClick: ({ key }) => {
            if (key === 'zip') {
              setFileList([]);
              setTarget('oss');
              setImportOpen(true);
            } else if (key === 'pick') {
              setKeyword('');
              setPickOpen(true);
            } else {
              setGitUrl('');
              setGitBranch('');
              setGitOpen(true);
            }
          },
        }}
      >
        <Button type="primary" icon={<SparkPlusLine />}>
          Add Skill <SparkDownArrowLine size={12} />
        </Button>
      </Dropdown>

      <ResponsiveModal
        title="导入 Skill zip"
        open={importOpen}
        onOk={onImportZip}
        onCancel={() => setImportOpen(false)}
        okText="导入并绑定"
        confirmLoading={busy}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Upload.Dragger
            accept=".zip"
            maxCount={1}
            fileList={fileList}
            beforeUpload={(file) => {
              setFileList([
                { uid: file.uid, name: file.name, size: file.size, type: file.type, originFileObj: file },
              ]);
              return false;
            }}
            onRemove={() => setFileList([])}
          >
            <p style={{ marginBottom: 4 }}>点击或拖拽 zip 到此处</p>
            <p style={{ color: '#999', fontSize: 12, margin: 0 }}>
              单 skill（根级 SKILL.md）或多 skill（首层目录各含 SKILL.md）；≤ 20MB
            </p>
          </Upload.Dragger>
          <Radio.Group
            value={target}
            onChange={(e) => setTarget(e.target.value)}
            options={[
              { label: '存入 OSS（同名覆盖）', value: 'oss' },
              { label: '存入平台库（同名更新）', value: 'mysql' },
            ]}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            导入成功后自动绑定到当前 Agent。
          </Typography.Text>
        </Space>
      </ResponsiveModal>

      <ResponsiveModal
        title="从 Git 仓库导入 Skill"
        open={gitOpen}
        onOk={onImportGit}
        onCancel={() => setGitOpen(false)}
        okText="导入并绑定"
        confirmLoading={busy}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div>
            <div style={{ fontSize: 13, marginBottom: 4 }}>仓库地址</div>
            <Input
              placeholder="https://github.com/xxx/skills.git"
              value={gitUrl}
              onChange={(e) => setGitUrl(e.target.value)}
              allowClear
            />
          </div>
          <div>
            <div style={{ fontSize: 13, marginBottom: 4 }}>分支（可选）</div>
            <Input
              placeholder="默认仓库主分支"
              value={gitBranch}
              onChange={(e) => setGitBranch(e.target.value)}
              allowClear
            />
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            需公开可读，含 SKILL.md（根级或首层子目录）；同名再次导入即更新。
          </Typography.Text>
          {gitStatus?.enabled ? (
            <Button size="small" icon={<SparkRefreshLine />} loading={syncing} onClick={onSyncGit}>
              同步全局 Git 仓库
            </Button>
          ) : null}
        </Space>
      </ResponsiveModal>
      <ResponsiveModal
        title="绑定已有 Skill"
        open={pickOpen}
        onCancel={() => setPickOpen(false)}
        footer={null}
        width={640}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Input
            placeholder="按名称或描述搜索"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            allowClear
          />
          <div style={{ maxHeight: 360, overflowY: 'auto' }}>
            {picked.length === 0 ? (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                没有匹配的 skill：换个关键词，或用 zip / Git 导入新的。
              </Typography.Text>
            ) : (
              picked.map((s) => (
                <div
                  key={s.name}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    padding: '10px 0',
                    borderBottom: '1px solid rgba(26, 26, 29, 0.06)',
                  }}
                >
                  <div style={{ minWidth: 0, flex: 1 }}>
                    <div
                      style={{
                        fontWeight: 600,
                        fontSize: 13,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {s.name}
                      {s.source && (
                        <span style={{ fontWeight: 400, fontSize: 11, color: 'rgba(26, 26, 29, 0.4)', marginLeft: 8 }}>
                          {s.source}
                        </span>
                      )}
                    </div>
                    <div
                      style={{
                        fontSize: 12,
                        color: 'rgba(26, 26, 29, 0.55)',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {s.description || '（无描述）'}
                    </div>
                  </div>
                  <span style={{ fontSize: 11, color: 'rgba(26, 26, 29, 0.4)', flexShrink: 0 }}>
                    {(s.boundAgents?.length ?? 0) > 0
                      ? `${s.boundAgents!.length} 个 Agent 在用`
                      : '未被任何 Agent 使用'}
                  </span>
                  {/* 孤儿本体只有在这一屏才看得见，所以删除入口跟着搬到这里（工坊早已整页下线） */}
                  {isAdmin && (s.boundAgents?.length ?? 0) === 0 && s.source !== 'git' && (
                    <Popconfirm
                      title={`删除 Skill 本体「${s.name}」？删除后不可恢复。`}
                      onConfirm={() => onDeleteBody(s.name)}
                    >
                      <Button size="small" danger type="text">
                        删除本体
                      </Button>
                    </Popconfirm>
                  )}
                  <Button size="small" type="primary" onClick={() => onPickBind(s.name)}>
                    绑定
                  </Button>
                </div>
              ))
            )}
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            这里只列本 Agent 还没绑定的 skill；绑好之后到 Skills 分区用开关决定它是否生效。
          </Typography.Text>
        </Space>
      </ResponsiveModal>
    </>
  );
}
