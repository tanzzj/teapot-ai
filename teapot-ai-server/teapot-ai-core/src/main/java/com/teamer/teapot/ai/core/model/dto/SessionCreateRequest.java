package com.teamer.teapot.ai.core.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新建会话请求（SPEC §9）。
 */
@Data
public class SessionCreateRequest {

    @NotBlank(message = "不能为空")
    private String agentKey;

    private String title;
}
