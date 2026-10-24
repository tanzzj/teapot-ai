package com.teamer.teapot.ai.core.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 空间成员增/删请求（SPEC §28.4 member）。
 */
@Data
public class WorkspaceMemberRequest {

    @NotBlank(message = "不能为空")
    private String userId;
}
