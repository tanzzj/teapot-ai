-- ============================================================
-- Teapot AI Channel GitHub 渠道（SPEC §24 修订）：
-- webhook secret 敏感凭证列（AES-GCM 密文，同 app_secret 加密方案）；
-- PAT token 复用 app_secret 列，bot 账号 login 复用 app_key 列。
-- 幂等：仅当列不存在时添加。
-- ============================================================
USE teapot_ai;

ALTER TABLE t_channel_config
  ADD COLUMN IF NOT EXISTS webhook_secret VARCHAR(512) DEFAULT NULL
  COMMENT 'GitHub webhook secret，AES-GCM 密文（校验 X-Hub-Signature-256）';
