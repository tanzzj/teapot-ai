package com.teamer.teapot.ai.core.service;

import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AgentRegistry（SPEC §6.1 平台核心组件）：
 * Web 链路入口，t_agent 记录 → HarnessAgent 实例；每轮对话均重新 build，不做缓存，
 * 配置/Skill 变更即时生效。装配规则统一在 AgentAssembler（channel 链路共享，SPEC §24.2）。
 * 无状态并发：同一装配规则服务全部用户，RuntimeContext(userId, sessionId) 隔离。
 */
@Slf4j
@Component
public class AgentRegistry {

    private final AgentAssembler agentAssembler;

    public AgentRegistry(AgentAssembler agentAssembler) {
        this.agentAssembler = agentAssembler;
    }

    /** 每次调用均重新构建（不走缓存，配置即时生效） */
    public HarnessAgent getOrCreate(String agentKey) {
        return agentAssembler.assemble(agentKey, List.of());
    }

    /** 实例不再缓存，无状态可失效；保留接口兼容 AgentService/SkillService 调用 */
    public void invalidate(String agentKey) {
        // no-op：getOrCreate 每次都会重新 build，配置变更自然即时生效
    }
}
