import { useMemo, useRef, useState } from 'react';
import type { DragEvent } from 'react';
import { Alert, Dropdown, Empty, Radio, Tooltip } from 'antd';
import { Button, Form, Input, Popconfirm, Select, Tag, message } from '@agentscope-ai/design';
import {
  SparkDeleteLine,
  SparkDragDotLine,
  SparkEditLine,
  SparkInfoLine,
  SparkPlusLine,
  SparkToolboxLine,
} from '@agentscope-ai/icons';
import { ResponsiveModal } from './ResponsiveModal';
import type { AgentToolGroup, ToolCatalogEntry } from '../types';

/**
 * 工具分组分区（SPEC §31）：左边是这个版本注册得出来的工具目录，右边是分组卡片。
 * 目录里的工具可以拖进组，也可以点一下选组；组里的标签同样能拖去别的组，或拖回目录取消入组。
 *
 * <p>界面上没有「启用某个组」的开关：入组只决定"默认不给模型看见"，装备权在模型手里
 * （它调 reset_equipped_tools 按组名开关，组说明就是它的判断依据）。框架里组的激活状态
 * 存在每个会话自己的上下文里、且只在会话首次创建时取一次初值，服务端配置写不进去 —— 
 * 摆一个"初始激活"开关只会是个不生效的字段。
 */

/** 组名规则（与后端 AgentFeature.TOOL_GROUP_NAME_PATTERN 同一份口径） */
const NAME_RE = /^[a-z][a-z0-9_-]{1,63}$/;
/** 工具名规则：MCP 工具名由服务端自报，目录里没有也允许手填 */
const TOOL_NAME_RE = /^[A-Za-z0-9_.-]{1,128}$/;
const MAX_GROUPS = 20;
const MAX_TOOLS = 60;
const DESC_MAX = 500;
/** 框架保留组名：无分组的工具都算在它里面且始终激活 */
const RESERVED = 'basic';
/**
 * 必须常显的工具（与后端 AgentFeature.ungroupableToolNames() 同一份口径）：
 * meta tool 自己被入组，就没有工具能把组装备回来；技能加载器入组则技能全废。
 */
const UNGROUPABLE = ['reset_equipped_tools', 'load_skill_through_path'];

const SOURCE_LABEL: Record<ToolCatalogEntry['source'], string> = {
  harness: '框架自带',
  platform: '平台扩展',
  media: '生图生视频',
};
const SOURCE_ORDER: ToolCatalogEntry['source'][] = ['harness', 'platform', 'media'];

/** 保存前清理：trim、去空、组内去重（顺序保留）；不合法的组不静默丢，交给 toolGroupsProblem 报出来 */
export function cleanToolGroups(groups: AgentToolGroup[]): AgentToolGroup[] {
  return groups.map((g) => {
    const out: AgentToolGroup = {
      name: (g.name ?? '').trim(),
      tools: Array.from(new Set((g.tools ?? []).map((t) => (t ?? '').trim()).filter(Boolean))),
    };
    const description = (g.description ?? '').trim();
    if (description) {
      out.description = description;
    }
    return out;
  });
}

