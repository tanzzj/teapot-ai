package com.teamer.teapot.ai.core.model.provider;

import com.teamer.teapot.ai.common.exception.BizException;
import io.agentscope.core.model.Model;

import java.util.Set;

/**
 * 模型供应商策略（SPEC §29.16）：一个供应商一个实现，各自持有自己的环境变量与 builder。
 * {@code ModelRegistry} 只负责凭证定序、缓存分槽与失效，不再认识任何具体供应商
 * （形状同 {@code storage} 包的存储载体策略：实现类 {@code @Component}，按字符串 key 分发）。
 */
public interface ModelProviderStrategy {

    /** 供应商标识，即 modelId 的 {@code provider:} 前缀 */
    String provider();

    /** 思考档表达力：界面与保存校验的唯一依据 */
    ThinkingSupport thinkingSupport();

    /** 是否必须有 API Key（本地推理型供应商可关，如 Ollama） */
    default boolean apiKeyRequired() {
        return true;
    }

    /** 构建 Model 实例；凭证/网关留空时由实现自行回落自己的环境变量 */
    Model create(Spec spec);

    /**
     * 构建入参。
     *
     * @param modelId      完整 {@code provider:model}，仅用于报错文案
     * @param modelName    冒号后的模型名
     * @param apiKey       Agent 自带密钥（SPEC §29），空 = 未提供，实现回落自己的环境变量
     * @param baseUrl      生效网关（Agent 自带 &gt; 入口配置），空 = 实现回落自己的环境变量/SDK 默认
     * @param thinking     三态：TRUE 显式开 / FALSE 显式关 / null 不下发
     * @param capabilities 能力位（小写），如 image/video
     * @param contextWindowSize 模型上下文窗口 token 数；0 = 未配置，交给框架按模型名查表
     *                          （查不到时 harness 会退回 64000 硬编码上限，SPEC §30.1）
     */
    record Spec(String modelId, String modelName, String apiKey, String baseUrl,
                Boolean thinking, Set<String> capabilities, int contextWindowSize) {

        public boolean hasApiKey() {
            return notBlank(apiKey);
        }

        public boolean supportsMultimodal() {
            return capabilities.contains("image") || capabilities.contains("video");
        }
    }

    static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 显式值优先，留空回落到环境变量/默认值 */
    static String firstNonBlank(String preferred, String fallback) {
        return notBlank(preferred) ? preferred : (fallback == null ? "" : fallback);
    }

    /**
     * 密钥定序：Agent 自带 &gt; 环境变量；缺失且本供应商要求密钥时抛统一错误
     * （文案沿用历史格式，便于既有排查经验继续有效）。
     */
    default String resolveApiKey(Spec spec, String envName, String envValue) {
        String apiKey = firstNonBlank(spec.apiKey(), envValue);
        if (apiKey.isBlank() && apiKeyRequired()) {
            throw new BizException("未配置 " + envName + "，无法使用 " + spec.modelId());
        }
        return apiKey;
    }
}
