import { useEffect, useState } from 'react';
import { useLocation, useSearchParams } from 'react-router-dom';
import { Select } from '@agentscope-ai/design';
import { agentList } from '../api/agent';
import type { Agent } from '../types';

const MOBILE_BP = 768;
const LS_KEY = 'teapot:lastAgent';

/**
 * 顶栏 Agent 选择器：仅在 /chat 路由显示。
 * 选中值与对话页共用 URL 参数 ?agent=，切换后对话页按 key 重建。
 * - 下拉选项显示 Agent 头像
 * - 选择后存入 localStorage，下次优先选中
 */
export default function AgentSelector() {
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const [agents, setAgents] = useState<Agent[]>([]);
  const [isMobile, setIsMobile] = useState(window.innerWidth < MOBILE_BP);

  useEffect(() => {
    const onResize = () => setIsMobile(window.innerWidth < MOBILE_BP);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  useEffect(() => {
    (async () => {
      try {
        const page = await agentList({ page: 1, size: 100 });
        setAgents(page.list || []);
      } catch {
        setAgents([]);
      }
    })();
  }, []);

  if (!location.pathname.startsWith('/chat') || agents.length === 0) {
    return null;
  }

  // 优先 URL 参数 > localStorage 上次选择 > 列表第一个
  const lastAgent = localStorage.getItem(LS_KEY) || '';
  const current =
    searchParams.get('agent') ||
    (lastAgent && agents.some((a) => a.agentKey === lastAgent) ? lastAgent : '') ||
    agents[0].agentKey;
  const activeAgent = agents.find((a) => a.agentKey === current);

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
      {activeAgent?.avatar ? (
        <img
          src={activeAgent.avatar}
          alt={activeAgent.name}
          style={{ width: 22, height: 22, borderRadius: 999, objectFit: 'cover', flexShrink: 0 }}
        />
      ) : (
        <span
          style={{
            width: 22,
            height: 22,
            borderRadius: 999,
            background: 'linear-gradient(135deg, #5b5b63, #3a3a40)',
            color: '#fff',
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 11,
            fontWeight: 700,
            flexShrink: 0,
          }}
        >
          {activeAgent?.name?.charAt(0).toUpperCase() || '?'}
        </span>
      )}
      <Select
        className="teapot-agent-select"
        value={current}
        options={agents.map((a) => ({ label: a.name, value: a.agentKey }))}
        optionRender={(option) => {
          const agent = agents.find((a) => a.agentKey === option.value);
          return (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              {agent?.avatar ? (
                <img
                  src={agent.avatar}
                  alt={agent.name}
                  style={{ width: 22, height: 22, borderRadius: 999, objectFit: 'cover', flexShrink: 0 }}
                />
              ) : (
                <span
                  style={{
                    width: 22,
                    height: 22,
                    borderRadius: 999,
                    background: 'linear-gradient(135deg, #5b5b63, #3a3a40)',
                    color: '#fff',
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontSize: 11,
                    fontWeight: 700,
                    flexShrink: 0,
                  }}
                >
                  {agent?.name?.charAt(0).toUpperCase() || '?'}
                </span>
              )}
              <span>{option.label}</span>
            </div>
          );
        }}
        onChange={(v) => {
          localStorage.setItem(LS_KEY, v);
          setSearchParams({ agent: v });
        }}
        style={{ width: isMobile ? 120 : 200 }}
        size={isMobile ? 'small' : 'middle'}
      />
    </div>
  );
}
