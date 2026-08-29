package com.teamer.teapot.ai.core.agui;

import com.teamer.teapot.ai.core.dao.AgentMapper;
import com.teamer.teapot.ai.core.model.AgentDO;
import com.teamer.teapot.ai.core.service.AgentRegistry;
import com.teamer.teapot.ai.core.tool.AskUserQuestionTool;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AG-UI Agent 注册器（SPEC §6.2）：
 * 启动时把 t_agent 全部启用项以 registerFactory（惰性构建）注册进 AguiAgentRegistry；
 * Agent 增删/启停时由 AgentService 调用 register/unregister 保持同步。
 * AG-UI 端点按 X-Agent-Id（agentKey）路由到对应 HarnessAgent 实例。
 */
@Slf4j
@Component
public class TeapotAguiAgentRegistrar implements ApplicationRunner {

    private final AguiAgentRegistry aguiAgentRegistry;
    private final AgentMapper agentMapper;
    private final AgentRegistry agentRegistry;

    public TeapotAguiAgentRegistrar(AguiAgentRegistry aguiAgentRegistry,
                                    AgentMapper agentMapper,
                                    AgentRegistry agentRegistry) {
        this.aguiAgentRegistry = aguiAgentRegistry;
        this.agentMapper = agentMapper;
        this.agentRegistry = agentRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<AgentDO> enabled = agentMapper.selectAllEnabled();
        for (AgentDO agent : enabled) {
            register(agent.getAgentKey());
        }
        log.info("AG-UI Agent 注册完成，共 {} 个：{}", enabled.size(),
                enabled.stream().map(AgentDO::getAgentKey).toList());
    }

    /** 注册（惰性 supplier，首次 AG-UI 请求才构建 HarnessAgent 实例） */
    public void register(String agentKey) {
        aguiAgentRegistry.registerFactory(agentKey, () -> {
            HarnessAgent agent = agentRegistry.getOrCreate(agentKey);
            // ask_user_question 仅挂 Web/AG-UI 链路：渠道无法渲染选项卡片，挂起将无人应答
            agent.getToolkit().registerTool(new AskUserQuestionTool());
            return agent;
        });
    }

    /** 注销（软删/停用时调用） */
    public void unregister(String agentKey) {
        boolean removed = aguiAgentRegistry.unregister(agentKey);
        log.info("AG-UI Agent 注销 agentKey={} removed={}", agentKey, removed);
    }
}
