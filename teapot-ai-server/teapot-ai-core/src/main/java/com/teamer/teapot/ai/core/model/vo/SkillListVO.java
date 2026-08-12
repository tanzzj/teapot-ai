package com.teamer.teapot.ai.core.model.vo;

import lombok.Data;

/**
 * Skill 列表项（SPEC §8.3 list：name/description/来源）。
 */
@Data
public class SkillListVO {

    private String name;
    private String description;
    /** 来源（seed / platform） */
    private String source;
}
