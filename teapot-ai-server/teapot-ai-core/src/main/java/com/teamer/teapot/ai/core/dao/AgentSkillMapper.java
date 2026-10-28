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

    /** 全量绑定行（SPEC §29.10）：skill 列表据此给出每个 skill 被哪些 Agent 绑定 */
    List<AgentSkillBind> selectAllBinds();

    int insert(AgentSkillBind bind);

    /** 只改生效位（SPEC §33）：返回 0 表示这条绑定不存在，调用方据此报错 */
    int updateEnabled(@Param("agentKey") String agentKey,
                      @Param("skillName") String skillName,
                      @Param("enabled") boolean enabled);

    int delete(@Param("agentKey") String agentKey, @Param("skillName") String skillName);

    int deleteByAgentKey(@Param("agentKey") String agentKey);

    int deleteBySkillName(@Param("skillName") String skillName);
}
