package com.teamer.teapot.ai.core.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Channel 会话索引（表 t_channel_session，SPEC §24.9）：
 * 消息体仍以 agentscope_sessions 为事实源，本表仅存索引供 admin 全量会话历史视图。
 */
@Data
public class ChannelSessionDO implements Serializable {

    private Long id;
    /** 所属 Agent */
    private String agentKey;
    /** gateway 身份（钉钉 peer：staffId/conversationId） */
    private String userId;
    /** gateway 生成的会话 id（gw-…） */
    private String sessionId;
    /** dingtalk（后续渠道枚举） */
    private String channelType;
    /** 首条用户消息截断 50 字（仅首次写入） */
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime lastActiveAt;
}
