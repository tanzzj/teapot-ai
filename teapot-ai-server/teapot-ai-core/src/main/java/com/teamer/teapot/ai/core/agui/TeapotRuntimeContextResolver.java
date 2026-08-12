package com.teamer.teapot.ai.core.agui;

import com.teamer.teapot.ai.rbac.context.ContextUtil;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.spring.boot.agui.common.AguiRuntimeContextRequest;
import io.agentscope.spring.boot.agui.common.AguiRuntimeContextResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AG-UI RuntimeContext 解析器（SPEC §6.2）：
 * 覆盖 starter 默认实现，把 RBAC 过滤器解析出的当前用户 + AG-UI threadId
 * 注入 RuntimeContext，实现 (userId, sessionId) 维度的状态隔离。
 * AgentscopeAguiMvcAutoConfiguration 通过 ObjectProvider 优先取本 Bean。
 */
@Slf4j
@Component
public class TeapotRuntimeContextResolver implements AguiRuntimeContextResolver {

    @Override
    public RuntimeContext resolve(AguiRuntimeContextRequest request) {
        String userId = ContextUtil.currentUserId();
        if (userId == null) {
            // /agui/** 经 RbacAuthFilter 鉴权，正常不会为空；兜底防御
            log.warn("AG-UI 请求缺少用户上下文 path={}", request.getPath());
            userId = "anonymous";
        }
        String sessionId = null;
        if (request.getInput() != null) {
            sessionId = request.getInput().getThreadId();
        }
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "default";
        }
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();
    }
}
