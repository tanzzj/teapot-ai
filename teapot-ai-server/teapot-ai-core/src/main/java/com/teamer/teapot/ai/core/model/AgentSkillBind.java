package com.teamer.teapot.ai.core.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent-Skill 绑定（表 t_agent_skill，SPEC §10.1）。
 * 绑定与是否生效是两层（SPEC §33）：本行存在 = 这个 skill 属于这个 Agent；
 * {@code enabled=false} = 关系保留但装配时不进 SkillFilter。解绑是删行，不是置 0。
 */
@Data
public class AgentSkillBind implements Serializable {

    private Long id;
    private String agentKey;
    private String skillName;
    /** 默认 true：新建绑定即生效，且漏设该字段也不会静默把 skill 停掉 */
    private boolean enabled = true;
    private String createdBy;
    private LocalDateTime createdAt;
}
