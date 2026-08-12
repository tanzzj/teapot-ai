-- ============================================================
-- Teapot AI 业务库初始化 DDL（SPEC §10.1 / §10.3）
-- 库：teapot_ai（utf8mb4 / utf8mb4_unicode_ci）；表前缀 t_
-- 幂等：全部 IF NOT EXISTS
-- ============================================================
USE teapot_ai;

-- 用户表（兼容老 t_portal_user 语义，字段扩展）
CREATE TABLE IF NOT EXISTS t_user (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id      VARCHAR(64)  NOT NULL COMMENT '业务用户ID',
  username     VARCHAR(64)  NOT NULL COMMENT '登录名',
  password     VARCHAR(100) NOT NULL COMMENT 'BCrypt hash',
  real_name    VARCHAR(64)  NULL,
  mobile       VARCHAR(20)  NULL,
  email        VARCHAR(128) NULL,
  roles        VARCHAR(255) NOT NULL DEFAULT 'viewer' COMMENT '逗号分隔 roleId: admin,developer,viewer',
  status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  UNIQUE KEY uk_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台用户';

-- Agent 定义表
CREATE TABLE IF NOT EXISTS t_agent (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  agent_key           VARCHAR(32)  NOT NULL COMMENT '全局唯一，AG-UI 路由键',
  name                VARCHAR(64)  NOT NULL,
  description         VARCHAR(512) NULL,
  sys_prompt          TEXT         NOT NULL,
  model_id            VARCHAR(64)  NOT NULL DEFAULT 'dashscope:qwen-plus' COMMENT 'provider:model',
  compaction_trigger  INT          NOT NULL DEFAULT 30,
  compaction_keep     INT          NOT NULL DEFAULT 10,
  status              TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_by          VARCHAR(64)  NOT NULL,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_key (agent_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent定义';

-- Agent-Skill 绑定表
CREATE TABLE IF NOT EXISTS t_agent_skill (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  agent_key   VARCHAR(32)  NOT NULL,
  skill_name  VARCHAR(255) NOT NULL,
  created_by  VARCHAR(64)  NOT NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_skill (agent_key, skill_name),
  KEY idx_skill_name (skill_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent-Skill绑定';

-- 会话索引表（消息体在 agentscope 库）
CREATE TABLE IF NOT EXISTS t_chat_session (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     VARCHAR(64)  NOT NULL,
  agent_key   VARCHAR(32)  NOT NULL,
  session_id  VARCHAR(128) NOT NULL COMMENT 'AG-UI threadId',
  title       VARCHAR(128) NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_session (user_id, session_id),
  KEY idx_user_agent (user_id, agent_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话索引';

-- 审计表（一期建表，埋点写入随 M3 落地，SPEC §10.3）
CREATE TABLE IF NOT EXISTS t_audit_log (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     VARCHAR(64)  NOT NULL COMMENT '操作人',
  action      VARCHAR(64)  NOT NULL COMMENT '如 agent.create / skill.save / user.reset_password',
  target      VARCHAR(255) NULL COMMENT '操作对象标识',
  detail      TEXT         NULL COMMENT 'JSON 摘要（脱敏）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, created_at),
  KEY idx_action_time (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作审计';
