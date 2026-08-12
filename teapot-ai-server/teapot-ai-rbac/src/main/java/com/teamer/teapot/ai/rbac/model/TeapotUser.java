package com.teamer.teapot.ai.rbac.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 平台用户（表 t_user，SPEC §5.1 / §10.1；演进自老 teapot-rbac 的 TeapotUser）。
 */
@Data
public class TeapotUser implements Serializable {

    private Long id;
    /** 业务用户ID */
    private String userId;
    private String username;
    /** BCrypt hash，不出后端 */
    @JsonIgnore
    private String password;
    private String realName;
    private String mobile;
    private String email;
    /** 逗号分隔 roleId：admin,developer,viewer */
    private String roles;
    /** 1 启用 0 停用 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public List<String> getRoleList() {
        if (roles == null || roles.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(roles.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
