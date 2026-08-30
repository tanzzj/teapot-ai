package com.teamer.teapot.ai.core.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

/**
 * 工具提供型中间件抽象：将「注册工具 + 向 system prompt 注入用法说明」成对封装为一个中间件。
 * <p>
 * 实现类通过 {@link #providedTools()} 提供工具对象（{@code @Tool} 注解类实例），
 * 由装配器在 Agent 构建后注册到 Toolkit；通过 {@link #toolUsageDescription()} 提供
 * 面向模型的用法描述，默认 {@code onSystemPrompt} 实现会把描述追加到 system prompt 末尾。
 */
public interface ToolProvidedMiddleware extends MiddlewareBase {

    /** 本中间件提供的工具对象（@Tool 注解类实例）；null = 不提供工具 */
    Object providedTools();

    /** 注入 system prompt 的工具用法描述；null/空白 = 不注入 */
    String toolUsageDescription();

    @Override
    default Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String desc = toolUsageDescription();
        if (desc == null || desc.isBlank()) {
            return Mono.just(currentPrompt);
        }
        String base = currentPrompt == null ? "" : currentPrompt;
        return Mono.just(base + "\n\n" + desc);
    }
}
