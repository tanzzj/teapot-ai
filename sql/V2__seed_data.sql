-- ============================================================
-- Teapot AI 种子数据（SPEC §10.4）；幂等：INSERT IGNORE / 存在性判断
-- ⚠️ admin 初始密码为 Teapot@2026（BCrypt 存密），首次登录必须强制改密
-- ============================================================
USE teapot_ai;

-- 管理员账号（初始密码 Teapot@2026，BCrypt 哈希；首次登录强制改密）
INSERT IGNORE INTO t_user (user_id, username, password, real_name, roles, status)
VALUES ('admin', 'admin', '$2a$10$GLIpnCRO2/pbuZBXZQKjpONbVuptoYnX7lEtGq8Sh8QFD9eRYgmpm', '管理员', 'admin', 1);

-- 示例 Agent：general-assistant（绑定 0 个 skill，用于冒烟）
INSERT IGNORE INTO t_agent (agent_key, name, description, sys_prompt, model_id, created_by)
VALUES ('general-assistant', '通用助手', 'Teapot AI 默认通用助手（冒烟用）',
        '你是 Teapot AI 平台的通用助手，使用简体中文回答，回答简洁、准确、有条理。',
        'dashscope:qwen-plus', 'admin');

-- ============================================================
-- 示例 Skill：meeting-notes（会议纪要），验证 Skill 市场链路
-- 注意：落在 agentscope 库（MysqlSkillRepository 管理）
-- ============================================================
USE agentscope;

INSERT INTO agentscope_skills (name, description, skill_content, source, metadata_json)
SELECT 'meeting-notes', '将会议要点整理为结构化纪要（议题/结论/行动项）',
'---\nname: meeting-notes\ndescription: 将会议要点整理为结构化纪要（议题/结论/行动项）\n---\n\n# 会议纪要整理\n\n## 使用场景\n用户提供会议要点或录音转写文本时，输出结构化纪要。\n\n## 输出格式\n1. **会议主题**与时间/参会人（若提供）\n2. **议题与讨论**：逐条列出\n3. **结论**：明确的决定\n4. **行动项**：事项 + 负责人 + 截止时间（表格）\n\n## 约束\n- 不虚构未提及的内容；缺失信息标注"待确认"\n- 行动项必须可执行、可验收\n',
       'seed', '{"category":"productivity","version":"1.0.0"}'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM agentscope_skills WHERE name = 'meeting-notes');
