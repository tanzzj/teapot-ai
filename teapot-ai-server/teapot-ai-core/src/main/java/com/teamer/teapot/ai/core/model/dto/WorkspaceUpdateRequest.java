package com.teamer.teapot.ai.core.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 编辑业务空间请求（SPEC §28.12）：字段为 null 表示不修改；
 * memberUserIds 非 null 时按集合整体同步成员（owner 行不参与增删）。
 */
@Data
public class WorkspaceUpdateRequest {

    @Size(max = 64, message = "最长 64 位")
    private String workspaceName;

    @Size(max = 512, message = "最长 512 位")
    private String description;

    /** 期望的成员 userId 全集（null = 不动成员；空间编码与类型不可改） */
    private List<String> memberUserIds;
}
