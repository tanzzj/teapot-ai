package com.teamer.teapot.ai.core.model.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 修改 Agent 请求（SPEC §7.1；字段为 null 表示不修改；agentKey 不可改）。
 */
@Data
public class AgentUpdateRequest {

    @Size(max = 64, message = "最长 64 位")
    private String name;

    @Size(max = 512, message = "最长 512 位")
    private String description;

    private String sysPrompt;

    @Pattern(regexp = "^[a-z]+:[A-Za-z0-9.\\-_]+$", message = "格式须为 provider:model")
    private String modelId;

    private Integer compactionTrigger;
    private Integer compactionKeep;

    /** 非 null 时整体替换绑定集合 */
    private List<String> skillNames;
}
