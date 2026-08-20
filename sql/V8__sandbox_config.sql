-- ============================================================
-- Teapot AI 沙箱连接多记录表（SPEC §22.2：多条沙箱连接记录，Agent 按 feature 引用）
-- 库：teapot_ai；幂等：IF NOT EXISTS
-- 注：e2b_api_key / ar_api_key / ar_account_id 存 AES-GCM 密文；
--     link_type=e2b 消费 e2b_* 列；link_type=agentrun 消费 ar_* 列
-- ============================================================
USE teapot_ai;

CREATE TABLE IF NOT EXISTS t_sandbox_config (
  id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name                 VARCHAR(64)  NOT NULL COMMENT '记录名（唯一标识，Agent feature.sandbox.sandboxRecord 引用）',
  link_type            VARCHAR(16)  NOT NULL COMMENT 'e2b | agentrun',
  e2b_api_key          TEXT                  DEFAULT NULL COMMENT 'E2B API Key，AES-GCM 密文',
  e2b_api_base_url     VARCHAR(255)          DEFAULT NULL,
  e2b_domain           VARCHAR(255)          DEFAULT NULL,
  e2b_default_template VARCHAR(128)          DEFAULT NULL,
  ar_api_key           TEXT                  DEFAULT NULL COMMENT 'AgentRun API Key，AES-GCM 密文',
  ar_account_id        TEXT                  DEFAULT NULL COMMENT '阿里云账号 ID，AES-GCM 密文',
  ar_region            VARCHAR(64)           DEFAULT NULL,
  ar_default_template  VARCHAR(128)          DEFAULT NULL,
  ar_mcp_server_url    VARCHAR(512)          DEFAULT NULL,
  remark               VARCHAR(255)          DEFAULT NULL,
  updated_by           VARCHAR(64)  NOT NULL,
  created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sandbox_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='沙箱连接记录（多条，Agent feature 引用）';
