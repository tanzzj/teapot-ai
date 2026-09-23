package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.core.AgentBuilder;
import io.agentscope.extensions.a2ui.ClarificationMiddleware;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AgentRegistry（SPEC §6.1 平台核心组件）：
 * Web 链路入口，t_agent 记录 → HarnessAgent 实例；每轮对话均重新 build，不做缓存，
 * 配置/Skill 变更即时生效。装配规则统一在 AgentBuilder（channel 链路共享，SPEC §24.2）。
 * 无状态并发：同一装配规则服务全部用户，RuntimeContext(userId, sessionId) 隔离。
 */
@Slf4j
@Component
public class AgentRegistry {

    private final AgentBuilder agentBuilder;

    public AgentRegistry(AgentBuilder agentBuilder) {
        this.agentBuilder = agentBuilder;
    }

    /** 每次调用均重新构建（不走缓存，配置即时生效） */
    public HarnessAgent getOrCreate(String agentKey) {
        // ask_user_question（纯文本澄清档）仅挂 Web/AG-UI 链路：渠道无法渲染问题卡片，挂起将无人应答；
        // a2ui 开启时 A2uiMiddleware 在 extras 之后 rebind，同名注册表单档覆盖此澄清档
        return agentBuilder.assemble(agentKey, List.of(new ClarificationMiddleware()));
    }

    /** 实例不再缓存，无状态可失效；保留接口兼容 AgentService/SkillService 调用 */
    public void invalidate(String agentKey) {
        // no-op：getOrCreate 每次都会重新 build，配置变更自然即时生效
    }
}
