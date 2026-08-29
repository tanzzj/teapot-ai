-- ============================================================
-- Teapot AI MCP Server 配置（参考 QwenPaw MCP 配置模型）
-- 库：teapot_ai；幂等：IF NOT EXISTS
-- 支持 stdio（本地进程）/ streamable_http / sse（远程服务）三种传输协议。
-- args / env / headers 以 JSON 字符串存储，Service 层序列化/反序列化。
-- ============================================================
USE teapot_ai;

CREATE TABLE IF NOT EXISTS t_mcp_config (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name        VARCHAR(64)  NOT NULL COMMENT 'MCP server 名称（唯一标识）',
  transport   VARCHAR(20)  NOT NULL COMMENT '传输协议：stdio / streamable_http / sse',
  command     VARCHAR(512)          DEFAULT NULL COMMENT 'stdio 启动命令',
  args        VARCHAR(1024)         DEFAULT NULL COMMENT 'stdio 命令参数（JSON 数组，如 ["--port","8080"]）',
  env         TEXT                  DEFAULT NULL COMMENT '环境变量（JSON 对象，如 {"KEY":"VALUE"}）',
  url         VARCHAR(512)          DEFAULT NULL COMMENT 'HTTP/SSE 远程 URL',
  headers     TEXT                  DEFAULT NULL COMMENT 'HTTP 请求头（JSON 对象）',
  enabled     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
  description VARCHAR(255)          DEFAULT NULL,
  remark      VARCHAR(255)          DEFAULT NULL,
  updated_by  VARCHAR(64)  NOT NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mcp_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP Server 配置记录（多条，Agent 可选引用）';
