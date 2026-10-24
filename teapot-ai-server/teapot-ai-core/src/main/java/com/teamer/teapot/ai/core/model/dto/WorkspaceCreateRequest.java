package com.teamer.teapot.ai.core.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 新建业务空间请求（SPEC §28.4 create）。memberUserIds 为空时仅把 creator 加为 owner。
 */
@Data
public class WorkspaceCreateRequest {

    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64 位")
    private String workspaceName;

    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^[a-z][a-z0-9-]{2,63}$", message = "小写字母开头，仅允许小写字母数字中划线，3-64 位")
    private String workspaceCode;

    @Size(max = 512, message = "最长 512 位")
    private String description;

    /** 一次性加入的成员 userId 列表（可空） */
    private List<String> memberUserIds;
}
