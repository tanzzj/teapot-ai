import { useCallback, useEffect, useState } from 'react';
import { Checkbox, Col, Row, Spin } from 'antd';
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
  SparkBatchMoveLine,
  SparkDeleteLine,
  SparkEditLine,
  SparkMessageLine,
  SparkPlusLine,
  SparkSearchLine,
} from '@agentscope-ai/icons';
import { useNavigate } from 'react-router-dom';
import { agentDelete, agentList, agentMoveWorkspace, agentMoveWorkspaceBatch } from '../api/agent';
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
  /** 批量移动（SPEC §35）：选择态只在当前这一页有效，翻页/搜索/换空间即清空 */
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [batchOpen, setBatchOpen] = useState(false);
  const [batchTarget, setBatchTarget] = useState<string>('personal');
  const [batchBusy, setBatchBusy] = useState(false);

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
      // 列表内容变了，上一批勾选就不再对应屏幕上的卡片了
      setSelected([]);
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

  const toggleSelect = (agentKey: string) => {
    setSelected((prev) => (prev.includes(agentKey) ? prev.filter((k) => k !== agentKey) : [...prev, agentKey]));
  };

  const exitSelect = () => {
    setSelecting(false);
    setSelected([]);
  };

  const selectedAgents = list.filter((a) => selected.includes(a.agentKey));

  const onBatchMove = async () => {
    setBatchBusy(true);
    try {
      const moved = await agentMoveWorkspaceBatch(
        selected,
        batchTarget === 'personal' ? null : Number(batchTarget),
      );
      const name = batchTarget === 'personal' ? '个人空间' : spaceOptions.find((o) => o.value === batchTarget)?.label;
      message.success(`已移动 ${moved} 个 Agent 到「${name}」`);
      setBatchOpen(false);
      exitSelect();
      load();
      // 移走之后当前空间少了一批，顶栏空间计数要跟着变，否则下拉里还是旧数字
      loadWorkspaces();
    } finally {
      setBatchBusy(false);
    }
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
        {selecting ? (
          <>
            <span style={{ color: 'rgba(26, 26, 29, 0.55)', fontSize: 13, whiteSpace: 'nowrap' }}>
              已选 {selected.length} 个
            </span>
            <Button
              type="primary"
              disabled={selected.length === 0}
              onClick={() => {
                setBatchTarget('personal');
                setBatchOpen(true);
              }}
            >
              移动到空间
            </Button>
            <Button type="text" onClick={exitSelect}>
              取消
            </Button>
          </>
        ) : (
          <>
            <Button
              icon={<SparkBatchMoveLine />}
              disabled={list.length === 0}
              onClick={() => setSelecting(true)}
            >
              批量移动
            </Button>
            <Button
              type="primary"
              icon={<SparkPlusLine />}
              onClick={() => navigate(`/agents/${NEW_AGENT_KEY}`)}
            >
              New Agent
            </Button>
          </>
        )}
      </div>

      <Spin spinning={loading}>
        {list.length === 0 && !loading ? (
          <Empty description="暂无 Agent" style={{ marginTop: 60 }} />
        ) : (
          <Row gutter={[16, 16]}>
            {list.map((agent) => {
              const isSelected = selected.includes(agent.agentKey);
              return (
              <Col key={agent.agentKey} xs={24} md={12} xl={8} style={{ display: 'flex' }}>
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
                    // 选择态用描边标出勾选，卡片点击从"进详情"改成"切换选中"
                    outline: isSelected ? '2px solid #5bb98b' : 'none',
                    outlineOffset: -2,
                  }}
                  onClick={() => (selecting ? toggleSelect(agent.agentKey) : navigate(`/agents/${agent.agentKey}`))}
                >
                  {/* 卡片头：勾选框（仅选择态）+ 头像（SPEC §23：有头像图则展示，否则首字母占位） + 名称/key + 类型标签 */}
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    {selecting && <Checkbox checked={isSelected} style={{ pointerEvents: 'none' }} />}
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

                  {/* 底部操作（对话 / 编辑 / 移动 / 删除）：选择态下整排收起，此时点卡片是勾选而不是进详情 */}
                  {!selecting && (
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
                  )}
                </div>
              </Col>
              );
            })}
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
      {/* 批量移动（SPEC §35）：整批一个事务，失败即全批回滚 */}
      <ResponsiveModal
        title={`批量移动 ${selected.length} 个 Agent`}
        open={batchOpen}
        onOk={onBatchMove}
        onCancel={() => setBatchOpen(false)}
        okText="移动"
        cancelText="取消"
        confirmLoading={batchBusy}
      >
        <div style={{ marginBottom: 10, color: 'rgba(26, 26, 29, 0.65)', fontSize: 13, lineHeight: 1.7 }}>
          {selectedAgents
            .slice(0, 6)
            .map((a) => `${a.name}（${a.agentKey}）`)
            .join('、')}
          {selectedAgents.length > 6 ? ` 等 ${selectedAgents.length} 个` : ''}
        </div>
        <Select
          value={batchTarget}
          onChange={(v) => setBatchTarget(String(v))}
          style={{ width: '100%' }}
          options={spaceOptions}
        />
        <div style={{ marginTop: 10, color: 'rgba(26, 26, 29, 0.45)', fontSize: 12 }}>
          整批一次提交：其中任何一个已停用或你对其所在空间无权限，本次就全部不移动。
        </div>
      </ResponsiveModal>
    </div>
  );
}
