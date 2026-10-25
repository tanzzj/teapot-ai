package com.teamer.teapot.ai.core.model.provider;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openaiofficial.OpenAIResponsesChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OpenAI 官方 Responses API（SPEC §29.16）：与 Chat Completions 是两个端点、两个模块，
 * 故单列一家。思考档 = ON_ONLY：只有 {@code reasoning_effort} 这条"要思考"的通道，
 * 没有等价的"显式不思考"参数（{@code thinkingBudget} 在本模块是 fail-fast 抛异常），
 * 所以选「关闭」时按不下发处理并 warn。
 */
@Slf4j
@Component
public class OpenAIResponsesStrategy implements ModelProviderStrategy {

    /** "开启"落到 reasoning_effort 的档位：官方取值 low/medium/high/minimal，取中档不赌上限 */
    private static final String REASONING_EFFORT = "medium";

    @Value("${OPENAI_API_KEY:}")
    private String openaiApiKey;

    @Value("${OPENAI_BASE_URL:}")
    private String openaiBaseUrl;

    @Override
    public String provider() {
        return "openai-official";
    }

    @Override
    public ThinkingSupport thinkingSupport() {
        return ThinkingSupport.ON_ONLY;
    }

    @Override
    public Model create(Spec spec) {
        String apiKey = resolveApiKey(spec, "OPENAI_API_KEY", openaiApiKey);
        OpenAIResponsesChatModel.Builder builder = OpenAIResponsesChatModel.builder()
                .apiKey(apiKey)
                .modelName(spec.modelName())
                .stream(true);
        String baseUrl = ModelProviderStrategy.firstNonBlank(spec.baseUrl(), openaiBaseUrl);
        if (!baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        if (Boolean.TRUE.equals(spec.thinking())) {
            builder.generateOptions(GenerateOptions.builder()
                    .reasoningEffort(REASONING_EFFORT)
                    .build());
        } else if (Boolean.FALSE.equals(spec.thinking())) {
            log.warn("openai-official 无显式关闭思考的通道，「关闭」按不下发处理 modelId={}", spec.modelId());
        }
        return builder.build();
    }
}
