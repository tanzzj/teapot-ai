package com.teamer.teapot.ai.core.model.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量移动 Agent 到同一空间（SPEC §35）。
 * workspaceId 不加 @NotNull：null 是合法语义 = 移回个人空间（与单条移动同一口径）。
 */
@Data
public class BatchMoveWorkspaceRequest {

    @NotEmpty(message = "请先选择要移动的 Agent")
    private List<String> agentKeys;

    private Long workspaceId;
}
