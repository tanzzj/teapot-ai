package com.teamer.teapot.ai.core.middleware.feature.impl;

import com.teamer.teapot.ai.core.middleware.feature.ConfigField;
import com.teamer.teapot.ai.core.middleware.feature.FeatureDrivenMiddleware;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ModelCallInput;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 模型调用限速（SPEC §29.23 的第一个使用者）：按 Agent 给两次模型调用之间强制拉开间隔，
 * 超出的请求排队等待而不是报错 —— 供应商侧的 QPS/并发限制是按时段计的，排队比失败划算。
 *
 * <p>限速位按 agentKey 记在单例里（键数量与 Agent 数同阶，不会随会话增长）；
 * 同 Agent 的并发调用共享同一个间隔，这正是"别把这一个 Agent 的请求堆到供应商脸上"的语义。
 */
@Slf4j
@Component
public class RateLimitMiddleware extends FeatureDrivenMiddleware<RateLimitMiddleware.Config> {

    /** agentKey → 上一次模型调用的实际发起时刻（毫秒） */
    private final Map<String, Long> lastCallAt = new ConcurrentHashMap<>();

    @Data
    public static class Config {

        @ConfigField(
                label = "最小调用间隔（毫秒）",
                description = "同一 Agent 两次模型调用之间至少拉开多久，超出的请求排队等待；0 = 不限速",
                defaultValue = "0")
        private Integer minIntervalMs;
    }

    @Override
    public String description() {
        return "按 Agent 限制模型调用频率：两次调用之间强制留出间隔，用于躲开供应商的 QPS 限制";
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_MODEL_CALL);
    }

    @Override
    protected Flux<AgentEvent> onModelCall(Config config, Agent agent, RuntimeContext ctx,
                                           ModelCallInput input,
                                           Function<ModelCallInput, Flux<AgentEvent>> next) {
        Integer interval = config.getMinIntervalMs();
        String key = agent == null ? null : agent.getName();
        if (interval == null || interval <= 0 || key == null || key.isBlank()) {
            // 认不出是哪个 Agent 就不限速：把无名流量挤进同一个桶会让互不相干的调用互相拖住
            return next.apply(input);
        }
        long now = System.currentTimeMillis();
        long wait = interval - (now - lastCallAt.getOrDefault(key, 0L));
        if (wait <= 0) {
            lastCallAt.put(key, now);
            return next.apply(input);
        }
        log.debug("模型调用限速等待 agentKey={} waitMs={}", key, wait);
        // 时刻在真正订阅下游时才落，等待期间到达的其它调用会排到更后面
        return Mono.delay(Duration.ofMillis(wait))
                .doOnNext(tick -> lastCallAt.put(key, System.currentTimeMillis()))
                .thenMany(next.apply(input));
    }
}
