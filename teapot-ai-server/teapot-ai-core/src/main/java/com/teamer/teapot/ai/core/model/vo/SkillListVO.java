package com.teamer.teapot.ai.core.model.vo;

import lombok.Data;

import java.util.List;

/**
 * Skill 列表项（SPEC §8.3 list：name/description/来源）。
 */
@Data
public class SkillListVO {

    private String name;
    private String description;
    /** 来源（seed / platform） */
    private String source;
    /** 绑定该 skill 的 Agent（SPEC §29.10）：Agent 侧据此决定「删除本体」是否可用 */
    private List<String> boundAgents;
    /**
     * 本次查询带的 agentKey 是否绑定了这条 skill（SPEC §33）。
     * 未传 agentKey 时为 null（不评估），与 false 区分开：null=没问，false=问了且未绑定。
     */
    private Boolean agentBound;
    /** 该 Agent 的这条绑定是否生效（停用=绑定保留但不装配）；未绑定时为 null */
    private Boolean agentEnabled;
}
