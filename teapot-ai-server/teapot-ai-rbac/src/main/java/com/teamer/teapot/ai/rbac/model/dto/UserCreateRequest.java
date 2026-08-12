package com.teamer.teapot.ai.rbac.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建用户请求（SPEC §5.4：仅 admin）。
 */
@Data
public class UserCreateRequest {

    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_-]{2,64}$", message = "仅允许字母数字下划线中划线，2-64 位")
    private String userId;

    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_-]{2,64}$", message = "仅允许字母数字下划线中划线，2-64 位")
    private String username;

    @NotBlank(message = "不能为空")
    @Size(min = 8, max = 64, message = "长度 8-64 位")
    private String password;

    private String realName;
    private String mobile;
    private String email;

    /** 逗号分隔 roleId，取值 admin,developer,viewer */
    @NotBlank(message = "不能为空")
    private String roles;
}
