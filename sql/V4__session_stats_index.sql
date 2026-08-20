-- V4：会话按日统计（Profile 热力图）覆盖索引
-- 聚合查询 WHERE agent_key = ? AND created_at >= ? GROUP BY DATE(created_at)
CREATE INDEX idx_chat_session_agent_date ON t_chat_session (agent_key, created_at);
