package com.teamer.teapot.ai.rbac.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshRequest {

    @NotBlank(message = "不能为空")
    private String refreshToken;
}
