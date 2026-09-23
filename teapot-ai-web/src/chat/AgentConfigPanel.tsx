import { useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Collapse, Spin, Tag } from 'antd';
import { IconButton, Tooltip } from '@agentscope-ai/design';
import { SparkOperateLeftLine, SparkOperateRightLine } from '@agentscope-ai/icons';
import { agentDetail } from '../api/agent';
import type {
  AgentA2uiConfig,
  AgentChannelConfig,
  AgentDetail,
  AgentMCPConfig,
  AgentMultiAgentConfig,
  AgentRuntimeConfig,
  AgentSandboxConfig,
} from '../types';

/** feature JSON 解析后的局部结构（缺省命名空间 = 未配置） */
interface ParsedFeature {
  runtime?: AgentRuntimeConfig;
  sandbox?: AgentSandboxConfig;
  channel?: AgentChannelConfig;
  mcp?: AgentMCPConfig;
  multiagent?: AgentMultiAgentConfig;
  a2ui?: AgentA2uiConfig;
}

/** 键值行：左灰标签 + 右值 */
function KV({ k, v }: { k: string; v?: ReactNode }) {
  return (
    <div style={{ display: 'flex', gap: 8, fontSize: 12, padding: '3px 0', lineHeight: 1.5 }}>
      <span style={{ color: 'rgba(26, 26, 29, 0.45)', flexShrink: 0, width: 78 }}>{k}</span>
      <span style={{ color: 'rgba(26, 26, 29, 0.85)', wordBreak: 'break-all', minWidth: 0, flex: 1 }}>
        {v ?? '—'}
      </span>
    </div>
  );
}

function EnabledTag({ on }: { on: boolean }) {
  return <Tag color={on ? 'success' : 'default'}>{on ? '启用' : '关闭'}</Tag>;
}

const mono: React.CSSProperties = { fontFamily: 'Menlo, Consolas, monospace' };

/** 收起态跨会话记忆（与左栏 teapot-chat-left-collapsed 对称，仅宽屏渲染本面板） */
const LS_RIGHT_COLLAPSED = 'teapot-chat-agent-panel-collapsed';

/** 生成能力位中文名（SPEC-media-gen §4.8：与 runtime.mediaModels 字段对应，仅展示用） */
const MEDIA_MODEL_LABELS: Record<string, string> = {
  textToImage: '文生图',
  imageToImage: '图生图',
  textToVideo: '文生视频',
  imageToVideo: '图生视频',
  frameToVideo: '首尾帧视频',
  textToAudio: '语音合成',
};

/**
 * 对话页右侧边栏：当前 Agent 配置总览（Basic Info / Skill / Tool & Advanced /
 * MultiAgent / Channel / Sandbox / MCP），内容来自 /api/agent/detail 的 feature。
 */
