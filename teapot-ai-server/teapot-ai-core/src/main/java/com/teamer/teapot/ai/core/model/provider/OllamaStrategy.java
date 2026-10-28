package com.teamer.teapot.ai.core.model.provider;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import io.agentscope.extensions.model.ollama.options.ThinkOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ollama 本地推理（SPEC §29.16）：无凭证概念，网关缺省由 SDK 落到 localhost:11434，
 * 所以 {@link #apiKeyRequired()} 为 false —— 否则会被平台密钥闸门误拦。
 * 思考档 = TRISTATE：{@code think} 字段可显式 true / false / 不下发。
 */
@Component
public class OllamaStrategy implements ModelProviderStrategy {

    /** 自建网关（非本机部署时配置），留空 = 用 SDK 默认的 http://localhost:11434 */
    @Value("${OLLAMA_BASE_URL:}")
    private String ollamaBaseUrl;

    @Override
    public String provider() {
        return "ollama";
    }

    @Override
    public ThinkingSupport thinkingSupport() {
        return ThinkingSupport.TRISTATE;
    }

    @Override
    public boolean apiKeyRequired() {
        return false;
    }

    @Override
    public Model create(Spec spec) {
        OllamaChatModel.Builder builder = OllamaChatModel.builder()
                .modelName(spec.modelName())
                .stream(true);
        String baseUrl = ModelProviderStrategy.firstNonBlank(spec.baseUrl(), ollamaBaseUrl);
        if (!baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        if (spec.thinking() != null) {
            builder.defaultOptions(OllamaOptions.builder()
                    .thinkOption(spec.thinking()
                            ? ThinkOption.ThinkBoolean.ENABLED
                            : ThinkOption.ThinkBoolean.DISABLED)
                    .build());
        }
        if (spec.contextWindowSize() > 0) {
            builder.contextWindowSize(spec.contextWindowSize());
        }
        return builder.build();
    }
}