/** 保存前自检：命中就返回一句能直接提示用户的话，没问题返回 null（规则与后端校验一一对应） */
export function toolGroupsProblem(groups: AgentToolGroup[]): string | null {
  if (groups.length > MAX_GROUPS) {
    return `工具分组最多 ${MAX_GROUPS} 个，现在有 ${groups.length} 个`;
  }
  const names = new Set<string>();
  const owner = new Map<string, string>();
  for (const group of groups) {
    const name = (group.name ?? '').trim();
    if (!name) {
      return '有个分组还没有名字';
    }
    if (!NAME_RE.test(name)) {
      return `分组名「${name}」非法：小写字母开头，仅含小写字母/数字/下划线/短横线，2–64 位`;
    }
    if (name === RESERVED) {
      return `${RESERVED} 是框架保留组名，请换一个`;
    }
    if (names.has(name)) {
      return `分组名重复：${name}`;
    }
    names.add(name);
    const tools = group.tools ?? [];
    if (tools.length === 0) {
      return `分组 ${name} 里至少要放一个工具（空组对模型没有意义）`;
    }
    if (tools.length > MAX_TOOLS) {
      return `分组 ${name} 最多放 ${MAX_TOOLS} 个工具`;
    }
    for (const tool of tools) {
      if (UNGROUPABLE.includes(tool)) {
        return `工具 ${tool} 必须始终对模型可见，不能放进分组`;
      }
      const previous = owner.get(tool);
      if (previous === name) {
        return `分组 ${name} 里工具重复：${tool}`;
      }
      if (previous) {
        return `工具 ${tool} 已经在分组 ${previous} 里，一个工具只能属于一个组`;
      }
      owner.set(tool, name);
    }
  }
  return null;
}

/** 目录里的一颗工具：整颗可拖，点一下弹出"放进哪个组" */
function CatalogChip({ entry, owner, groups, onAssign, onDragStart }: {
  entry: ToolCatalogEntry;
  owner?: string;
  groups: AgentToolGroup[];
  onAssign: (tools: string[], group: string) => void;
  onDragStart: (e: DragEvent<HTMLSpanElement>, tool: string, from?: string) => void;
}) {
  const chip = (
    <span
      draggable
      onDragStart={(e) => onDragStart(e, entry.name, owner)}
      title={entry.description || entry.name}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 4,
        padding: '3px 8px',
        borderRadius: 999,
        border: '1px solid rgba(26, 26, 29, 0.12)',
        background: owner ? 'rgba(26, 26, 29, 0.03)' : '#fff',
        color: owner ? 'rgba(26, 26, 29, 0.45)' : 'rgba(26, 26, 29, 0.88)',
        fontSize: 12,
        cursor: 'grab',
        userSelect: 'none',
        maxWidth: '100%',
      }}
    >
      <SparkDragDotLine style={{ fontSize: 12, opacity: 0.4, flexShrink: 0 }} />
      <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{entry.name}</span>
      {owner && (
        <Tag size="small" color="blue" style={{ margin: 0, flexShrink: 0 }}>{owner}</Tag>
      )}
    </span>
  );
  if (groups.length === 0) {
    return <span title="先新建一个分组，才能把工具放进去">{chip}</span>;
  }
  return (
    <Dropdown
      trigger={['click']}
      menu={{
        items: groups.map((g) => ({
          key: g.name,
          disabled: owner === g.name,
          label: owner === g.name ? `已在 ${g.name}` : owner ? `移到 ${g.name}` : `加入 ${g.name}`,
        })),
        onClick: ({ key }) => onAssign([entry.name], key),
      }}
    >
      {chip}
    </Dropdown>
  );
}

/** 组内加工具的输入框：目录里选，或直接敲名字回车（MCP 工具名只能这么加） */
function ToolAdder({ catalog, taken, onAdd }: {
  catalog: ToolCatalogEntry[];
  taken: string[];
  onAdd: (names: string[]) => void;
}) {
  const [value, setValue] = useState<string[]>([]);
  const options = catalog
    .filter((e) => !taken.includes(e.name))
    .map((e) => ({ value: e.name, label: e.name }));
  return (
    <Select
      mode="tags"
      value={value}
      options={options}
      showSearch
      optionFilterProp="label"
      tokenSeparators={[',', ' ']}
      placeholder="从目录选，或直接敲工具名回车（MCP 工具名由服务端自报）"
      style={{ width: '100%' }}
      onChange={(next) => {
        const names = (next as string[]).map((n) => n.trim()).filter(Boolean);
        if (names.length) {
          onAdd(names);
        }
        // 这里只当输入口用：加完就清空，成员一律以组卡片上的标签为准
        setValue([]);
      }}
    />
  );
}

