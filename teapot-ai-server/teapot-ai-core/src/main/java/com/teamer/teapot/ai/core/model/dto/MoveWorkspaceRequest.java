package com.teamer.teapot.ai.core.model.dto;

import lombok.Data;

/**
 * Agent 移动空间请求（SPEC §28.4 moveWorkspace）。
 * workspaceId 为 null = 移回个人空间（懒创建）。
 */
@Data
public class MoveWorkspaceRequest {

    private Long workspaceId;
}
