package com.teamer.teapot.ai.core.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 新增 Agent 请求（SPEC §7.1）。
 */
@Data
public class AgentCreateRequest {

    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^[a-z][a-z0-9-]{2,31}$", message = "小写字母开头，仅允许小写字母数字中划线，3-32 位")
    private String agentKey;

    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64 位")
    private String name;

    @Size(max = 512, message = "最长 512 位")
    private String description;

    @NotBlank(message = "不能为空")
    private String sysPrompt;

    /** provider:model，如 dashscope:qwen-plus */
    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^[a-z]+:[A-Za-z0-9.\\-_]+$", message = "格式须为 provider:model")
    private String modelId;

    private Integer compactionTrigger;
    private Integer compactionKeep;

    /** 绑定的 skill name 列表（可空） */
    private List<String> skillNames;
}
