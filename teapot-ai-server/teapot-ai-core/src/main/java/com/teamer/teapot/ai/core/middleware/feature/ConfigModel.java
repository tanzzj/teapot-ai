package com.teamer.teapot.ai.core.middleware.feature;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * 内省结果（SPEC §29.23）：对外暴露的 {@link ConfigSchema} 加上运行时回填默认值所需的反射句柄。
 * 反射句柄不外泄（Class/Field 不进接口出参）。
 *
 * @param configType 完整配置类型（{@code List<T>} 时为参数化类型）
 * @param valueType  字段所在的配置类（数组配置即元素类）
 * @param schema     面向前端的结构描述
 * @param fields     字段名 → 反射句柄，与 schema.fields 同名同序
 */
record ConfigModel(Type configType, Class<?> valueType, ConfigSchema schema, Map<String, Field> fields) {
}
