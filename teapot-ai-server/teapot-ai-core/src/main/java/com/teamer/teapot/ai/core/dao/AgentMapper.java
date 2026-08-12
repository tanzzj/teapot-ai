package com.teamer.teapot.ai.core.dao;

import com.teamer.teapot.ai.core.model.AgentDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * t_agent 数据访问（SQL 见 resources/sqlclient/AgentMapper.xml）。
 */
@Mapper
public interface AgentMapper {

    AgentDO selectByAgentKey(@Param("agentKey") String agentKey);

    List<AgentDO> selectPage(@Param("keyword") String keyword,
                             @Param("includeDisabled") boolean includeDisabled,
                             @Param("offset") int offset, @Param("size") int size);

    long count(@Param("keyword") String keyword, @Param("includeDisabled") boolean includeDisabled);

    List<AgentDO> selectAllEnabled();

    int insert(AgentDO agent);

    int update(AgentDO agent);

    int softDelete(@Param("agentKey") String agentKey);
}
