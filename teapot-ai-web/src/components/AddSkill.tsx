import { useCallback, useEffect, useState } from 'react';
import { Dropdown, Input, Space, Typography, Upload } from 'antd';
import type { UploadFile } from 'antd';
import { Button, message, Radio } from '@agentscope-ai/design';
import {
  SparkDownArrowLine,
  SparkMagicWandLine,
  SparkPlusLine,
  SparkRefreshLine,
} from '@agentscope-ai/icons';
import { agentBindSkill } from '../api/agent';
import {
  skillGitStatus,
  skillGitSync,
  skillImport,
  skillImportFromGit,
  skillOssStatus,
} from '../api/skill';
import { useAuthStore } from '../store/auth';
import { ResponsiveModal } from './ResponsiveModal';
import type { SkillGitStatus, SkillOssStatus } from '../types';

/**
 * Agent 内的 Add Skill（SPEC §29.10）：平台不再提供独立的 Skill 工坊，
 * skill 本体只能在这里进来（zip 上传 / 任意 Git 仓库），导入成功即绑定到当前 Agent。
 * 落点权限沿用工坊的判定：zip 导入要 OSS 开着且是 admin/developer，git 导入要 admin/developer。
 */
export function AddSkillButton({ agentKey, onAdded }: { agentKey: string; onAdded: () => void }) {
  const canImport = useAuthStore((s) => s.hasRole('admin', 'developer'));
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
          ],
          onClick: ({ key }) => {
            if (key === 'zip') {
              setFileList([]);
              setTarget('oss');
              setImportOpen(true);
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
            OSS 落点写入 {ossStatus?.bucket || '—'}/{ossStatus?.prefix || 'teapot-ai/skills/'}；
            导入成功的 skill 会自动绑定到当前 Agent，其它 Agent 需要用的话在各自 Agent 里打开开关。
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
            仅支持可公开访问的仓库；需含 SKILL.md（根级或首层子目录）。导入时从远程拉取一次，
            内容存入平台数据库，同名再次导入即更新。
            {gitStatus?.enabled
              ? `另有一个全局管控仓库（${gitStatus.branch || '默认分支'}），用下面的按钮刷新它的来源。`
              : ''}
          </Typography.Text>
          {gitStatus?.enabled ? (
            <Button size="small" icon={<SparkRefreshLine />} loading={syncing} onClick={onSyncGit}>
              同步全局 Git 仓库
            </Button>
          ) : null}
        </Space>
      </ResponsiveModal>
    </>
  );
}
