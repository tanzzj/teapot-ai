package com.teamer.teapot.ai.core.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

/**
 * Skill 工坊保存请求（SPEC §8.2：表单 → SKILL.md，upsert）。
 */
@Data
public class SkillSaveRequest {

    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_-]{2,64}$", message = "仅允许字母数字下划线中划线，2-64 位")
    private String name;

    /** 触发描述（决定 Agent 何时加载该 skill） */
    @NotBlank(message = "不能为空")
    private String description;

    /** 指令正文（markdown body） */
    @NotBlank(message = "不能为空")
    private String instructions;

    /** 附件资源：references/ 与 scripts/（一期脚本仅分发不执行） */
    private List<SkillResourceItem> resources;

    @Data
    public static class SkillResourceItem {
        /** 相对路径，如 references/guide.md、scripts/run.sh */
        @NotBlank(message = "不能为空")
        @Pattern(regexp = "^(references|scripts)/[A-Za-z0-9._\\-/]{1,200}$",
                message = "仅允许 references/ 或 scripts/ 前缀")
        private String path;
        @NotBlank(message = "不能为空")
        private String content;
    }
}
