package com.teamer.teapot.ai.core.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 启停一条已存在的 skill 绑定请求（SPEC §33）。
 * enabled 用包装类型：漏传要报错，不能靠默认值悄悄把 skill 停掉。
 */
@Data
public class SkillEnabledRequest {

    @NotBlank(message = "不能为空")
    private String skillName;

    @NotNull(message = "必须显式传 true/false")
    private Boolean enabled;
}
