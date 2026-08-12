package com.teamer.teapot.ai.core.model.vo;

import com.teamer.teapot.ai.core.model.AgentDO;
import lombok.Data;

import java.util.List;

/**
 * Agent 详情（含绑定 skill，SPEC §7.1 detail）。
 */
@Data
public class AgentDetailVO {

    private AgentDO agent;
    private List<String> skillNames;

    public static AgentDetailVO of(AgentDO agent, List<String> skillNames) {
        AgentDetailVO vo = new AgentDetailVO();
        vo.setAgent(agent);
        vo.setSkillNames(skillNames);
        return vo;
    }
}
