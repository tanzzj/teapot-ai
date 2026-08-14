import { useEffect, useState } from 'react';
import { useLocation, useSearchParams } from 'react-router-dom';
import { Select } from '@agentscope-ai/design';
import { SparkAgentLine } from '@agentscope-ai/icons';
import { agentList } from '../api/agent';
import type { Agent } from '../types';

const MOBILE_BP = 768;

/**
 * 顶栏 Agent 选择器：仅在 /chat 路由显示。
 * 选中值与对话页共用 URL 参数 ?agent=，切换后对话页按 key 重建。
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

  const current = searchParams.get('agent') || agents[0].agentKey;

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
      <SparkAgentLine size={16} style={{ color: '#666' }} />
      <Select
        className="teapot-agent-select"
        value={current}
        options={agents.map((a) => ({ label: a.name, value: a.agentKey }))}
        onChange={(v) => setSearchParams({ agent: v })}
        style={{ width: isMobile ? 120 : 200 }}
        size={isMobile ? 'small' : 'middle'}
      />
    </div>
  );
}
