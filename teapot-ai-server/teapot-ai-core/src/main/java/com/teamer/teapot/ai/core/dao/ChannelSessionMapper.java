package com.teamer.teapot.ai.core.dao;

import com.teamer.teapot.ai.core.model.ChannelSessionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Channel 会话索引 Mapper（SPEC §24.9）。
 */
@Mapper
public interface ChannelSessionMapper {

    /**
     * 索引 upsert：命中 (user_id, session_id) 唯一键时刷新活跃时间；
     * title 仅首次写入（已有标题时保留）。
     */
    void upsert(ChannelSessionDO record);

    /** 某 Agent 的 channel 会话，按活跃时间倒序 */
    List<ChannelSessionDO> selectByAgent(@Param("agentKey") String agentKey, @Param("limit") int limit);

    ChannelSessionDO selectByUserSession(@Param("userId") String userId,
                                         @Param("sessionId") String sessionId);

    /** admin 会话历史删除（SPEC §24.9） */
    int delete(@Param("userId") String userId, @Param("sessionId") String sessionId);
}
