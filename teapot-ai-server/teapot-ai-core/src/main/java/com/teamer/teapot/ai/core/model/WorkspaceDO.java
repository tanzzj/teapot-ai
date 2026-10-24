package com.teamer.teapot.ai.core.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务空间（表 teapot_workspace，SPEC §28.2）。
 */
@Data
public class WorkspaceDO implements Serializable {

    private Long id;
    private String workspaceName;
    /** 空间业务编码，全局唯一；个人空间=personal:{userId} */
    private String workspaceCode;
    /** personal=个人空间 team=团队空间 */
    private String workspaceType;
    private String description;
    /** 空间级扩展配置 JSON（预留，仿 AgentDO.feature） */
    private String feature;
    /** 1 启用 0 停用 */
    private Integer status;
    /** 创建人 userId（个人空间即归属人） */
    private String creator;
    private LocalDateTime gmtCreate;
    private LocalDateTime gmtModified;
}
