-- ============================================================
-- 修复 V2 种子中文双重编码（M0 执行时 mysql 客户端默认 latin1 所致）
-- 幂等：直接 UPDATE 覆盖为正确值；须以 utf8mb4 连接执行（run-sql.sh 已内置）
-- ============================================================
USE teapot_ai;

UPDATE t_user SET real_name = '管理员' WHERE user_id = 'admin';

UPDATE t_agent
SET name = '通用助手',
    description = 'Teapot AI 默认通用助手（冒烟用）',
    sys_prompt = '你是 Teapot AI 平台的通用助手，使用简体中文回答，回答简洁、准确、有条理。'
WHERE agent_key = 'general-assistant';

USE agentscope;

UPDATE agentscope_skills
SET description = '将会议要点整理为结构化纪要（议题/结论/行动项）',
    skill_content = '---\nname: meeting-notes\ndescription: 将会议要点整理为结构化纪要（议题/结论/行动项）\n---\n\n# 会议纪要整理\n\n## 使用场景\n用户提供会议要点或录音转写文本时，输出结构化纪要。\n\n## 输出格式\n1. **会议主题**与时间/参会人（若提供）\n2. **议题与讨论**：逐条列出\n3. **结论**：明确的决定\n4. **行动项**：事项 + 负责人 + 截止时间（表格）\n\n## 约束\n- 不虚构未提及的内容；缺失信息标注"待确认"\n- 行动项必须可执行、可验收\n'
WHERE name = 'meeting-notes';
