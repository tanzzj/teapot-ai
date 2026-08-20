-- ============================================================
-- Teapot AI 头像列（SPEC §23：Agent 头像 + 用户头像，OSS 直链）
-- 库：teapot_ai；迁移方式与 V4/V5 一致（手动执行，无 Flyway）
-- ============================================================
USE teapot_ai;

-- Agent 头像（OSS 对象直链；NULL = 未设置，前端回落首字母占位）
ALTER TABLE t_agent
  ADD COLUMN avatar VARCHAR(512) NULL COMMENT '头像 OSS 直链（SPEC §23）' AFTER description;

-- 用户头像（同上）
ALTER TABLE t_user
  ADD COLUMN avatar VARCHAR(512) NULL COMMENT '头像 OSS 直链（SPEC §23）' AFTER email;