export function ToolGroupsSection({ groups, catalog, catalogError, onChange }: {
  groups: AgentToolGroup[];
  catalog: ToolCatalogEntry[];
  /** 目录接口失败的原因（失败时仍可手填工具名，所以只是降级提示，不清空已有配置） */
  catalogError?: string | null;
  onChange: (next: AgentToolGroup[]) => void;
}) {
  const [keyword, setKeyword] = useState('');
  const [onlyFree, setOnlyFree] = useState(false);
  const [dragOver, setDragOver] = useState<string | null>(null);
  const [modal, setModal] = useState<{ mode: 'create' | 'edit'; index: number } | null>(null);
  const [modalForm] = Form.useForm();
  /** 拖拽载荷走 ref：同页内拖不需要跨窗口传数据，dataTransfer 只留个 text/plain 让浏览器认这是一次拖动 */
  const drag = useRef<{ tool: string; from?: string } | null>(null);

  /** 工具名 → 所在组（一个工具只属于一个组，后端同口径） */
  const ownerOf = useMemo(() => {
    const map = new Map<string, string>();
    groups.forEach((g) => (g.tools ?? []).forEach((t) => map.set(t, g.name)));
    return map;
  }, [groups]);

  const visibleCatalog = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    return catalog.filter((e) => {
      if (onlyFree && ownerOf.has(e.name)) {
        return false;
      }
      if (!kw) {
        return true;
      }
      return e.name.toLowerCase().includes(kw) || (e.description ?? '').toLowerCase().includes(kw);
    });
  }, [catalog, keyword, onlyFree, ownerOf]);

  const grouped = useMemo(
    () => SOURCE_ORDER
      .map((source) => ({ source, entries: visibleCatalog.filter((e) => e.source === source) }))
      .filter((g) => g.entries.length > 0),
    [visibleCatalog],
  );

  const onDragStart = (e: DragEvent<HTMLSpanElement>, tool: string, from?: string) => {
    drag.current = { tool, from };
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', tool);
  };

  /**
   * 入组（也是"移组"）：目标组外的同名成员先摘掉，保证一个工具只属于一个组 ——
   * 框架的工具→组反向索引本来就是一对一，界面上放两份只会让显示与实际生效的对不上。
   */
  const assign = (tools: string[], groupName: string) => {
    const names = tools.map((t) => t.trim()).filter(Boolean);
    if (names.length === 0) {
      return;
    }
    const blocked = names.filter((n) => UNGROUPABLE.includes(n));
    const illegal = names.filter((n) => !UNGROUPABLE.includes(n) && !TOOL_NAME_RE.test(n));
    const wanted = names.filter((n) => !UNGROUPABLE.includes(n) && TOOL_NAME_RE.test(n));
    if (blocked.length) {
      message.error(`${blocked.join('、')} 必须始终对模型可见，不能放进分组`);
    }
    if (illegal.length) {
      message.error(`工具名非法，已忽略：${illegal.join('、')}`);
    }
    if (wanted.length === 0) {
      return;
    }
    const set = new Set(wanted);
    onChange(groups.map((g) => {
      const kept = (g.tools ?? []).filter((t) => !set.has(t));
      if (g.name !== groupName) {
        return { ...g, tools: kept };
      }
      if (kept.length + set.size > MAX_TOOLS) {
        message.error(`分组 ${groupName} 最多放 ${MAX_TOOLS} 个工具`);
        return g;
      }
      return { ...g, tools: [...kept, ...wanted.filter((n) => !kept.includes(n))] };
    }));
  };

  const unassign = (tool: string) => {
    onChange(groups.map((g) => ({ ...g, tools: (g.tools ?? []).filter((t) => t !== tool) })));
  };

  const onDropToGroup = (e: DragEvent<HTMLDivElement>, groupName: string) => {
    e.preventDefault();
    setDragOver(null);
    const payload = drag.current;
    drag.current = null;
    if (payload) {
      assign([payload.tool], groupName);
    }
  };

  /** 拖回目录 = 取消入组（只对已在组里的工具有意义） */
  const onDropToCatalog = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragOver(null);
    const payload = drag.current;
    drag.current = null;
    if (payload?.from) {
      unassign(payload.tool);
    }
  };

  const openCreate = () => {
    modalForm.resetFields();
    setModal({ mode: 'create', index: -1 });
  };

  const openEdit = (index: number) => {
    modalForm.resetFields();
    modalForm.setFieldsValue({
      name: groups[index].name,
      description: groups[index].description,
    });
    setModal({ mode: 'edit', index });
  };

  const onModalOk = async () => {
    if (!modal) {
      return;
    }
    let row: { name?: string; description?: string };
    try {
      row = await modalForm.validateFields() as { name?: string; description?: string };
    } catch {
      // 规则没过的红字已经标在字段上，弹窗不关
      return;
    }
    const name = (row.name ?? '').trim();
    const description = (row.description ?? '').trim();
    if (modal.mode === 'create') {
      // 空组先建着：工具随后拖进来；保存时若仍是空的，toolGroupsProblem 会拦住并说明原因
      onChange([...groups, { name, description: description || undefined, tools: [] }]);
    } else {
      onChange(groups.map((g, i) => (i === modal.index
        ? { ...g, name, description: description || undefined }
        : g)));
    }
    setModal(null);
  };

  const editing = modal?.mode === 'edit' ? groups[modal.index] : undefined;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <div style={{ fontSize: 13, color: 'rgba(26, 26, 29, 0.55)' }}>
          {groups.length} 个分组 · 已入组 {ownerOf.size} 个工具
        </div>
        {/* 规则说明收进 Tooltip：常驻一大块 Alert 会把真正的工作区（目录 + 组卡）挤下去，
            而这番话只在第一次配置时看一次就够 */}
        <Tooltip
          title={
            <div style={{ fontSize: 12, lineHeight: 1.7 }}>
              <div style={{ fontWeight: 600 }}>入组的工具默认不出现在模型的工具表里</div>
              模型看不到它们，但能看到组名与组说明，需要时自己调 reset_equipped_tools 把整组装上（换组时上一次装的会卸下）。
              没有入组的工具始终可见 —— 所以组说明要写清「这组是干什么的、什么时候该装」。
            </div>
          }
        >
          <span style={{ display: 'inline-flex', cursor: 'help', color: 'rgba(26, 26, 29, 0.4)' }}>
            <SparkInfoLine size={14} />
          </span>
        </Tooltip>
        <div style={{ flex: 1 }} />
        <Button
          type="primary"
          icon={<SparkPlusLine />}
          disabled={groups.length >= MAX_GROUPS}
          onClick={openCreate}
        >
          新建分组
        </Button>
      </div>
      {groups.length >= MAX_GROUPS && (
        <div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.45)', marginTop: -8 }}>
          已到上限 {MAX_GROUPS} 个分组，删掉不用的才能再建。
        </div>
      )}

      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'flex-start' }}>
        {/* 左：工具目录（拖出 / 点击选组；也是"拖回来取消入组"的落点） */}
        <div
          className="glass-card"
          onDragOver={(e) => { e.preventDefault(); setDragOver('__catalog__'); }}
          onDragLeave={() => setDragOver((s) => (s === '__catalog__' ? null : s))}
          onDrop={onDropToCatalog}
          style={{
            flex: '1 1 300px',
            minWidth: 260,
            padding: 16,
            borderColor: dragOver === '__catalog__' ? '#5bb98b' : undefined,
            borderStyle: dragOver === '__catalog__' ? 'dashed' : undefined,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <div style={{ fontSize: 14, fontWeight: 600 }}>工具目录</div>
            <div style={{ flex: 1 }} />
            <Radio.Group
              size="small"
              value={onlyFree ? 'free' : 'all'}
              onChange={(e) => setOnlyFree(e.target.value === 'free')}
              options={[{ label: '全部', value: 'all' }, { label: '未入组', value: 'free' }]}
              optionType="button"
            />
          </div>
          <Input
            allowClear
            value={keyword}
            placeholder="搜索工具名或说明"
            onChange={(e) => setKeyword(e.target.value)}
            style={{ marginBottom: 10 }}
          />
          {catalogError && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 10 }}
              message="工具目录没拉到"
              description={`${catalogError}　已有的分组配置不受影响；要新增成员可以在组卡片下方的输入框里直接敲工具名。`}
            />
          )}
          {catalog.length === 0 && !catalogError && (
            <Empty description="目录为空" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxHeight: 520, overflowY: 'auto' }}>
            {grouped.map((bucket) => (
              <div key={bucket.source}>
                <div style={{ fontSize: 11, color: 'rgba(26, 26, 29, 0.45)', marginBottom: 6 }}>
                  {SOURCE_LABEL[bucket.source]}（{bucket.entries.length}）
                </div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                  {bucket.entries.map((entry) => (
                    <CatalogChip
                      key={entry.name}
                      entry={entry}
                      owner={ownerOf.get(entry.name)}
                      groups={groups}
                      onAssign={assign}
                      onDragStart={onDragStart}
                    />
                  ))}
                </div>
              </div>
            ))}
            {catalog.length > 0 && visibleCatalog.length === 0 && (
              <div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.45)' }}>没有符合条件的工具</div>
            )}
          </div>
          <div style={{ fontSize: 11, color: 'rgba(26, 26, 29, 0.4)', marginTop: 10 }}>
            拖到右边的分组卡片即可入组；把组里的标签拖回这里 = 取消入组。目录是「这个版本可能注册出来的工具」，
            这个 Agent 没开的功能（如生图、OSS）列在这里也不影响 —— 没注册的名字框架会忽略。
          </div>
        </div>

        {/* 右：分组卡片（拖入 / 标签删除 / 输入框加成员） */}
        <div style={{ flex: '2 1 420px', minWidth: 280, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {groups.length === 0 ? (
            <div className="glass-card" style={{ padding: 24 }}>
              <Empty description="还没有分组：新建一个，再把工具拖进去">
                <div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.55)' }}>
                  一个分组都不建 = 这个 Agent 的所有工具始终对模型可见（现状）。
                </div>
              </Empty>
            </div>
          ) : groups.map((group, index) => (
            <div className="glass-card" key={`${group.name}-${index}`} style={{ padding: 16 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <SparkToolboxLine style={{ fontSize: 16, opacity: 0.6 }} />
                <div style={{ fontSize: 14, fontWeight: 600, fontFamily: 'monospace' }}>{group.name || '（未命名）'}</div>
                <Tag size="small" color={group.tools?.length ? 'purple' : 'warning'} style={{ margin: 0 }}>
                  {(group.tools ?? []).length} 个工具
                </Tag>
                <div style={{ flex: 1 }} />
                <Button type="text" size="small" icon={<SparkEditLine />} onClick={() => openEdit(index)}>
                  编辑
                </Button>
                <Popconfirm
                  title="删除这个分组？"
                  description="组里的工具会回到「始终可见」，保存后生效"
                  okText="删除"
                  cancelText="取消"
                  onConfirm={() => onChange(groups.filter((_, i) => i !== index))}
                >
                  <Button type="text" size="small" icon={<SparkDeleteLine />} />
                </Popconfirm>
              </div>
              <div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.55)', margin: '6px 0 10px' }}>
                {group.description || '（没写说明：模型只能靠组名猜这组该不该装）'}
              </div>
              {(group.tools ?? []).length === 0 && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginBottom: 10 }}
                  message="这个组还是空的"
                  description="空组对模型没有意义，保存前请至少放一个工具进去。"
                />
              )}
              <div
                onDragOver={(e) => { e.preventDefault(); setDragOver(group.name); }}
                onDragLeave={() => setDragOver((s) => (s === group.name ? null : s))}
                onDrop={(e) => onDropToGroup(e, group.name)}
                style={{
                  minHeight: 56,
                  padding: 8,
                  borderRadius: 8,
                  border: dragOver === group.name
                    ? '1px dashed #5bb98b'
                    : '1px dashed rgba(26, 26, 29, 0.14)',
                  background: dragOver === group.name ? 'rgba(91, 185, 139, 0.06)' : undefined,
                  display: 'flex',
                  flexWrap: 'wrap',
                  gap: 6,
                  alignItems: 'center',
                }}
              >
                {(group.tools ?? []).length === 0 ? (
                  <span style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.35)', padding: 4 }}>
                    把左边的工具拖进来
                  </span>
                ) : (group.tools ?? []).map((tool) => (
                  <span
                    key={tool}
                    draggable
                    onDragStart={(e) => onDragStart(e, tool, group.name)}
                    style={{ cursor: 'grab', display: 'inline-flex' }}
                  >
                    <Tag
                      closable
                      color="teal"
                      style={{ margin: 0 }}
                      onClose={(e) => { e.preventDefault(); unassign(tool); }}
                    >
                      {tool}
                    </Tag>
                  </span>
                ))}
              </div>
              <div style={{ marginTop: 10 }}>
                <ToolAdder
                  catalog={catalog}
                  taken={group.tools ?? []}
                  onAdd={(names) => assign(names, group.name)}
                />
              </div>
            </div>
          ))}
        </div>
      </div>

      <ResponsiveModal
        title={modal?.mode === 'edit' ? '编辑分组' : '新建分组'}
        open={modal !== null}
        onOk={onModalOk}
        onCancel={() => setModal(null)}
        okText="确定"
        cancelText="取消"
        destroyOnClose
        width={520}
      >
        <Form form={modalForm} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item
            name="name"
            label="组名"
            tooltip="模型装备时按这个名字点名，建好后再改也可以，但改了要告诉用户旧的组名不再生效"
            rules={[
              { required: true, message: '组名必填' },
              {
                validator: (_, value: string) => {
                  const name = (value ?? '').trim();
                  if (!name) {
                    return Promise.resolve();
                  }
                  if (!NAME_RE.test(name)) {
                    return Promise.reject(new Error('小写字母开头，仅含小写字母/数字/下划线/短横线，2–64 位'));
                  }
                  if (name === RESERVED) {
                    return Promise.reject(new Error(`${RESERVED} 是框架保留组名，请换一个`));
                  }
                  const dup = groups.some((g, i) => g.name === name && i !== modal?.index);
                  return dup
                    ? Promise.reject(new Error(`已经有叫 ${name} 的分组了`))
                    : Promise.resolve();
                },
              },
            ]}
          >
            <Input placeholder="如 db-ops、media_gen" maxLength={64} />
          </Form.Item>
          <Form.Item
            name="description"
            label="组说明"
            tooltip="这段文字会进 meta tool 的说明里，是模型判断「要不要装这组」的唯一依据"
            rules={[{ max: DESC_MAX, message: `最多 ${DESC_MAX} 字符` }]}
          >
            <Input.TextArea
              rows={3}
              showCount
              maxLength={DESC_MAX}
              placeholder="如：数据库运维工具（查询、迁移、备份）。用户提到数据库、SQL、迁移或备份时装备。"
            />
          </Form.Item>
          {editing && (
            <div style={{ fontSize: 12, color: 'rgba(26, 26, 29, 0.55)' }}>
              当前有 {(editing.tools ?? []).length} 个工具；成员在卡片上增删，这里只改名字与说明。
            </div>
          )}
        </Form>
      </ResponsiveModal>
    </div>
  );
}
