package com.teamer.teapot.ai.core.middleware.feature.impl;

import com.teamer.teapot.ai.core.middleware.feature.ConfigField;
import com.teamer.teapot.ai.core.middleware.feature.FeatureDrivenMiddleware;
import com.teamer.teapot.ai.core.service.ModelRegistry;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * 模型回退（SPEC §29.23）：一次模型调用失败时，换备用模型把<b>同一次请求</b>再发一遍。
 *
 * <p>只在错误上触发、只重试一次（回退那次再失败就原样抛给调用方），错误信息可按关键字限定，
 * 免得把"参数非法"这类确定性错误也拖成一次注定失败的重复调用。
 *
 * <p>回退目标走系统模型入口解析（不带 Agent 自带凭证），思考档不下发、跟随服务端默认。
 * 顺序刻意比限速中间件更靠外：回退的那一次同样要过限速，不会因为是重试就绕过间隔。
 */
@Slf4j
@Component
public class ModelFallbackMiddleware extends FeatureDrivenMiddleware<ModelFallbackMiddleware.Config> {

    /** 翻找异常链的层数上限（防御自引用链，正常供应商异常不会这么深） */
    private static final int CAUSE_DEPTH_LIMIT = 5;

    private final ModelRegistry modelRegistry;

    public ModelFallbackMiddleware(ModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    @Data
    public static class Config {

        @ConfigField(
                label = "回退模型",
                description = "主模型调用失败时改用的模型 id（provider:modelName 形式，须是系统模型入口；"
                        + "Agent 自带模型暂不能作为回退目标）",
                required = true)
        private String fallbackModelId;

        @ConfigField(
                label = "触发关键字",
                description = "逗号分隔，错误信息命中其一才回退；留空 = 任何模型调用错误都回退。"
                        + "例：429,throttling,timeout")
        private String errorContains;

        @ConfigField(
                label = "回退前等待（毫秒）",
                description = "给主模型一点恢复时间，0 = 立刻换模型重试",
                defaultValue = "0")
        private Integer backoffMs;
    }

    @Override
    public String description() {
        return "模型调用失败时换备用模型重试一次：可按错误关键字限定触发条件，回退前可先等待";
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_MODEL_CALL);
    }

    /** 高于默认 1：把自己套在限速等中间件外面，回退那次同样受限速约束 */
    @Override
    public int order() {
        return 5;
    }

    @Override
    protected Flux<AgentEvent> onModelCall(Config config, Agent agent, RuntimeContext ctx,
                                           ModelCallInput input,
                                           Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .onErrorResume(error -> retry(config, agent, ctx, input, next, error));
    }

    private Flux<AgentEvent> retry(Config config, Agent agent, RuntimeContext ctx, ModelCallInput input,
                                   Function<ModelCallInput, Flux<AgentEvent>> next, Throwable error) {
        if (!triggered(config, error)) {
            return Flux.error(error);
        }
        Model fallback = resolveFallback(config);
        if (fallback == null) {
            return Flux.error(error);
        }
        log.warn("模型调用失败已回退 agentKey={} 原模型={} 回退模型={} 原因={}",
                agent == null ? "-" : agent.getName(),
                input.model() == null ? "-" : input.model().getModelName(),
                fallback.getModelName(), message(error));
        long backoff = config.getBackoffMs() == null ? 0L : config.getBackoffMs();
        Mono<Void> wait = backoff > 0 ? Mono.delay(Duration.ofMillis(backoff)).then() : Mono.empty();
        return wait.thenMany(next.apply(
                new ModelCallInput(input.messages(), input.tools(), input.options(), fallback)));
    }

    /** 关键字为空 = 任何错误都回退；否则按小写包含匹配整条异常链的文本 */
    private boolean triggered(Config config, Throwable error) {
        String keywords = config.getErrorContains();
        if (keywords == null || keywords.isBlank()) {
            return true;
        }
        String haystack = message(error).toLowerCase(Locale.ROOT);
        for (String keyword : keywords.split(",")) {
            String needle = keyword.trim().toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private Model resolveFallback(Config config) {
        String modelId = config.getFallbackModelId();
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        try {
            return modelRegistry.resolve(modelId.trim(), null, null);
        } catch (RuntimeException e) {
            // 回退目标本身解析不出来（模型入口没登记 / 供应商没接密钥）：不掩盖原始错误
            log.warn("回退模型解析失败，本次不回退 fallbackModelId={} 原因={}", modelId, e.getMessage());
            return null;
        }
    }

    private static String message(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < CAUSE_DEPTH_LIMIT; depth++) {
            if (depth > 0) {
                text.append(' ');
            }
            text.append(current.getClass().getSimpleName()).append(':')
                    .append(current.getMessage() == null ? "" : current.getMessage());
            current = current.getCause() == current ? null : current.getCause();
        }
        return text.toString();
    }
}
