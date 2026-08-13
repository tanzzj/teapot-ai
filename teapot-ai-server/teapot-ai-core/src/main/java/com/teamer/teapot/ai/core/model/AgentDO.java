package com.teamer.teapot.ai.core.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent 定义（表 t_agent，SPEC §10.1 / §7）。
 */
@Data
public class AgentDO implements Serializable {

    private Long id;
    /** 全局唯一，AG-UI 路由键（X-Agent-Id） */
    private String agentKey;
    private String name;
    private String description;
    private String sysPrompt;
    /** provider:model，如 dashscope:qwen-plus */
    private String modelId;
    private Integer compactionTrigger;
    private Integer compactionKeep;
    /** 扩展功能配置 JSON（一期仅 sandbox 命名空间，SPEC §16.6） */
    private String feature;
    /** 1 启用 0 停用（删除即软删为 0） */
    private Integer status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
