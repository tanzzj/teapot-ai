package com.teamer.teapot.ai.core.model;

import lombok.Data;

import java.time.LocalDateTime;

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
    /** 1 启用 0 停用 */
    private Integer status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** provider:model 标识串 */
    public String modelId() {
        return provider + ":" + modelName;
    }
}
