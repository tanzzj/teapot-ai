package com.teamer.teapot.ai.core.middleware.feature;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 框架层桥接（SPEC §29.23）：单例中间件 + 本次装配的 feature 快照 → 一个可挂到 Agent 上的
 * {@link MiddlewareBase} 实例。配置在首次用到时才解析并记忆（不触发的钩子不付解析代价），
 * 未配置/配置非法一律原样放行并记警告 —— 运行期 fail-open，不让中间件配置问题拖死整轮对话。
 *
 * <p>{@link #activePoints()} 与 {@link #order()} 仍由中间件本体决定，桥接层不改语义。
 */
@Slf4j
final class FeatureMiddlewareBinding<C> implements MiddlewareBase {

    private final FeatureDrivenMiddleware<C> delegate;
    private final ConfigModel model;
    private final String agentKey;
    private final Object rawNamespace;
    private final AtomicReference<Resolved<C>> resolved = new AtomicReference<>();

    FeatureMiddlewareBinding(FeatureDrivenMiddleware<C> delegate, ConfigModel model,
                             String agentKey, Object rawNamespace) {
        this.delegate = delegate;
        this.model = model;
        this.agentKey = agentKey;
        this.rawNamespace = rawNamespace;
    }

    private record Resolved<T>(T config) {
    }

    /** 懒解析并记忆；返回 null 表示「按未配置处理」 */
    private C config() {
        Resolved<C> current = resolved.get();
        if (current == null) {
            resolved.compareAndSet(null, new Resolved<>(resolve()));
            current = resolved.get();
        }
        return current.config();
    }

    private C resolve() {
        try {
            @SuppressWarnings("unchecked")
            C config = (C) ConfigCodec.parse(model, rawNamespace);
            return config;
        } catch (RuntimeException e) {
            log.warn("feature 中间件配置非法，该中间件本次装配不生效 agentKey={} middleware={} 原因={}",
                    agentKey, delegate.key(), e.getMessage());
            return null;
        }
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        Set<ExtensionPoint> points = delegate.activePoints();
        return points == null ? EnumSet.allOf(ExtensionPoint.class) : points;
    }

    @Override
    public int order() {
        return delegate.order();
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        C config = config();
        return config == null ? next.apply(input) : delegate.onAgent(config, agent, ctx, input, next);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        C config = config();
        return config == null ? next.apply(input) : delegate.onReasoning(config, agent, ctx, input, next);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        C config = config();
        return config == null ? next.apply(input) : delegate.onActing(config, agent, ctx, input, next);
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        C config = config();
        return config == null ? next.apply(input) : delegate.onModelCall(config, agent, ctx, input, next);
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        C config = config();
        return config == null ? Mono.just(currentPrompt) : delegate.onSystemPrompt(config, agent, ctx, currentPrompt);
    }

    @Override
    public void onAgentStateReady(Agent agent, RuntimeContext ctx, AgentState state, List<Msg> inputMessages) {
        C config = config();
        if (config != null) {
            delegate.onAgentStateReady(config, agent, ctx, state, inputMessages);
        }
    }
}
