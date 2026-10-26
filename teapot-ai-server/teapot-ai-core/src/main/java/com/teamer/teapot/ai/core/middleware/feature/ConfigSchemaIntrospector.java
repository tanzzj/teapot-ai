package com.teamer.teapot.ai.core.middleware.feature;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 配置类内省器（SPEC §29.23）：把「中间件配置类 + {@link ConfigField}」翻成 schema，
 * 是界面与解析规则的唯一来源。
 *
 * <p>只在注册表构造时跑一次，任何不合规的声明（字段缺注解、类型不支持、options 与枚举不符、
 * defaultValue 解析不了）都抛 {@link IllegalStateException} 让应用起不来 —— 配置声明的错误
 * 属于开发期缺陷，不能等到运行时才暴露。
 */
final class ConfigSchemaIntrospector {

    private ConfigSchemaIntrospector() {
    }

    /** 内省配置类型；{@code List<T>} 视为数组配置并内省元素 T */
    static ConfigModel introspect(Type configType, String owner) {
        boolean array = false;
        Class<?> valueType;
        if (configType instanceof ParameterizedType parameterized) {
            if (parameterized.getRawType() != List.class) {
                throw new IllegalStateException(owner + " 的配置类型不支持：" + configType
                        + "（只支持配置类或 List<配置类>）");
            }
            Type element = parameterized.getActualTypeArguments()[0];
            if (!(element instanceof Class<?> elementClass)) {
                throw new IllegalStateException(owner + " 的数组配置缺少元素类型：" + configType);
            }
            array = true;
            valueType = elementClass;
        } else if (configType instanceof Class<?> clazz) {
            if (List.class.isAssignableFrom(clazz)) {
                throw new IllegalStateException(owner + " 的数组配置必须写出元素泛型（List<T>），当前是原始 List");
            }
            valueType = clazz;
        } else {
            throw new IllegalStateException(owner + " 的配置类型不支持：" + configType);
        }
        if (valueType.isInterface() || Modifier.isAbstract(valueType.getModifiers())) {
            throw new IllegalStateException(owner + " 的配置类必须是可实例化的具体类：" + valueType.getName());
        }

        Map<String, Field> fields = new LinkedHashMap<>();
        List<ConfigFieldSchema> schemas = new ArrayList<>();
        for (Class<?> current = valueType; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String where = owner + "." + field.getName();
                ConfigField annotation = field.getAnnotation(ConfigField.class);
                if (annotation == null) {
                    throw new IllegalStateException(where + " 缺少 @ConfigField 注解");
                }
                if (fields.putIfAbsent(field.getName(), field) != null) {
                    throw new IllegalStateException(where + " 与父类字段重名");
                }
                field.setAccessible(true);
                List<String> options = resolveOptions(annotation, field.getType(), where);
                ConfigFieldType type = typeOf(annotation, field, options, where);
                Object defaultValue = parseDefault(annotation.defaultValue(), type, field.getType(), options, where);
                if (annotation.label().isBlank()) {
                    throw new IllegalStateException(where + " 的 @ConfigField.label 不能为空");
                }
                schemas.add(new ConfigFieldSchema(field.getName(), type, annotation.label(),
                        annotation.description(), annotation.required(), defaultValue, options));
            }
        }
        if (schemas.isEmpty()) {
            throw new IllegalStateException(owner + " 的配置类没有任何 @ConfigField 字段：" + valueType.getName());
        }
        return new ConfigModel(configType, valueType, new ConfigSchema(array, List.copyOf(schemas)), fields);
    }

    /** options 合法性：只有 String 与枚举字段能带，枚举字段的 options 必须是常量子集 */
    private static List<String> resolveOptions(ConfigField annotation, Class<?> raw, String where) {
        List<String> options = Arrays.asList(annotation.options());
        if (options.isEmpty()) {
            if (raw.isEnum()) {
                return enumConstants(raw);
            }
            return List.of();
        }
        if (raw != String.class && !raw.isEnum()) {
            throw new IllegalStateException(where + " 只有 String 或枚举字段能声明 options，当前类型：" + raw.getName());
        }
        if (raw.isEnum()) {
            List<String> constants = enumConstants(raw);
            for (String option : options) {
                if (!constants.contains(option)) {
                    throw new IllegalStateException(where + " 的 options 含非枚举常量：" + option
                            + "，可选值：" + constants);
                }
            }
        }
        for (String option : options) {
            if (option == null || option.isBlank()) {
                throw new IllegalStateException(where + " 的 options 含空值");
            }
        }
        return List.copyOf(options);
    }

    private static List<String> enumConstants(Class<?> raw) {
        List<String> names = new ArrayList<>();
        for (Object constant : raw.getEnumConstants()) {
            names.add(((Enum<?>) constant).name());
        }
        return List.copyOf(names);
    }

    /** Java 类型 → schema 类型；映射不到直接失败，不静默当字符串处理 */
    private static ConfigFieldType typeOf(ConfigField annotation, Field field, List<String> options, String where) {
        Class<?> raw = field.getType();
        if (raw == String.class) {
            return options.isEmpty() ? ConfigFieldType.STRING : ConfigFieldType.ENUM;
        }
        if (raw.isEnum()) {
            return ConfigFieldType.ENUM;
        }
        if (!options.isEmpty()) {
            throw new IllegalStateException(where + " 声明了 options 但不是 String/枚举字段");
        }
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class) {
            return ConfigFieldType.INTEGER;
        }
        if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class) {
            return ConfigFieldType.NUMBER;
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return ConfigFieldType.BOOLEAN;
        }
        if (Map.class.isAssignableFrom(raw)) {
            if (isStringMap(field.getGenericType())) {
                return ConfigFieldType.STRING_MAP;
            }
            throw new IllegalStateException(where + " 只支持 Map<String, String>，当前：" + field.getGenericType());
        }
        throw new IllegalStateException(where + " 的配置字段类型不支持：" + raw.getName());
    }

    private static boolean isStringMap(Type generic) {
        if (!(generic instanceof ParameterizedType parameterized) || parameterized.getRawType() != Map.class) {
            return false;
        }
        Type[] args = parameterized.getActualTypeArguments();
        return args.length == 2 && args[0] == String.class && args[1] == String.class;
    }

    /** defaultValue 字面量 → 目标类型值；解析不了或不在 options 内直接失败 */
    private static Object parseDefault(String literal, ConfigFieldType type, Class<?> raw,
                                       List<String> options, String where) {
        if (literal == null || literal.isBlank()) {
            return null;
        }
        String text = literal.trim();
        try {
            return switch (type) {
                case STRING -> text;
                case ENUM -> {
                    if (!options.contains(text)) {
                        throw new IllegalStateException(where + " 的 defaultValue 不在可选值内：" + text
                                + "，可选值：" + options);
                    }
                    yield raw.isEnum() ? Enum.valueOf(asEnum(raw), text) : text;
                }
                // 数值分支不能用三元表达式：Long/Integer 混在一支会触发数值提升，默认值一律变 Long
                case INTEGER -> {
                    if (raw == long.class || raw == Long.class) {
                        yield Long.valueOf(text);
                    }
                    yield Integer.valueOf(text);
                }
                case NUMBER -> {
                    if (raw == float.class || raw == Float.class) {
                        yield Float.valueOf(text);
                    }
                    yield Double.valueOf(text);
                }
                case BOOLEAN -> {
                    String lower = text.toLowerCase(Locale.ROOT);
                    if (!lower.equals("true") && !lower.equals("false")) {
                        throw new IllegalStateException(where + " 的 defaultValue 必须是 true/false：" + text);
                    }
                    yield Boolean.valueOf(lower);
                }
                case STRING_MAP -> throw new IllegalStateException(where
                        + " 的 Map 字段不支持 defaultValue（留空即可，未配置按空 Map 处理）");
            };
        } catch (NumberFormatException e) {
            throw new IllegalStateException(where + " 的 defaultValue 解析失败：" + text + "（" + e.getMessage() + "）");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Class<? extends Enum> asEnum(Class<?> raw) {
        return (Class<? extends Enum>) raw;
    }
}
