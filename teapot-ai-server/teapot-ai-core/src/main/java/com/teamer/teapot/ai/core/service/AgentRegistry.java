package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.core.AgentBuilder;
import com.teamer.teapot.ai.core.middleware.SingleUseAgentCloseMiddleware;
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
 * 「不缓存」的代价由本类负责偿还：每轮新建的实例在轮末由 SingleUseAgentCloseMiddleware
 * 关闭回收（channel 链路的实例长生命周期，不挂该中间件）。
 */
@Slf4j
@Component
public class AgentRegistry {

    private final AgentBuilder agentBuilder;

    public AgentRegistry(AgentBuilder agentBuilder) {
        this.agentBuilder = agentBuilder;
    }

    /** 每次调用均重新构建（不走缓存，配置即时生效），并挂轮末回收 */
    public HarnessAgent getOrCreate(String agentKey) {
        // 每轮重建 → 轮末必须回收，否则实例被 GracefulShutdownManager 以实例 UUID 为键永久持有
        // （线上 1.7GB 堆的成因，细节见 SingleUseAgentCloseMiddleware）。
        // 必须排在 extras 首位 = onAgent 链最外层，保证 close 晚于其它中间件收尾与状态落盘
        SingleUseAgentCloseMiddleware recycle = new SingleUseAgentCloseMiddleware(agentKey);
        // ask_user_question（纯文本澄清档）仅挂 Web/AG-UI 链路：渠道无法渲染问题卡片，挂起将无人应答；
        // a2ui 开启时由 AgentBuilder 在 build 后按挂载顺序 installTools，同名注册表单档覆盖此澄清档
        HarnessAgent agent =
                agentBuilder.assemble(agentKey, List.of(recycle, new ClarificationMiddleware()));
        recycle.bind(agent);
        return agent;
    }

    /** 实例不再缓存，无状态可失效；保留接口兼容 AgentService/SkillService 调用 */
    public void invalidate(String agentKey) {
        // no-op：getOrCreate 每次都会重新 build，配置变更自然即时生效
    }
}
