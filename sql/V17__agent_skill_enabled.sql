-- ============================================================
-- Agent ↔ Skill 绑定加「停用」层（SPEC §33）
-- 库：teapot_ai；表：t_agent_skill；手动执行（无 Flyway）
--
-- 语义：绑定关系与是否生效是两件事。
--   绑定（有这一行）= 这个 skill 出现在该 Agent 的可装配集合里；
--   enabled=0      = 行还在（配置关系保留），但装配时不进 SkillFilter，
--                    模型看不到这个 skill —— 用于「暂时停用但不想丢掉绑定」。
--   解绑 = 删行，与本列无关。
--
-- 默认 1：存量行全部是「绑定且生效」，加列后行为与加列前完全一致，
-- 不存在"上线即静默停用某个 Agent 的 skill"。
--
-- 幂等：列已存在时跳过（MySQL 8.4 无 ADD COLUMN IF NOT EXISTS）
-- ============================================================
USE teapot_ai;

SET @col_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = 'teapot_ai'
    AND TABLE_NAME = 't_agent_skill'
    AND COLUMN_NAME = 'enabled'
);

SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE t_agent_skill ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''绑定是否生效：0=已绑定但停用，装配时不进 SkillFilter'' AFTER skill_name',
  'SELECT ''column t_agent_skill.enabled already exists, skipped'' AS msg'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT CONCAT('V17_OK enabled_rows_1=',
              (SELECT COUNT(*) FROM t_agent_skill WHERE enabled = 1),
              ' enabled_rows_0=',
              (SELECT COUNT(*) FROM t_agent_skill WHERE enabled = 0)) AS result;
