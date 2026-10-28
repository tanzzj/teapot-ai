package com.teamer.teapot.ai.core.model.provider;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容层（SPEC §29.16）：Chat Completions 端点，同时承载 DeepSeek / GLM / Kimi / vLLM
 * 等一切 OpenAI 兼容网关（网关地址走 {@code t_model_entry.baseUrl} 或 Agent 自带 baseUrl）。
 * 思考档 = NONE：builder 无 enableThinking 等价物，per-request 的 reasoning_effort 对非推理模型
 * 会被拒，沿用历史行为（忽略并 warn），不猜。
 */
@Slf4j
@Component
public class OpenAIChatStrategy implements ModelProviderStrategy {

    @Value("${OPENAI_API_KEY:}")
    private String openaiApiKey;

    @Value("${OPENAI_BASE_URL:}")
    private String openaiBaseUrl;

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public ThinkingSupport thinkingSupport() {
        return ThinkingSupport.NONE;
    }

    @Override
    public Model create(Spec spec) {
        String apiKey = resolveApiKey(spec, "OPENAI_API_KEY", openaiApiKey);
        if (spec.thinking() != null) {
            log.warn("openai 供应商不支持思考模式开关，已忽略 modelId={}", spec.modelId());
        }
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(spec.modelName())
                .stream(true);
        String baseUrl = ModelProviderStrategy.firstNonBlank(spec.baseUrl(), openaiBaseUrl);
        if (!baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        if (spec.contextWindowSize() > 0) {
            builder.contextWindowSize(spec.contextWindowSize());
        }
        return builder.build();
    }
}
