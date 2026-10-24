-- ============================================================
-- Teapot AI 模型入口 × 业务空间 授权（SPEC §28.11）
-- 库：teapot_ai；手动执行（无 Flyway），依赖 V3 建 t_model_entry、V13 建 teapot_workspace
-- 语义：deny-by-default —— 模型必须显式授权到某空间桶才能被该桶选用；
--       workspace_id = 0 为「全部空间（含个人空间）」桶，个人空间不逐个建授权。
-- 幂等：建表 IF NOT EXISTS；补授权 INSERT IGNORE + uk_ws_model 去重
-- ============================================================
USE teapot_ai;

CREATE TABLE IF NOT EXISTS teapot_workspace_model (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id   BIGINT UNSIGNED NOT NULL COMMENT '授权空间 id；0=全部空间（含个人空间）',
  model_entry_id BIGINT UNSIGNED NOT NULL COMMENT 't_model_entry.id',
  creator        VARCHAR(64)  NOT NULL COMMENT '谁加的这条授权',
  gmt_create     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ws_model (workspace_id, model_entry_id),
  KEY idx_model (model_entry_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型入口空间授权';

-- 存量补授权：把当前全部模型入口开放到 0（全部空间），保持去空间化前的既有行为（SPEC §28.11）
INSERT IGNORE INTO teapot_workspace_model (workspace_id, model_entry_id, creator)
SELECT 0, m.id, COALESCE(m.created_by, 'system') FROM t_model_entry m;
