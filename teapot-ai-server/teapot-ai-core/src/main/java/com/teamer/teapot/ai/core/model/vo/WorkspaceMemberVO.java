package com.teamer.teapot.ai.core.model.vo;

import lombok.Data;

/**
 * 空间成员视图（SPEC §28.4 GET /api/workspace/{id}/members，join t_user 带出展示名）。
 */
@Data
public class WorkspaceMemberVO {

    private String userId;
    private String username;
    private String realName;
    /** owner / member */
    private String memberRole;
}
