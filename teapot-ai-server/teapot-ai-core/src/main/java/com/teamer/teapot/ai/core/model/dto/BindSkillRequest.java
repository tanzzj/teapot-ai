package com.teamer.teapot.ai.core.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 绑定/解绑 skill 请求（SPEC §7.1 bindSkill/unbindSkill）。
 */
@Data
public class BindSkillRequest {

    @NotBlank(message = "不能为空")
    private String skillName;
}
