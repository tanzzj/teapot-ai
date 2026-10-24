-- ============================================================
-- Teapot AI 存量 Agent 迁移到一个新空间（SPEC §28.5 需求 3）
-- 库：teapot_ai；手动执行，依赖 V13 已建表；幂等（INSERT IGNORE + UNIQUE 键 / WHERE workspace_id IS NULL）
-- 顺序：V13__workspace.sql → V14__workspace_backfill.sql
-- ============================================================
USE teapot_ai;

-- 1) 承载存量的 team 空间（workspace_code 唯一键去重；creator = 首个 admin，无则 system）
INSERT IGNORE INTO teapot_workspace (workspace_name, workspace_code, workspace_type, description, status, creator)
SELECT '存量空间', 'legacy-default', 'team',
       'V14 迁移脚本自动创建，承载去空间化前的存量 Agent', 1,
       COALESCE((SELECT user_id FROM t_user WHERE roles LIKE '%admin%' ORDER BY id LIMIT 1), 'system');

-- 2) creator 作为 owner 成员（uk_ws_user 去重）
INSERT IGNORE INTO teapot_workspace_member (workspace_id, user_id, member_role, creator)
SELECT w.id, w.creator, 'owner', w.creator
FROM teapot_workspace w
WHERE w.workspace_code = 'legacy-default';

-- 3) 存量未归属 Agent 全部归入该空间（仅 NULL，重跑不影响已归属行）
UPDATE t_agent
SET workspace_id = (SELECT id FROM teapot_workspace WHERE workspace_code = 'legacy-default')
WHERE workspace_id IS NULL;

-- 4) 每个存量 Agent 的 created_by 补为成员（避免迁移后原创建人看不到自己的 Agent；uk_ws_user 去重）
INSERT IGNORE INTO teapot_workspace_member (workspace_id, user_id, member_role, creator)
SELECT w.id, a.created_by, 'member', w.creator
FROM teapot_workspace w
JOIN (SELECT DISTINCT created_by FROM t_agent WHERE created_by IS NOT NULL AND created_by <> '') a
WHERE w.workspace_code = 'legacy-default';
