package com.teamer.teapot.ai.core.middleware.feature;

import java.util.List;

/**
 * 配置 schema（SPEC §29.23）：中间件配置类的结构描述，产出给前端渲染表单。
 *
 * @param array  true = 配置本体是数组（{@code List<T>}），fields 描述元素 T；false = 配置本体是对象
 * @param fields 字段清单，按声明顺序
 */
public record ConfigSchema(boolean array, List<ConfigFieldSchema> fields) {
}
