package com.teamer.teapot.ai.core.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型入口配置（t_model_entry，SPEC §6.4 修订：界面配置化）。
 * 只存模型标识与可选 baseUrl，不落 API Key。
 */
@Data
public class ModelEntryDO {

    private Long id;
    /** 供应商：dashscope / openai */
    private String provider;
    /** 模型名，与 provider 拼成 provider:model */
    private String modelName;
    /** 界面展示名，空则用 provider:model */
    private String displayName;
    /** OpenAI 兼容自定义端点（可选） */
    private String baseUrl;
    /** 能力位逗号分隔：image,audio,video；null=纯文本（SPEC §19） */
    private String capabilities;
    /** 1 启用 0 停用 */
    private Integer status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 授权空间桶（非 t_model_entry 列，读写经 teapot_workspace_model，SPEC §28.11）：
     * 0=全部空间（含个人），其余为团队空间 id；空=未开放任何空间。
     */
    private List<Long> workspaceIds;

    /** provider:model 标识串 */
    public String modelId() {
        return provider + ":" + modelName;
    }
}
