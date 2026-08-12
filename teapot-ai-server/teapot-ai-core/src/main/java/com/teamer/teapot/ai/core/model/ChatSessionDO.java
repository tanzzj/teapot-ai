package com.teamer.teapot.ai.core.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话索引（表 t_chat_session，SPEC §9/§10.1；消息体以 agentscope_sessions 为唯一事实源）。
 */
@Data
public class ChatSessionDO implements Serializable {

    private Long id;
    private String userId;
    private String agentKey;
    /** AG-UI threadId */
    private String sessionId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
