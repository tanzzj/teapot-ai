package com.teamer.teapot.ai.core.sandbox;

import com.teamer.teapot.ai.core.sandbox.agentrun.AgentRunConnection;
import com.teamer.teapot.ai.core.sandbox.e2b.E2bConnection;
import org.springframework.stereotype.Component;

/**
 * 沙箱双链路可用性汇总（SPEC §16.5 修订）：任一链路凭证齐备即视为已接入（前端门控）。
 * 实际走哪条链路由全局 {@code sandbox.link} 与 Agent 级 {@code feature.sandbox.link}
 * 在 {@link com.teamer.teapot.ai.core.AgentBuilder} 里路由。
 */
@Component
public class SandboxConnectivity {

    private final AgentRunConnection agentRunConnection;
    private final E2bConnection e2bConnection;

    public SandboxConnectivity(AgentRunConnection agentRunConnection, E2bConnection e2bConnection) {
        this.agentRunConnection = agentRunConnection;
        this.e2bConnection = e2bConnection;
    }

    public boolean anyConfigured() {
        return e2bConnection.configured() || agentRunConnection.configured();
    }
}
