package com.teamer.teapot.ai.core.model.vo;

import lombok.Data;

/**
 * 顶栏空间下拉框条目（SPEC §28.4 /api/workspace/list）。
 * 个人空间在尚未落库时以 id=null 的合成项下发（懒创建，SPEC §28.3）。
 */
@Data
public class WorkspaceVO {

    /** null = 个人空间尚未创建（合成项） */
    private Long id;
    private String workspaceName;
    private String workspaceCode;
    /** personal / team */
    private String workspaceType;
    /** 当前用户在该空间的角色：owner / member；合成个人空间为 owner */
    private String myRole;
    private String description;

    public static WorkspaceVO of(Long id, String name, String code, String type, String myRole, String desc) {
        WorkspaceVO vo = new WorkspaceVO();
        vo.setId(id);
        vo.setWorkspaceName(name);
        vo.setWorkspaceCode(code);
        vo.setWorkspaceType(type);
        vo.setMyRole(myRole);
        vo.setDescription(desc);
        return vo;
    }
}
