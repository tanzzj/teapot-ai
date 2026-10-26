package com.teamer.teapot.ai.core.middleware.feature;

import java.util.List;

/**
 * 单个配置字段的界面/校验视图（SPEC §29.23）。
 *
 * @param name         字段名，也是 feature JSON 里的键
 * @param type         字段类型，前端按此渲染控件
 * @param label        界面显示名
 * @param description  界面说明
 * @param required     是否必填
 * @param defaultValue 默认值（已按字段类型解析；null = 无默认）
 * @param options      枚举可选值（非枚举为空列表）
 */
public record ConfigFieldSchema(
        String name,
        ConfigFieldType type,
        String label,
        String description,
        boolean required,
        Object defaultValue,
        List<String> options) {
}
