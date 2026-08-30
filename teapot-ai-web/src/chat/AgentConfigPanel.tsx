import { useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Collapse, Spin, Tag } from 'antd';
import { agentDetail } from '../api/agent';
import type {
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

/**
 * 对话页右侧边栏：当前 Agent 配置总览（Basic Info / Skill / Tool & Advanced /
 * MultiAgent / Channel / Sandbox / MCP），内容来自 /api/agent/detail 的 feature。
 */
export default function AgentConfigPanel({ agentKey }: { agentKey: string }) {
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<AgentDetail | null>(null);

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
    <aside
      style={{
        width: 320,
        flexShrink: 0,
        height: '100%',
        overflowY: 'auto',
        borderLeft: '1px solid rgba(0, 0, 0, 0.05)',
        padding: '48px 16px 16px',
        boxSizing: 'border-box',
      }}
    >
      <div style={{ fontSize: 14, fontWeight: 700, color: 'rgba(26, 26, 29, 0.92)', marginBottom: 12 }}>
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
  );
}
