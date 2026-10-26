package com.teamer.teapot.ai.core.middleware.feature;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * feature 驱动中间件的配置字段声明（SPEC §29.23）：一个注解同时产出「面向前端的 schema」与
 * 「面向运行时的解析规则」，两者共用同一份声明，避免界面与后端各写一遍后漂移。
 *
 * <p>配置类的每个实例字段都必须带本注解，缺注解在注册表启动内省时直接失败（fail-fast）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigField {

    /** 界面显示名 */
    String label();

    /** 界面说明（写清语义与边界，隐式默认值也要写在这里） */
    String description() default "";

    /** 必填：解析后为 null 即视为配置非法（保存时拒绝，运行时 fail-open 跳过该中间件） */
    boolean required() default false;

    /**
     * 默认值的字面量形式：留空 = 无默认。启动时按字段类型解析，解析不了直接失败；
     * 解析后写进 schema 供界面展示，并在配置里缺该字段时回填到配置对象（仅对可为 null 的字段生效，
     * 基本类型缺省值由 Java 语义决定，不回填）。
     */
    String defaultValue() default "";

    /** 可选值：非空即把该字段当枚举暴露（String 字段用给定值，枚举字段必须是常量子集） */
    String[] options() default {};
}
