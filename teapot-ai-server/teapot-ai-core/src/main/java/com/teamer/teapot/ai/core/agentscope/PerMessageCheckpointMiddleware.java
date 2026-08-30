package com.teamer.teapot.ai.core.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * 消息级落盘中间件（SPEC-checkpoint-persist §3.1）：
 * 在 onReasoning / onActing 阶段入口（此时上一阶段产物已全部入 context）强制落盘，
 * 使每条消息在产生的下一个阶段入口即持久化，缓解「轮进行中刷新/重启丢整轮消息」问题。
 * 轮末既有落盘保留不动（叠加而非替换）。
 */
@Slf4j
public class PerMessageCheckpointMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        checkpoint(agent, ctx);
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        checkpoint(agent, ctx);
        return next.apply(input);
    }

    /**
     * 阶段入口落盘：中间件收到的 agent 为内部 ReActAgent（MiddlewareChain.build 传 ReActAgent.this），
     * 走公开 saveAgentState(ctx)（与轮末落盘共享版本缓存的 CAS 落盘）。
     * 落盘失败仅告警，绝不打断对话流。
     */
    private void checkpoint(Agent agent, RuntimeContext ctx) {
        try {
            if (agent instanceof ReActAgent ra) {
                ra.saveAgentState(ctx);
            }
        } catch (Exception e) {
            log.warn("Checkpoint 落盘失败（不中断对话）sessionId={} err={}",
                    ctx == null ? "-" : ctx.getSessionId(), e.toString());
        }
    }
}
