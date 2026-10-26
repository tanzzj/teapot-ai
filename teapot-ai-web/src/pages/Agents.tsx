import { useCallback, useEffect, useState } from 'react';
import { Col, Row, Spin } from 'antd';
import {
  Button,
  Empty,
  Input,
  message,
  Pagination,
  Popconfirm,
  Select,
  Switch,
} from '@agentscope-ai/design';
import {
  SparkDeleteLine,
  SparkEditLine,
  SparkMessageLine,
  SparkPlusLine,
  SparkSearchLine,
} from '@agentscope-ai/icons';
import { useNavigate } from 'react-router-dom';
import { agentDelete, agentList, agentMoveWorkspace } from '../api/agent';
import { NEW_AGENT_KEY } from './AgentDetail';
import type { Agent } from '../types';
import { ResponsiveModal } from '../components/ResponsiveModal';
import { useWorkspaceStore } from '../store/workspace';

/**
 * Agent 管理列表（大标题 + 三列毛玻璃卡片网格）。
 * SPEC §12.2
 */
export default function Agents() {
  const navigate = useNavigate();
  const { workspaces, activeId, loaded, load: loadWorkspaces } = useWorkspaceStore();
  const [list, setList] = useState<Agent[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [includeDisabled, setIncludeDisabled] = useState(false);
  const [loading, setLoading] = useState(false);
  const [moveAgent, setMoveAgent] = useState<Agent | null>(null);
  const [moveTarget, setMoveTarget] = useState<string>('personal');

  useEffect(() => {
    if (!loaded) {
      loadWorkspaces();
    }
  }, [loaded, loadWorkspaces]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const resp = await agentList({
        page,
        size: 12,
        keyword: keyword || undefined,
        includeDisabled,
        workspaceId: activeId ?? undefined,
      });
      setList(resp.list || []);
      setTotal(resp.total || 0);
    } catch {
      // 已统一提示
    } finally {
      setLoading(false);
    }
  }, [page, keyword, includeDisabled, activeId]);

  useEffect(() => {
    load();
  }, [load]);

  const spaceOptions = [
    { value: 'personal', label: '个人空间' },
    ...workspaces
      .filter((w) => w.id !== null && w.id !== undefined)
      .map((w) => ({ value: String(w.id), label: w.workspaceName })),
  ];

  const onMove = async () => {
    if (!moveAgent) {
      return;
    }
    await agentMoveWorkspace(moveAgent.agentKey, moveTarget === 'personal' ? null : Number(moveTarget));
    message.success('已移动到目标空间');
    setMoveAgent(null);
    load();
  };

  return (
    <div style={{ padding: '24px 28px', minHeight: '100%' }}>
      {/* 标题行（大标题 + 副标题 + 右侧黑色主按钮） */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 20, flexWrap: 'wrap' }}>
        <h2 style={{ margin: 0, fontSize: 26, fontWeight: 700, color: 'rgba(26, 26, 29, 0.92)' }}>
          Agents
        </h2>
        <div style={{ flex: 1 }} />
        <Input
          placeholder="按名称/标识搜索"
          prefix={<SparkSearchLine />}
          allowClear
          style={{ width: 200 }}
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value);
            setPage(1);
          }}
        />
        <span style={{ color: 'rgba(26, 26, 29, 0.55)', fontSize: 13, whiteSpace: 'nowrap' }}>
          显示已停用
          <Switch
            size="small"
            style={{ marginLeft: 6 }}
            checked={includeDisabled}
            onChange={(v) => {
              setIncludeDisabled(v);
              setPage(1);
            }}
          />
        </span>
        <Button
          type="primary"
          icon={<SparkPlusLine />}
          onClick={() => navigate(`/agents/${NEW_AGENT_KEY}`)}
        >
          New Agent
        </Button>
      </div>

      <Spin spinning={loading}>
        {list.length === 0 && !loading ? (
          <Empty description="暂无 Agent" style={{ marginTop: 60 }} />
        ) : (
          <Row gutter={[16, 16]}>
            {list.map((agent) => (
              <Col key={agent.agentKey} xs={24} md={12} xl={8} style={{ display: 'flex' }}>
                <div
                  className="glass-card"
                  style={{ padding: 20, cursor: 'pointer', display: 'flex', flexDirection: 'column', gap: 12, flex: 1, minWidth: 0 }}
                  onClick={() => navigate(`/agents/${agent.agentKey}`)}
                >
                  {/* 卡片头：头像（SPEC §23：有头像图则展示，否则首字母占位） + 名称/key + 类型标签 */}
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    {agent.avatar ? (
                      <img
                        src={agent.avatar}
                        alt={agent.name}
                        style={{ width: 44, height: 44, borderRadius: 999, objectFit: 'cover', flexShrink: 0 }}
                      />
                    ) : (
                      <span
                        style={{
                          width: 44,
                          height: 44,
                          borderRadius: 999,
                          background: 'linear-gradient(135deg, #2b2b31, #1a1a1d)',
                          color: '#fff',
                          display: 'inline-flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          fontSize: 18,
                          fontWeight: 700,
                          flexShrink: 0,
                        }}
                      >
                        {(agent.name || agent.agentKey).charAt(0).toUpperCase()}
                      </span>
                    )}
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <div style={{ fontWeight: 600, fontSize: 15, color: 'rgba(26, 26, 29, 0.92)' }}>
                        {agent.name}
                      </div>
                      <div
                        style={{
                          fontFamily: 'Menlo, Consolas, monospace',
                          fontSize: 11,
                          color: 'rgba(26, 26, 29, 0.55)',
                          background: 'rgba(0, 0, 43, 0.05)',
                          borderRadius: 6,
                          padding: '1px 8px',
                          display: 'inline-block',
                          marginTop: 2,
                        }}
                      >
                        {agent.agentKey}
                      </div>
                    </div>
                    {agent.status !== 1 && (
                      <span style={{ fontSize: 12, fontWeight: 500, color: 'rgba(26, 26, 29, 0.35)', whiteSpace: 'nowrap' }}>
                        已停用
                      </span>
                    )}
                  </div>

                  {/* 描述：单行省略 */}
                  <div
                    style={{
                      color: 'rgba(26, 26, 29, 0.6)',
                      fontSize: 13,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {agent.description || '（无描述）'}
                  </div>

                  {/* 底部操作（对话 / 编辑 / 删除） */}
                  <div
                    style={{
                      borderTop: '1px solid rgba(0, 0, 0, 0.06)',
                      paddingTop: 12,
                      display: 'flex',
                      gap: 18,
                      alignItems: 'center',
                      marginTop: 'auto',
                    }}
                    onClick={(e) => e.stopPropagation()}
                  >
                    <span
                      style={{ display: 'inline-flex', gap: 6, alignItems: 'center', color: 'rgba(26, 26, 29, 0.88)', fontWeight: 500, fontSize: 13, cursor: 'pointer' }}
                      onClick={() => navigate(`/chat?agent=${agent.agentKey}`)}
                    >
                      <SparkMessageLine /> 对话
                    </span>
                    <span
                      style={{ display: 'inline-flex', gap: 6, alignItems: 'center', color: 'rgba(26, 26, 29, 0.55)', fontSize: 13, cursor: 'pointer' }}
                      onClick={() => navigate(`/agents/${agent.agentKey}`)}
                    >
                      <SparkEditLine /> 编辑
                    </span>
                    <span
                      style={{ display: 'inline-flex', gap: 6, alignItems: 'center', color: 'rgba(26, 26, 29, 0.55)', fontSize: 13, cursor: 'pointer' }}
                      onClick={() => {
                        setMoveTarget(agent.workspaceId ? String(agent.workspaceId) : 'personal');
                        setMoveAgent(agent);
                      }}
                    >
                      移动
                    </span>
                    <Popconfirm
                      title="确认停用并删除该 Agent？"
                      onConfirm={async () => {
                        await agentDelete(agent.agentKey);
                        message.success('已删除');
                        load();
                      }}
                    >
                      <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center', color: 'rgba(26, 26, 29, 0.35)', fontSize: 13, cursor: 'pointer' }}>
                        <SparkDeleteLine /> 删除
                      </span>
                    </Popconfirm>
                  </div>
                </div>
              </Col>
            ))}
          </Row>
        )}
      </Spin>

      <div style={{ marginTop: 20, textAlign: 'right' }}>
        <Pagination current={page} pageSize={12} total={total} onChange={setPage} showSizeChanger={false} />
      </div>

      {/* 移动 Agent 到目标空间（SPEC §28.6 需求 5，卡片级） */}
      <ResponsiveModal
        title="移动 Agent 到空间"
        open={moveAgent !== null}
        onOk={onMove}
        onCancel={() => setMoveAgent(null)}
        okText="移动"
        cancelText="取消"
      >
        <div style={{ marginBottom: 8, color: 'rgba(26, 26, 29, 0.55)', fontSize: 13 }}>
          当前 Agent：{moveAgent?.name}（{moveAgent?.agentKey}）
        </div>
        <Select
          value={moveTarget}
          onChange={(v) => setMoveTarget(String(v))}
          style={{ width: '100%' }}
          options={spaceOptions}
        />
      </ResponsiveModal>
    </div>
  );
}
