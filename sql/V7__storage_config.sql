-- ============================================================
-- Teapot AI OSS 存储多记录表（SPEC §20.12：多条 OSS 连接记录，激活其一）
-- 库：teapot_ai；幂等：IF NOT EXISTS
-- 注：access_key_id / access_key_secret 存 AES-GCM 密文 v<keyVer>:<base64(iv+ciphertext+tag)>；
--     激活记录名存 t_sys_config.storage.image.active；策略存 storage.image.strategy
-- ============================================================
USE teapot_ai;

CREATE TABLE IF NOT EXISTS t_storage_config (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name              VARCHAR(64)  NOT NULL COMMENT '记录名（唯一标识，管理台展示用）',
  access_key_id     TEXT         NOT NULL COMMENT 'AES-GCM 密文',
  access_key_secret TEXT         NOT NULL COMMENT 'AES-GCM 密文',
  region            VARCHAR(64)  NOT NULL COMMENT '如 cn-beijing',
  bucket            VARCHAR(128) NOT NULL,
  endpoint          VARCHAR(255)          DEFAULT NULL COMMENT '与 custom_domain 二选一',
  custom_domain     VARCHAR(255)          DEFAULT NULL COMMENT '含 https://；内地新 bucket 合规（§20.8）',
  key_prefix        VARCHAR(255)          DEFAULT NULL COMMENT '对象 key 前缀',
  remark            VARCHAR(255)          DEFAULT NULL,
  updated_by        VARCHAR(64)  NOT NULL,
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_storage_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OSS 存储连接记录（多条，激活其一）';
