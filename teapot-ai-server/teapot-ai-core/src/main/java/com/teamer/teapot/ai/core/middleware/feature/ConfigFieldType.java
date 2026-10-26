package com.teamer.teapot.ai.core.middleware.feature;

/**
 * 配置字段类型（SPEC §29.23）：内省器从 Java 类型映射而来，前端按此渲染控件。
 * 映射不到的类型在启动时直接失败，不做静默降级。
 */
public enum ConfigFieldType {

    /** String */
    STRING,
    /** int / long 及其包装类型 */
    INTEGER,
    /** double / float 及其包装类型 */
    NUMBER,
    /** boolean / Boolean */
    BOOLEAN,
    /** 枚举，或带 options 的 String */
    ENUM,
    /** Map&lt;String, String&gt; */
    STRING_MAP
}
