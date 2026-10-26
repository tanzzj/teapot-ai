package com.teamer.teapot.ai.core.middleware.feature;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配置解析（SPEC §29.23）：feature 里的 {@code middleware_<key>} 命名空间 → 强类型配置对象。
 *
 * <p>解析口径与 schema 同源（同一份 {@link ConfigModel}）：Jackson 默认语义（未声明字段视为非法，
 * 防止把拼错的键静默丢掉），缺省字段回填 {@link ConfigField#defaultValue()}，必填字段缺失、
 * options 越界即非法。本类只负责判定合法与否并抛 {@link IllegalArgumentException}；
 * 调用方决定是拒绝保存还是运行时 fail-open 跳过。
 */
final class ConfigCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigCodec() {
    }

    /**
     * 解析一个命名空间的值。
     *
     * @param raw feature 里的原始值（JSON 文本或已解析的 Map/List）
     * @return 配置对象；{@code raw} 为 null/空白即「未配置」，返回 null
     * @throws IllegalArgumentException 配置非法（结构不符、含未声明字段、必填缺失、枚举越界）
     */
    static Object parse(ConfigModel model, Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String text) {
            if (text.isBlank()) {
                return null;
            }
        } else if (raw instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        } else if (raw instanceof List<?> list && list.isEmpty()) {
            return null;
        }
        Object config;
        try {
            JavaType javaType = MAPPER.getTypeFactory().constructType(model.configType());
            config = raw instanceof String text
                    ? MAPPER.readValue(text, javaType)
                    : MAPPER.convertValue(raw, javaType);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw new IllegalArgumentException(rootMessage(e));
        }
        if (config == null) {
            return null;
        }
        if (config instanceof List<?> list) {
            List<Object> filled = new ArrayList<>(list.size());
            for (Object element : list) {
                filled.add(fillAndCheck(model, element));
            }
            return filled;
        }
        return fillAndCheck(model, config);
    }

    /** 回填默认值 + 校验必填；返回同一实例（数组配置逐元素处理） */
    private static Object fillAndCheck(ConfigModel model, Object config) {
        if (config == null) {
            throw new IllegalArgumentException("配置项不能为 null");
        }
        for (ConfigFieldSchema field : model.schema().fields()) {
            Field handle = model.fields().get(field.name());
            Object value = read(handle, config);
            if (value == null && field.defaultValue() != null) {
                write(handle, config, field.defaultValue());
                value = field.defaultValue();
            }
            boolean missing = value == null
                    || ((field.type() == ConfigFieldType.STRING || field.type() == ConfigFieldType.ENUM)
                        && value instanceof String s && s.isBlank());
            if (field.required() && missing) {
                throw new IllegalArgumentException("缺少必填字段 " + field.name() + "（" + field.label() + "）");
            }
            // options 是 String 字段的枚举约束（Java 枚举字段由 Jackson 兜住），schema 是唯一来源，
            // 这里补上解析侧的校验，否则越界值能存进库却与界面下拉不一致
            if (value instanceof String s && !s.isBlank() && field.type() == ConfigFieldType.ENUM
                    && !field.options().contains(s)) {
                throw new IllegalArgumentException("字段 " + field.name() + "（" + field.label()
                        + "）取值非法：" + s + "，可选值：" + field.options());
            }
        }
        return config;
    }

    private static Object read(Field field, Object config) {
        try {
            return field.get(config);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读取配置字段失败：" + field.getName(), e);
        }
    }

    private static void write(Field field, Object config, Object value) {
        try {
            field.set(config, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("回填配置默认值失败：" + field.getName(), e);
        }
    }

    /** Jackson 的定位/引用链尾巴对用户没意义（还会把内部类名带出去），只留原因本身 */
    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        int cut = message.indexOf("\n at [Source");
        if (cut < 0) {
            cut = message.indexOf(" at [Source");
        }
        return cut > 0 ? message.substring(0, cut) : message;
    }
}
