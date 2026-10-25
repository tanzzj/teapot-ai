package com.teamer.teapot.ai.core.model.provider;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Google Gemini（SPEC §29.16）：思考档 = NONE —— 该模块只有 {@code thinkingBudget(Integer)}，
 * 非空即"要求思考并限定预算"，没有一个"按模型默认思考"或"确定性地不思考"的开关
 * （budget=0 是否等价关闭未经证实），挑固定数值等于替用户决定成本，故先不下发。
 * 注意本模块的流式方法名是 {@code streamEnabled} 而非 {@code stream}。
 */
@Slf4j
@Component
public class GeminiStrategy implements ModelProviderStrategy {

    @Value("${GEMINI_API_KEY:}")
    private String geminiApiKey;

    @Override
    public String provider() {
        return "gemini";
    }

    @Override
    public ThinkingSupport thinkingSupport() {
        return ThinkingSupport.NONE;
    }

    @Override
    public Model create(Spec spec) {
        String apiKey = resolveApiKey(spec, "GEMINI_API_KEY", geminiApiKey);
        if (spec.thinking() != null) {
            log.warn("gemini 供应商暂不支持思考模式开关，已忽略 modelId={}", spec.modelId());
        }
        GeminiChatModel.Builder builder = GeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(spec.modelName())
                .streamEnabled(true);
        if (ModelProviderStrategy.notBlank(spec.baseUrl())) {
            builder.baseUrl(spec.baseUrl());
        }
        return builder.build();
    }
}