export default function AgentConfigPanel({ agentKey }: { agentKey: string }) {
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<AgentDetail | null>(null);
  /** 收起态：整栏收成右缘竖条，把空间让给中间对话区 */
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem(LS_RIGHT_COLLAPSED) === '1');

  useEffect(() => {
    localStorage.setItem(LS_RIGHT_COLLAPSED, collapsed ? '1' : '0');
  }, [collapsed]);

  /** 顶栏折叠开关的 Portal 挂载点（AppLayout header 内 id=teapot-header-extra） */
  const [toggleSlot, setToggleSlot] = useState<HTMLElement | null>(null);
  useEffect(() => {
    let raf = 0;
    const find = () => {
      const el = document.getElementById('teapot-header-extra');
      if (el) setToggleSlot(el);
      else raf = requestAnimationFrame(find);
    };
    find();
    return () => cancelAnimationFrame(raf);
  }, []);

  useEffect(() => {
    if (!agentKey) return;
    let alive = true;
    setLoading(true);
    agentDetail(agentKey)
      .then((d) => { if (alive) setDetail(d); })
      .catch(() => { if (alive) setDetail(null); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [agentKey]);

  const feature = useMemo<ParsedFeature>(() => {
    try {
      return detail?.agent.feature ? JSON.parse(detail.agent.feature) : {};
    } catch {
      return {};
    }
  }, [detail]);

  const agent = detail?.agent;
  const rt = feature.runtime;
  const sb = feature.sandbox;
  const ch = feature.channel;
  const mcp = feature.mcp;
  const ma = feature.multiagent;
  const a2ui = feature.a2ui;

  const items = useMemo(() => {
    if (!agent) return [];
    return [
      {
        key: 'basic',
        label: 'Basic Info',
        children: (
          <div>
            <KV k="名称" v={agent.name} />
            <KV k="模型" v={<span style={mono}>{agent.modelId}</span>} />
            {agent.description && <KV k="描述" v={agent.description} />}
            {agent.sysPrompt && (
              <div style={{ marginTop: 6 }}>
                <div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.45)', marginBottom: 4 }}>Persona</div>
                <pre style={{ ...mono, margin: 0, fontSize: 11, lineHeight: 1.5, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 140, overflow: 'auto', background: 'rgba(0, 0, 0, 0.03)', borderRadius: 6, padding: 8 }}>
                  {agent.sysPrompt}
                </pre>
              </div>
            )}
          </div>
        ),
      },
      {
        key: 'skills',
        label: `Skill（${detail?.skillNames?.length ?? 0}）`,
        children: (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
            {(detail?.skillNames ?? []).length > 0
              ? (detail?.skillNames ?? []).map((s) => <Tag key={s}>{s}</Tag>)
              : <span style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.45)' }}>未绑定技能</span>}
          </div>
        ),
      },
      {
        key: 'tools',
        label: 'Tool & Advanced',
        children: (
          <div>
            <KV k="思考模式" v={rt?.thinkingMode !== undefined ? <EnabledTag on={!!rt.thinkingMode} /> : '默认'} />
            <KV k="计划模式" v={rt?.enablePlanMode !== undefined ? <EnabledTag on={!!rt.enablePlanMode} /> : '默认'} />
            <KV k="Shell" v={rt?.enableShell !== undefined ? <EnabledTag on={!!rt.enableShell} /> : '跟随沙箱'} />
            {rt?.enableOssFile && <KV k="OSS 文件" v={<EnabledTag on />} />}
            {rt?.enableMcpConfig && <KV k="MCP 配置查询" v={<EnabledTag on />} />}
            {rt?.enableMediaGen && <KV k="生图/生视频" v={<EnabledTag on />} />}
            {a2ui?.enabled && (
              <>
                <KV k="A2UI 界面" v={<EnabledTag on />} />
                <KV k="渲染后中断" v={<EnabledTag on={a2ui.stopAfterPresent !== false} />} />
                {a2ui.catalogResource && (
                  <KV k="A2UI Catalog" v={<span style={mono}>{a2ui.catalogResource}</span>} />
                )}
              </>
            )}
            {/* 指定的生成模型（SPEC-media-gen §4.8）：未配置的保持默认，不占行 */}
            {rt?.enableMediaGen
              && Object.entries(rt.mediaModels ?? {})
                .filter(([, v]) => typeof v === 'string' && v)
                .map(([k, v]) => (
                  <KV key={k} k={`${MEDIA_MODEL_LABELS[k] ?? k}模型`} v={<span style={mono}>{v}</span>} />
                ))}
            {rt?.permissionMode && (
              <KV
                k="权限模式"
                v={rt.permissionMode === 'EXPLORE' ? '只读探索'
                  : rt.permissionMode === 'BLOCK_DANGEROUS' ? '阻止危险命令'
                  : rt.permissionMode === 'BYPASS' ? '全部放行'
                  : rt.permissionMode}
              />
            )}
            {rt?.temperature !== undefined && <KV k="temperature" v={rt.temperature} />}
            {rt?.topP !== undefined && <KV k="topP" v={rt.topP} />}
            {rt?.maxTokens !== undefined && <KV k="maxTokens" v={rt.maxTokens} />}
            {rt?.maxIterations !== undefined && <KV k="最大迭代" v={rt.maxIterations} />}
            {rt?.allowedTools && rt.allowedTools.length > 0 && (
              <KV k="工具白名单" v={<span style={mono}>{rt.allowedTools.join(', ')}</span>} />
            )}
            {!rt && <span style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.45)' }}>全部跟随默认</span>}
          </div>
        ),
      },
      {
        key: 'multiagent',
        label: 'MultiAgent',
        children: (
          <KV k="Subagent" v={<EnabledTag on={ma?.enabled !== false} />} />
        ),
      },
      {
        key: 'channel',
        label: 'Channel',
        children: ch ? (
          <div>
            <KV k="状态" v={<EnabledTag on={!!ch.enabled} />} />
            {ch.channelRecord && <KV k="连接记录" v={<span style={mono}>{ch.channelRecord}</span>} />}
            <KV k="隔离粒度" v={ch.dmScope ?? 'PER_CHANNEL_PEER'} />
          </div>
        ) : <span style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.45)' }}>未配置渠道</span>,
      },
      {
        key: 'sandbox',
        label: 'Sandbox',
        children: sb ? (
          <div>
            <KV k="状态" v={<EnabledTag on={!!sb.enabled} />} />
            {sb.sandboxRecord && <KV k="承载记录" v={<span style={mono}>{sb.sandboxRecord}</span>} />}
            {sb.isolationScope && <KV k="隔离维度" v={sb.isolationScope} />}
            {sb.persistence && <KV k="持久化" v={sb.persistence} />}
            {sb.templateName && <KV k="模板" v={<span style={mono}>{sb.templateName}</span>} />}
          </div>
        ) : <span style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.45)' }}>未配置沙箱</span>,
      },
      {
        key: 'mcp',
        label: `MCP（${mcp?.mcpServers?.length ?? 0}）`,
        children: mcp ? (
          <div>
            <KV k="状态" v={<EnabledTag on={!!mcp.enabled} />} />
            {(mcp.mcpServers ?? []).map((s, i) => (
              <div key={i} style={{ fontSize: 12, padding: '4px 0', borderTop: i > 0 ? '1px dashed rgba(0, 0, 0, 0.06)' : undefined }}>
                {s.record ? (
                  <span><Tag color="blue">系统记录</Tag><span style={mono}>{s.record}</span></span>
                ) : (
                  <span>
                    <Tag color="green">自定义</Tag>
                    <span style={mono}>{s.transport === 'stdio' ? s.command : s.url}</span>
                  </span>
                )}
                {s.description && (
                  <div style={{ color: 'rgba(26, 26, 29, 0.45)', marginTop: 2 }}>{s.description}</div>
                )}
              </div>
            ))}
            {(mcp.mcpServers ?? []).length === 0 && (
              <span style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.45)' }}>未配置 Server</span>
            )}
          </div>
        ) : <span style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.45)' }}>未配置 MCP</span>,
      },
    ];
  }, [agent, detail, rt, sb, ch, mcp, ma]);

  return (
    <>
      {toggleSlot &&
        createPortal(
          <Tooltip title={collapsed ? '展开 Agent 配置' : '收起 Agent 配置'}>
            <IconButton
              bordered={false}
              aria-label="切换 Agent 配置"
              icon={collapsed ? <SparkOperateLeftLine size={16} /> : <SparkOperateRightLine size={16} />}
              onClick={() => setCollapsed((v) => !v)}
            />
          </Tooltip>,
          toggleSlot,
        )}
      {!collapsed && (
        <aside
          style={{
            width: 320,
            flexShrink: 0,
            height: '100%',
            overflowX: 'hidden',
            overflowY: 'auto',
            borderLeft: '1px solid rgba(0, 0, 0, 0.05)',
            padding: '16px 16px',
            boxSizing: 'border-box',
          }}
        >
          <div
            style={{
              marginBottom: 12,
              fontSize: 14,
              fontWeight: 700,
              color: 'rgba(26, 26, 29, 0.92)',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            Agent 配置
          </div>
          {loading ? (
            <div style={{ textAlign: 'center', padding: 24 }}><Spin /></div>
          ) : !detail ? (
            <span style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.45)' }}>暂无配置信息</span>
          ) : (
            <Collapse
              size="small"
              bordered={false}
              defaultActiveKey={['basic', 'skills', 'tools', 'mcp']}
              items={items}
            />
          )}
        </aside>
      )}
    </>
  );
}
