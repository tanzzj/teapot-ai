package com.teamer.teapot.ai.core.dao;

import com.teamer.teapot.ai.core.model.AgentSkillBind;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * t_agent_skill 数据访问（SQL 见 resources/sqlclient/AgentSkillMapper.xml）。
 */
@Mapper
public interface AgentSkillMapper {

    List<AgentSkillBind> selectByAgentKey(@Param("agentKey") String agentKey);

    List<AgentSkillBind> selectBySkillName(@Param("skillName") String skillName);

    int insert(AgentSkillBind bind);

    int delete(@Param("agentKey") String agentKey, @Param("skillName") String skillName);

    int deleteByAgentKey(@Param("agentKey") String agentKey);

    int deleteBySkillName(@Param("skillName") String skillName);
}
