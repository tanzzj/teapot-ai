-- ============================================================
-- Teapot AI 模型多模态能力位（SPEC §19）
-- 库：teapot_ai；幂等：已存在则跳过
-- capabilities 逗号分隔：image,audio,video；NULL = 纯文本（一期界面仅开放 image）
-- ============================================================
USE teapot_ai;

SET @col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'teapot_ai' AND TABLE_NAME = 't_model_entry' AND COLUMN_NAME = 'capabilities'
);
SET @sql := IF(@col = 0,
  'ALTER TABLE t_model_entry ADD COLUMN capabilities VARCHAR(64) NULL COMMENT ''能力位逗号分隔：image,audio,video；NULL=纯文本'' AFTER base_url',
  'SELECT ''capabilities column exists, skip'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
