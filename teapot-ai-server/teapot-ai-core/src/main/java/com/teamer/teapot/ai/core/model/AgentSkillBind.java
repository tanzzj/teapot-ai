package com.teamer.teapot.ai.core.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent-Skill 绑定（表 t_agent_skill，SPEC §10.1）。
 */
@Data
public class AgentSkillBind implements Serializable {

    private Long id;
    private String agentKey;
    private String skillName;
    private String createdBy;
    private LocalDateTime createdAt;
}
