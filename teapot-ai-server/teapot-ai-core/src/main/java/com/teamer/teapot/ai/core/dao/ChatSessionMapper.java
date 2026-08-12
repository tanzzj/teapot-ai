package com.teamer.teapot.ai.core.dao;

import com.teamer.teapot.ai.core.model.ChatSessionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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
}
