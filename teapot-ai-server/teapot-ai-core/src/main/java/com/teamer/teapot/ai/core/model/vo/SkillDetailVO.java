package com.teamer.teapot.ai.core.model.vo;

import lombok.Data;

import java.util.List;

/**
 * Skill 详情（SKILL.md 解析回表单结构 + 资源清单，SPEC §8.3 detail）。
 */
@Data
public class SkillDetailVO {

    private String name;
    private String description;
    /** markdown body */
    private String instructions;
    /** 来源（seed / platform） */
    private String source;
    /** 完整 SKILL.md 原文 */
    private String skillContent;
    private List<ResourceItem> resources;

    @Data
    public static class ResourceItem {
        private String path;
        private String content;
    }
}
