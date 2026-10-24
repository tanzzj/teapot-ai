package com.teamer.teapot.ai.core.model;

import lombok.Data;

/**
 * 模型入口 × 业务空间 授权行（teapot_workspace_model，SPEC §28.11）。
 * workspaceId=0 表示「全部空间（含个人空间）」桶。
 */
@Data
public class ModelWorkspaceGrantDO {

    private Long modelEntryId;
    private Long workspaceId;
}
