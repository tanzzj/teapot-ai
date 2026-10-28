package com.teamer.teapot.ai.core.model.provider;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Anthropic / Claude（SPEC §29.16）：思考档 = NONE —— 该模块没有一等的 thinking 开关，
 * 只能靠 {@code additionalBodyParam("thinking", Map)} 原样透传，且要求同时给足 max_tokens，
 * 属于隐式契约；不猜参数形状，需要时再单独开配置项。
 */
@Slf4j
@Component
public class AnthropicStrategy implements ModelProviderStrategy {

    @Value("${ANTHROPIC_API_KEY:}")
    private String anthropicApiKey;

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public ThinkingSupport thinkingSupport() {
        return ThinkingSupport.NONE;
    }

    @Override
    public Model create(Spec spec) {
        String apiKey = resolveApiKey(spec, "ANTHROPIC_API_KEY", anthropicApiKey);
        if (spec.thinking() != null) {
            log.warn("anthropic 供应商暂不支持思考模式开关，已忽略 modelId={}", spec.modelId());
        }
        AnthropicChatModel.Builder builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(spec.modelName())
                .stream(true);
        if (ModelProviderStrategy.notBlank(spec.baseUrl())) {
            builder.baseUrl(spec.baseUrl());
        }
        if (spec.contextWindowSize() > 0) {
            builder.contextWindowSize(spec.contextWindowSize());
        }
        return builder.build();
    }
}
