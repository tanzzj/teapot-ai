import { useCallback, useMemo, useRef, useState } from 'react';
import { Select } from '@agentscope-ai/design';
import { userPage } from '../api/auth';
import type { TeapotUser } from '../types';

const toLabel = (u: TeapotUser) => (u.realName ? `${u.realName}（${u.username}）` : u.username);

/**
 * 用户查询选择器（SPEC §28.6，「拉起用户查询组件」加成员）：
 * 输入关键字 → /api/user/list?keyword= 远程搜，支持多选，值为 userId 列表。
 */
export default function UserPicker(props: {
  value?: string[];
  onChange?: (userIds: string[]) => void;
  placeholder?: string;
  /** 已选值的展示项（编辑空间时回填现有成员，否则多选框只能显示裸 userId，SPEC §28.12） */
  initialOptions?: { label: string; value: string }[];
}) {
  const { value, onChange, placeholder = '按用户名/姓名搜索添加成员', initialOptions } = props;
  const [options, setOptions] = useState<TeapotUser[]>([]);
  const [fetching, setFetching] = useState(false);
  const timer = useRef<number | undefined>(undefined);

  const search = useCallback(async (keyword: string) => {
    setFetching(true);
    try {
      const page = await userPage({ page: 1, size: 20, keyword: keyword || undefined });
      setOptions(page.list || []);
    } finally {
      setFetching(false);
    }
  }, []);

  const onSearch = (kw: string) => {
    if (timer.current) {
      window.clearTimeout(timer.current);
    }
    timer.current = window.setTimeout(() => search(kw), 300);
  };

  const selectOptions = useMemo(() => {
    const merged = new Map((initialOptions || []).map((o) => [o.value, o]));
    for (const u of options) {
      merged.set(u.userId, { label: toLabel(u), value: u.userId });
    }
    return [...merged.values()];
  }, [initialOptions, options]);

  return (
    <Select
      mode="multiple"
      showSearch
      filterOption={false}
      allowClear
      placeholder={placeholder}
      value={value}
      onSearch={onSearch}
      onFocus={() => search('')}
      loading={fetching}
      onChange={(v) => onChange?.((v as string[]) || [])}
      options={selectOptions}
      style={{ width: '100%' }}
    />
  );
}
