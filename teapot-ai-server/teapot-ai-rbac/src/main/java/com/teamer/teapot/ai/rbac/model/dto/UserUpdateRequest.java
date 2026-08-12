package com.teamer.teapot.ai.rbac.model.dto;

import lombok.Data;

/**
 * 修改用户请求（SPEC §5.4：角色/状态/重置密码，仅 admin；字段为 null 表示不修改）。
 */
@Data
public class UserUpdateRequest {

    private String realName;
    private String mobile;
    private String email;
    /** 逗号分隔 roleId */
    private String roles;
    /** 1 启用 0 停用 */
    private Integer status;
    /** 重置密码（BCrypt 存密） */
    private String newPassword;
}
