import { useCallback, useRef, useState } from 'react';
import { Dropdown, message } from 'antd';
import { Avatar } from '@agentscope-ai/design';
import { SparkCameraLine, SparkEscapeLine, SparkUserLine } from '@agentscope-ai/icons';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/auth';
import { uploadUserAvatar } from '../api/avatar';

/**
 * 用户菜单（头像 + 用户名）：点击下拉展示角色 / 更换头像 / 退出登录。
 * 顶栏（非对话页）与对话页左栏底部 UserFooter 共用；头像上传逻辑（SPEC §23）集中于此。
 */
export default function UserMenu(props: {
  /** 是否展示用户名（移动端顶栏只出头像） */
  showName?: boolean;
  /** 用户名下方副标题（底部布局用，如角色名） */
  subtitle?: React.ReactNode;
  /** 头像尺寸（顶栏小头像 / 底部大头像） */
  avatarSize?: number;
}) {
  const navigate = useNavigate();
  const { user, logout, setUserPatch } = useAuthStore();
  const [avatarUploading, setAvatarUploading] = useState(false);
  const avatarInputRef = useRef<HTMLInputElement>(null);

  /** 用户头像上传（SPEC §23）：客户端预检 2MB/图片类型，服务端复检 */
  const onAvatarFile = useCallback(
    async (file: File | undefined) => {
      if (!file || avatarUploading) {
        return;
      }
      if (!file.type.startsWith('image/')) {
        message.error('仅支持图片格式头像');
        return;
      }
      if (file.size > 2 * 1024 * 1024) {
        message.error('头像超过 2MB 限制');
        return;
      }
      setAvatarUploading(true);
      try {
        const { url } = await uploadUserAvatar(file);
        setUserPatch({ avatar: url });
        message.success('头像已更新');
      } catch (e) {
        message.error(e instanceof Error ? e.message : '头像上传失败');
      } finally {
        setAvatarUploading(false);
        if (avatarInputRef.current) {
          avatarInputRef.current.value = '';
        }
      }
    },
    [avatarUploading, setUserPatch],
  );

  return (
    <>
      <Dropdown
        trigger={['click']}
        menu={{
          items: [
            { key: 'roles', label: `角色：${user?.roles || '-'}`, disabled: true },
            { type: 'divider' },
            {
              key: 'avatar',
              label: avatarUploading ? '头像上传中…' : '更换头像',
              icon: <SparkCameraLine />,
              disabled: avatarUploading,
            },
            { key: 'logout', label: '退出登录', icon: <SparkEscapeLine /> },
          ],
          onClick: ({ key }: { key: string }) => {
            if (key === 'logout') {
              logout();
              navigate('/login');
            } else if (key === 'avatar') {
              avatarInputRef.current?.click();
            }
          },
        }}
      >
        <div
          style={{
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            minWidth: 0,
          }}
        >
          <Avatar
            size={props.avatarSize || 'small'}
            src={user?.avatar || undefined}
            icon={<SparkUserLine />}
          />
          {props.showName && (
            <div style={{ minWidth: 0, lineHeight: 1.3 }}>
              <div
                style={{
                  fontSize: 13,
                  fontWeight: 500,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {user?.realName || user?.username || '未登录'}
              </div>
              {props.subtitle}
            </div>
          )}
        </div>
      </Dropdown>
      {/* 头像选择器（SPEC §23）：Dropdown 菜单项触发；
          原生 input[file] 为浏览器文件选择唯一通道，豁免于禁用原生表单标签规则（SPEC-mobile M8） */}
      <input
        ref={avatarInputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp,image/gif"
        style={{ display: 'none' }}
        onChange={(e) => onAvatarFile(e.target.files?.[0])}
      />
    </>
  );
}
