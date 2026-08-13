-- ============================================================
-- Teapot AI 系统配置表（SPEC §16.5.1：含 AES-GCM 加密凭证）
-- 库：teapot_ai；幂等：IF NOT EXISTS
-- 注：敏感项 config_value 存密文 v<keyVer>:<base64(iv+ciphertext+tag)>；
--     主密钥 TEAPOT_SECRET_KEY 仅服务器环境变量，绝不入库
-- ============================================================
USE teapot_ai;

CREATE TABLE IF NOT EXISTS t_sys_config (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  config_key   VARCHAR(64)  NOT NULL COMMENT 'agentrun.api_key / agentrun.account_id / agentrun.region / …',
  config_value TEXT         NOT NULL COMMENT '敏感项存 AES-GCM 密文 v<keyVer>:<base64(iv+ciphertext+tag)>',
  key_version  TINYINT      NOT NULL DEFAULT 1 COMMENT '主密钥版本，轮换用',
  encrypted    TINYINT      NOT NULL DEFAULT 0 COMMENT '1密文 0明文',
  updated_by   VARCHAR(64)  NOT NULL,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置（含加密凭证）';
