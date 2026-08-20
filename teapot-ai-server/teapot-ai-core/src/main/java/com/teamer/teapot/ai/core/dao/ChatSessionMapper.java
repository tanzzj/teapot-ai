package com.teamer.teapot.ai.core.dao;

import com.teamer.teapot.ai.core.model.ChatSessionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * t_chat_session 数据访问（SQL 见 resources/sqlclient/ChatSessionMapper.xml）。
 */
@Mapper
public interface ChatSessionMapper {

    List<ChatSessionDO> selectByUser(@Param("userId") String userId,
                                     @Param("agentKey") String agentKey);

    ChatSessionDO selectByUserSession(@Param("userId") String userId,
                                      @Param("sessionId") String sessionId);

    int insert(ChatSessionDO session);

    int touch(@Param("userId") String userId, @Param("sessionId") String sessionId,
              @Param("title") String title);

    int delete(@Param("userId") String userId, @Param("sessionId") String sessionId);

    /** 某 Agent 的会话按日聚合（since 起，含当日；行：d=yyyy-MM-dd、c=数量） */
    List<Map<String, Object>> countByAgentAndDate(@Param("agentKey") String agentKey,
                                                  @Param("since") String since);
}
