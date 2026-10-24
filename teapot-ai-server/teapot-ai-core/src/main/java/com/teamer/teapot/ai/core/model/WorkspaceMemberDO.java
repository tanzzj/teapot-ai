package com.teamer.teapot.ai.core.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务空间成员（表 teapot_workspace_member，SPEC §28.2/§28.3）。
 */
@Data
public class WorkspaceMemberDO implements Serializable {

    private Long id;
    private Long workspaceId;
    /** 成员 userId（t_user.user_id） */
    private String userId;
    /** owner=创建人/空间主 member=普通成员 */
    private String memberRole;
    /** 谁加的这条成员 */
    private String creator;
    private LocalDateTime gmtCreate;
}
