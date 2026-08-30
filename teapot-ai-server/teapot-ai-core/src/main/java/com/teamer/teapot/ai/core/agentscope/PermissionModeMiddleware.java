package com.teamer.teapot.ai.core.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.permission.PermissionContextState;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * 权限模式中间件（permission-system 落地）：每轮调用入口把生效的权限上下文写入会话槽位。
 * <p>
 * onAgent 前置于框架的槽位激活（beforeAgentExecution → activateSlotForContext 在 core 内、
 * 本中间件之后执行）：此处 replacePermissionContext 落库后，槽位激活重新读取到的
 * permission_context 即本轮生效模式，实现「每次请求按 生效模式 收敛」，
 * 避免上一轮持久化的模式残留压过本轮意图。应用失败仅告警，不打断对话。
 */
@Slf4j
public class PermissionModeMiddleware implements MiddlewareBase {

    private final PermissionContextState permissionContext;

    public PermissionModeMiddleware(PermissionContextState permissionContext) {
        this.permissionContext = permissionContext;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        try {
            if (agent instanceof ReActAgent ra) {
                ra.replacePermissionContext(ctx.getUserId(), ctx.getSessionId(), permissionContext);
            }
        } catch (Exception e) {
            log.warn("权限模式应用失败（不中断对话）sessionId={} err={}",
                    ctx == null ? "-" : ctx.getSessionId(), e.toString());
        }
        return next.apply(input);
    }
}
