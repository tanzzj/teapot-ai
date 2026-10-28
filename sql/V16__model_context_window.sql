-- ============================================================
-- 模型上下文窗口落库（SPEC §30.1）
-- 库：teapot_ai；表：t_model_entry
--
-- 背景：harness 的上下文预算取 model.getContextWindowSize()，
-- 该值由框架按模型名前缀查表（ModelContextWindows）得到；表里没有的
-- 新模型（如 qwen3.8-*）会落到 0，HarnessContextBuilder 于是退回
-- 硬编码 64000 token 上限，长会话直接报
-- "Prepared model input exceeds budget"。
-- 本列把窗口从"猜模型名"变成入口配置，0 = 未配置（维持框架现状）。
--
-- 幂等：列已存在时跳过（MySQL 8.4 无 ADD COLUMN IF NOT EXISTS）
-- ============================================================
USE teapot_ai;

SET @col_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = 'teapot_ai'
    AND TABLE_NAME = 't_model_entry'
    AND COLUMN_NAME = 'context_window_size'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE t_model_entry ADD COLUMN context_window_size INT NOT NULL DEFAULT 0 COMMENT ''模型上下文窗口 token 数，0=未配置'' AFTER capabilities',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 按 2026-10-27 对 DashScope 原生目录 GET /api/v1/models?name=<model> 的实测
-- output.models[].model_info.context_window 回填。本段按模型名无条件覆盖，可重复执行。
--
-- ⚠️ 口径教训（别再用 compatible-mode 那个值）：GET /compatible-mode/v1/models/{model} 的
-- extra_info.default_envs 是「请求默认参数袋」（同袋里有 num_beams/seed/top_p/max_tokens），
-- 里面的 max_tokens 是**默认输出上限**、不是上下文窗口：qwen3.8-max 那里报 131072，
-- 而它真正的 context_window 是 1,000,000（131072 恰为其 max_output_tokens）。
-- 本文件首版按那个错值回填过，这一段把它纠正过来。
UPDATE t_model_entry SET context_window_size = 1000000
 WHERE provider = 'dashscope'
   AND model_name IN ('qwen-plus', 'qwen3.7-flash', 'qwen3.8-flash', 'qwen3.8-max', 'qwen3.8-27b');
UPDATE t_model_entry SET context_window_size = 32768
 WHERE provider = 'dashscope' AND model_name = 'qwen-max';
-- qwen-turbo 与 qwen3-vl-flash-2026-01-22 在 DashScope 目录里查不到（?name= 返回 total:0），
-- 属失效入口，保持 0 = 交回框架推断；探活会如实报「目录中无此模型」。
UPDATE t_model_entry SET context_window_size = 0
 WHERE provider = 'dashscope'
   AND model_name IN ('qwen-turbo', 'qwen3-vl-flash-2026-01-22');
