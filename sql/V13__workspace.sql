-- ============================================================
-- Teapot AI 业务空间（SPEC §28）：teapot_workspace + 成员表 + t_agent.workspace_id
-- 库：teapot_ai；手动执行（无 Flyway），先例 run-sql.sh / tmp-deploy-v23.sh
-- 幂等：建表 IF NOT EXISTS；加列 information_schema 守卫（同 V4）
-- ============================================================
USE teapot_ai;

-- 业务空间主表（列名按 SPEC §28.2：creator/gmt_create/gmt_modified 用户指定，区别于 t_* 的 *_by/*_at）
CREATE TABLE IF NOT EXISTS teapot_workspace (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_name VARCHAR(64)  NOT NULL COMMENT '空间显示名',
  workspace_code VARCHAR(128) NOT NULL COMMENT '空间业务编码，全局唯一；个人空间=personal:{userId}',
  workspace_type VARCHAR(16)  NOT NULL DEFAULT 'team' COMMENT 'personal=个人空间 team=团队空间',
  description    VARCHAR(512) NULL,
  feature        JSON         NULL COMMENT '空间级扩展配置(JSON)，预留（仿 t_agent.feature）',
  status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  creator        VARCHAR(64)  NOT NULL COMMENT '创建人 userId（个人空间即归属人）',
  gmt_create     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_workspace_code (workspace_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务空间';

-- 空间成员（加成员即授权，SPEC §28.3；creator 自身作为 owner 一条）
CREATE TABLE IF NOT EXISTS teapot_workspace_member (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT UNSIGNED NOT NULL,
  user_id      VARCHAR(64)  NOT NULL COMMENT '成员 userId（t_user.user_id）',
  member_role  VARCHAR(16)  NOT NULL DEFAULT 'member' COMMENT 'owner=创建人/空间主 member=普通成员',
  creator      VARCHAR(64)  NOT NULL COMMENT '谁加的这条成员',
  gmt_create   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ws_user (workspace_id, user_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务空间成员';

-- t_agent 增加可空归属空间列（NULL = 未归属，存量/未迁移；SPEC §28.2 需求 6）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'teapot_ai' AND TABLE_NAME = 't_agent' AND COLUMN_NAME = 'workspace_id'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE t_agent ADD COLUMN workspace_id BIGINT UNSIGNED NULL COMMENT ''归属业务空间 teapot_workspace.id；NULL=未归属'' AFTER feature',
  'SELECT ''t_agent.workspace_id already exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 空间作用域查询走 (workspace_id, status)；information_schema 守卫幂等
SET @idx_exists = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = 'teapot_ai' AND TABLE_NAME = 't_agent' AND INDEX_NAME = 'idx_agent_workspace'
);
SET @ddl = IF(@idx_exists = 0,
  'ALTER TABLE t_agent ADD INDEX idx_agent_workspace (workspace_id, status)',
  'SELECT ''t_agent.idx_agent_workspace already exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
