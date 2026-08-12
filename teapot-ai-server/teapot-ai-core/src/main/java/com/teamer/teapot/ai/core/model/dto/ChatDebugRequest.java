package com.teamer.teapot.ai.core.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 非 AG-UI 同步对话请求（SPEC §7.1 /api/agent/chat，调试用）。
 */
@Data
public class ChatDebugRequest {

    @NotBlank(message = "不能为空")
    private String message;

    /** 会话标识（AG-UI threadId 同域），缺省 default */
    private String sessionId;
}
