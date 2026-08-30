import { Tooltip } from 'antd';
import { IconButton } from '@agentscope-ai/design';
import { SparkSettingLine } from '@agentscope-ai/icons';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/auth';
import UserMenu from './UserMenu';

/** 角色 → 中文展示名（副标题小字） */
const ROLE_LABEL: Record<string, string> = {
  admin: '管理员',
  developer: '开发者',
  user: '用户',
};

/**
 * 对话页左栏底部用户区（图二式布局）：
 * 左 = 用户信息（头像 + 用户名 + 角色副标题，点击出菜单）；右 = 系统配置入口（仅 admin）。
 * 由 SessionPanel footer 插槽渲染，替代原顶栏右上角的用户菜单与「系统配置」导航项。
 * compact：手机首页态单行紧凑样式（隐藏副标题），避免被浏览器底部栏遮挡/截断。
 */
export default function UserFooter(props: { compact?: boolean }) {
  const { compact } = props;
  const navigate = useNavigate();
  const { user, hasRole } = useAuthStore();

  const primary = (user?.roles || '').split(',').map((r) => r.trim())[0] || '';
  const subtitle = ROLE_LABEL[primary] || primary || '';

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        // 底部预留安全区边距（env 兼容异形屏），避免内容贴底被遮
        padding: compact
          ? '8px 6px calc(8px + env(safe-area-inset-bottom))'
          : '10px 6px calc(10px + env(safe-area-inset-bottom))',
        borderTop: '1px solid rgba(0, 0, 0, 0.06)',
        flexShrink: 0,
      }}
    >
      <div style={{ flex: 1, minWidth: 0 }}>
        <UserMenu
          showName
          avatarSize={compact ? 28 : 30}
          subtitle={
            !compact && subtitle ? (
              <div
                style={{
                  fontSize: 11,
                  color: 'rgba(26, 26, 29, 0.45)',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {subtitle}
              </div>
            ) : undefined
          }
        />
      </div>
      {/* 系统配置入口（SPEC §21）：仅 admin 可见，替代顶栏导航里的「系统配置」项 */}
      {hasRole('admin') && (
        <Tooltip title="系统配置">
          <IconButton
            bordered={false}
            icon={<SparkSettingLine size={18} />}
            onClick={() => navigate('/system')}
          />
        </Tooltip>
      )}
    </div>
  );
}
