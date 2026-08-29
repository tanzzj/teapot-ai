package com.teamer.teapot.ai.core.agui;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.teamer.teapot.ai.core.service.AgentRuntimeHints;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import com.teamer.teapot.ai.rbac.service.JwtService;
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
 * 注：starter 在异步线程池回调 resolve，ContextUtil（ThreadLocal）不会传播，
 * 故兼容从请求头 Authorization 直接解析 JWT（§22.5）。
 * 同线程内后续才解析 agent（AguiRequestProcessor.process 顺序），因此本方法同时从
 * forwardedProps 提取请求级开关（memoryMode / planMode）写入 AgentRuntimeHints，
 * 由 AgentAssembler 装配时消费（SPEC §25：chat 界面参数传递）。
 */
@Slf4j
@Component
public class TeapotRuntimeContextResolver implements AguiRuntimeContextResolver {

    private final JwtService jwtService;

    public TeapotRuntimeContextResolver(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public RuntimeContext resolve(AguiRuntimeContextRequest request) {
        String userId = ContextUtil.currentUserId();
        if (userId == null) {
            // 异步线程无 ThreadLocal 上下文：从 Authorization 头补解析（/agui/** 已经过滤器验签，此处为取值）
            userId = resolveFromAuthHeader(request);
        }
        if (userId == null) {
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
        // 请求级开关（chat 界面 forwardedProps，SPEC §25）：
        // 未传参时清理旧值（线程池线程复用），装配回落 Agent 配置
        AgentRuntimeHints.setMemoryMode(parseBooleanProp(request, "memoryMode"));
        AgentRuntimeHints.setPlanMode(parseBooleanProp(request, "planMode"));
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();
    }

    /** forwardedProps 布尔开关通用解析：缺失/非法返回 null（跟随 Agent 配置） */
    private Boolean parseBooleanProp(AguiRuntimeContextRequest request, String key) {
        if (request.getInput() == null) {
            return null;
        }
        Object raw = request.getInput().getForwardedProp(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            if ("true".equalsIgnoreCase(s)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(s)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /** 从 Authorization: Bearer 头解析 uid；无效/缺失返回 null */
    private String resolveFromAuthHeader(AguiRuntimeContextRequest request) {
        String authorization = request.firstHeader("Authorization");
        if (authorization == null) {
            authorization = request.firstHeader("authorization");
        }
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        try {
            DecodedJWT jwt = jwtService.verify(authorization.substring(7));
            if (!JwtService.isType(jwt, JwtService.TYPE_ACCESS)) {
                return null;
            }
            return jwt.getClaim("uid").asString();
        } catch (Exception e) {
            log.debug("AG-UI Authorization 头解析失败 cause={}", e.getMessage());
            return null;
        }
    }
}
