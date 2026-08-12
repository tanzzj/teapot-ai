-- ============================================================
-- Teapot AI 模型入口表（SPEC §6.4 修订：模型入口由配置文件改为界面配置化）
-- 库：teapot_ai；幂等：IF NOT EXISTS
-- 注：API Key 仍只存在于服务器环境变量（DASHSCOPE_API_KEY / OPENAI_API_KEY），
--     本表只存模型标识与可选 baseUrl，不落任何密钥（SPEC §14 安全约束）
-- ============================================================
USE teapot_ai;

CREATE TABLE IF NOT EXISTS t_model_entry (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  provider     VARCHAR(32)  NOT NULL COMMENT '供应商：dashscope / openai',
  model_name   VARCHAR(64)  NOT NULL COMMENT '模型名，与 provider 拼成 provider:model',
  display_name VARCHAR(64)  NULL COMMENT '界面展示名，空则用 provider:model',
  base_url     VARCHAR(255) NULL COMMENT 'OpenAI 兼容自定义端点（可选，覆盖环境变量）',
  status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_by   VARCHAR(64)  NOT NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_provider_model (provider, model_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型入口配置';

-- 预置与一期 yml 白名单相同的 DashScope 预设（幂等）
INSERT INTO t_model_entry (provider, model_name, display_name, status, created_by)
SELECT 'dashscope', 'qwen-plus', '通义千问 Plus', 1, 'system'
WHERE NOT EXISTS (SELECT 1 FROM t_model_entry WHERE provider = 'dashscope' AND model_name = 'qwen-plus');
INSERT INTO t_model_entry (provider, model_name, display_name, status, created_by)
SELECT 'dashscope', 'qwen-max', '通义千问 Max', 1, 'system'
WHERE NOT EXISTS (SELECT 1 FROM t_model_entry WHERE provider = 'dashscope' AND model_name = 'qwen-max');
INSERT INTO t_model_entry (provider, model_name, display_name, status, created_by)
SELECT 'dashscope', 'qwen-turbo', '通义千问 Turbo', 1, 'system'
WHERE NOT EXISTS (SELECT 1 FROM t_model_entry WHERE provider = 'dashscope' AND model_name = 'qwen-turbo');
