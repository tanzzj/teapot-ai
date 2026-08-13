-- ============================================================
-- Teapot AI Agent 扩展功能字段（SPEC §16.6：沙箱等按 Agent 配置）
-- 库：teapot_ai；幂等：仅当列不存在时添加
-- ============================================================
USE teapot_ai;

SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'teapot_ai' AND TABLE_NAME = 't_agent' AND COLUMN_NAME = 'feature'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE t_agent ADD COLUMN feature JSON NULL COMMENT ''扩展功能配置(JSON)：sandbox 等，SPEC §16.6'' AFTER compaction_keep',
  'SELECT ''t_agent.feature already exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
