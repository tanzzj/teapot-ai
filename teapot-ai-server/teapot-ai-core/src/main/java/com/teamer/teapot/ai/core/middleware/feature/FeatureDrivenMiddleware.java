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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * feature 驱动中间件（SPEC §29.23）：中间件本体是单例 Bean（无 Agent 状态），
 * 每个 Agent 的差异全部外置到 {@code t_agent.feature} 的 {@code middleware_<key>} 命名空间里，
 * 配置类由子类用泛型参数声明，字段用 {@link ConfigField} 描述 —— schema 与解析规则同源。
 *
 * <p>两层回调：
 * <ul>
 *   <li><b>框架层</b>（{@link MiddlewareBase} 的 6 个钩子）由 {@link FeatureMiddlewareBinding}
 *       在每次装配时实现：取本次装配的 feature 快照 → 懒解析配置 → 派发到业务回调；
 *       未配置或配置非法即原样放行（fail-open），不让一个中间件的配置问题拖死整轮对话。</li>
 *   <li><b>业务层</b>（下面这批 {@code onXxx(config, ...)}）由子类按需覆写，
 *       进来时 config 一定非 null，只写业务判断。</li>
 * </ul>
 *
 * <p>子类命名必须以 {@code Middleware} 结尾，key 由类名去掉该后缀并首字母小写得到
 * （{@code FooBarMiddleware} → {@code fooBar} → 命名空间 {@code middleware_fooBar}）。
 *
 * @param <C> 配置类型；配置类或 {@code List<配置类>}
 */
public abstract class FeatureDrivenMiddleware<C> implements MiddlewareBase {

    /** feature 里配置命名空间的前缀 */
    public static final String NS_PREFIX = "middleware_";

    /** 历史命名空间后缀（早期实现用 {@code middleware_<key>Hook}，仍认） */
    private static final String LEGACY_NS_SUFFIX = "Hook";

    private static final String CLASS_SUFFIX = "Middleware";

    /** 一句话说清这个中间件做什么（进 schema 出参，界面直接展示）；空白在注册表启动时即失败 */
    public abstract String description();

    /** 配置命名空间的 key，默认由类名派生 */
    public String key() {
        return deriveKey(getClass());
    }

    /** false = 不进注册表出参、界面不可见，但装配照跑（用于纯内部中间件） */
    public boolean forFrontendDisplay() {
        return true;
    }

    /**
     * 适用 Agent 白名单（agentKey）：空 = 全平台可见；非空 = 只有列出的 Agent 在 schema
     * 与配置查询里看得到它。装配不受此影响，仍以 {@link #accept} 为准。
     */
    public Set<String> appliedAgents() {
        return Set.of();
    }

    /** 本次装配是否挂载该中间件；默认口径 = feature 里存在非空的配置命名空间 */
    public boolean accept(String agentKey, Map<String, Object> namespaces) {
        Object raw = configNamespace(key(), namespaces);
        if (raw == null) {
            return false;
        }
        if (raw instanceof String text) {
            return !text.isBlank();
        }
        if (raw instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (raw instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    /** 取该 key 的配置命名空间值（先认 {@code middleware_<key>}，再认历史 {@code ...Hook}） */
    public static Object configNamespace(String key, Map<String, Object> namespaces) {
        if (namespaces == null || key == null) {
            return null;
        }
        Object raw = namespaces.get(NS_PREFIX + key);
        return raw != null ? raw : namespaces.get(NS_PREFIX + key + LEGACY_NS_SUFFIX);
    }

    /** 类名 → key；不以 Middleware 结尾的直接失败（key 要可预测，不能靠兜底猜测） */
    static String deriveKey(Class<?> type) {
        String simple = type.getSimpleName();
        if (!simple.endsWith(CLASS_SUFFIX) || simple.length() <= CLASS_SUFFIX.length()) {
            throw new IllegalStateException("feature 驱动中间件的类名必须以 " + CLASS_SUFFIX + " 结尾：" + type.getName());
        }
        String base = simple.substring(0, simple.length() - CLASS_SUFFIX.length());
        return Character.toLowerCase(base.charAt(0)) + base.substring(1);
    }

    // ---- 业务回调：config 非 null，按需覆写；默认实现即「什么都不做，原样放行」 ----

    protected Flux<AgentEvent> onAgent(C config, Agent agent, RuntimeContext ctx, AgentInput input,
                                       Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    protected Flux<AgentEvent> onReasoning(C config, Agent agent, RuntimeContext ctx, ReasoningInput input,
                                           Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    protected Flux<AgentEvent> onActing(C config, Agent agent, RuntimeContext ctx, ActingInput input,
                                        Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    protected Flux<AgentEvent> onModelCall(C config, Agent agent, RuntimeContext ctx, ModelCallInput input,
                                           Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    protected Mono<String> onSystemPrompt(C config, Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.just(currentPrompt);
    }

    protected void onAgentStateReady(C config, Agent agent, RuntimeContext ctx, AgentState state,
                                     List<Msg> inputMessages) {
    }
}
