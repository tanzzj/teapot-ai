-- ============================================================
-- Teapot AI Channel 连接器（SPEC §24：Agent 对外消息通道，初版钉钉）
-- 库：teapot_ai；幂等：IF NOT EXISTS
-- 注：app_secret 存 AES-GCM 密文（同 §22.2 加密方案）；
--     t_channel_session 为 channel 会话索引（消息体仍以 agentscope_sessions 为事实源）
-- ============================================================
USE teapot_ai;

CREATE TABLE IF NOT EXISTS t_channel_config (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name         VARCHAR(64)  NOT NULL COMMENT '记录名（唯一标识，Agent feature.channel.channelRecord 引用）',
  channel_type VARCHAR(16)  NOT NULL COMMENT 'dingtalk（v1 唯一值，枚举留扩展）',
  app_key      VARCHAR(128)          DEFAULT NULL COMMENT '钉钉应用 ClientID（明文）',
  app_secret   TEXT                  DEFAULT NULL COMMENT '钉钉应用 ClientSecret，AES-GCM 密文',
  robot_code   VARCHAR(128)          DEFAULT NULL COMMENT '机器人 robotCode，缺省同 appKey',
  remark       VARCHAR(255)          DEFAULT NULL,
  updated_by   VARCHAR(64)  NOT NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_channel_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Channel 连接器记录（多条，Agent feature 引用）';

CREATE TABLE IF NOT EXISTS t_channel_session (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  agent_key      VARCHAR(64)  NOT NULL COMMENT '所属 Agent',
  user_id        VARCHAR(128) NOT NULL COMMENT 'gateway 身份（钉钉 peer：staffId/conversationId）',
  session_id     VARCHAR(128) NOT NULL COMMENT 'gateway 生成的会话 id（gw-…）',
  channel_type   VARCHAR(16)  NOT NULL COMMENT 'dingtalk（后续渠道枚举）',
  title          VARCHAR(64)           DEFAULT NULL COMMENT '首条用户消息截断 50 字',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_active_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_channel_session (user_id, session_id),
  KEY idx_channel_session_agent (agent_key, last_active_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Channel 会话索引（admin 全量会话历史视图，SPEC §24.9）';
