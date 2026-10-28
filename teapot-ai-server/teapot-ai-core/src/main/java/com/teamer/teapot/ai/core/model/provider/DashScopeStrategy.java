package com.teamer.teapot.ai.core.model.provider;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.EndpointType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DashScope / 通义（SPEC §29.16）：唯一在 builder 上就有真三态思考开关的一家。
 */
@Component
public class DashScopeStrategy implements ModelProviderStrategy {

    @Value("${DASHSCOPE_API_KEY:}")
    private String dashscopeApiKey;

    @Override
    public String provider() {
        return "dashscope";
    }

    @Override
    public ThinkingSupport thinkingSupport() {
        return ThinkingSupport.TRISTATE;
    }

    @Override
    public Model create(Spec spec) {
        String apiKey = resolveApiKey(spec, "DASHSCOPE_API_KEY", dashscopeApiKey);
        DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(spec.modelName())
                .stream(true);
        // 能力位含 image/video 时显式走 multimodal-generation 端点，不依赖模型名启发式（SPEC §19）
        if (spec.supportsMultimodal()) {
            builder.endpointType(EndpointType.MULTIMODAL);
        }
        if (spec.thinking() != null) {
            builder.enableThinking(spec.thinking());
        }
        // 入口配了窗口就显式下发，绕开框架按模型名查表失败时的 64000 兜底（SPEC §30.1）
        if (spec.contextWindowSize() > 0) {
            builder.contextWindowSize(spec.contextWindowSize());
        }
        return builder.build();
    }
}
