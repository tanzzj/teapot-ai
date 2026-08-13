package com.teamer.teapot.ai.core.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 会话改名请求（SPEC §9）：懒创建会话后由前端以首条消息回填标题。
 */
@Data
public class SessionRenameRequest {

    @NotBlank(message = "不能为空")
    private String sessionId;

    @NotBlank(message = "不能为空")
    private String title;
}
