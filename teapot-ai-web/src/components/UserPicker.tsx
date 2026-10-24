import { useCallback, useRef, useState } from 'react';
import { Select } from '@agentscope-ai/design';
import { userPage } from '../api/auth';
import type { TeapotUser } from '../types';

/**
 * 用户查询选择器（SPEC §28.6，「拉起用户查询组件」加成员）：
 * 输入关键字 → /api/user/list?keyword= 远程搜，支持多选，值为 userId 列表。
 */
export default function UserPicker(props: {
  value?: string[];
  onChange?: (userIds: string[]) => void;
  placeholder?: string;
}) {
  const { value, onChange, placeholder = '按用户名/姓名搜索添加成员' } = props;
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
      options={options.map((u) => ({
        label: u.realName ? `${u.realName}（${u.username}）` : u.username,
        value: u.userId,
      }))}
      style={{ width: '100%' }}
    />
  );
}
