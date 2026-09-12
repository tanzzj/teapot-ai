package com.teamer.teapot.ai.core.sandbox.agentrun;

import com.teamer.teapot.ai.core.sandbox.SysConfigBackedConnection;
import com.teamer.teapot.ai.core.service.SysConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AgentRun（阿里云 MCP 链路）全局连接配置（SPEC §16.5）：env 优先、DB（t_sys_config）次之。
 * 无全局 enabled 开关；{@link #configured()} = apiKey/accountId/mcpServerUrl 三项齐备。
 * E2B 兼容链路见 {@link com.teamer.teapot.ai.core.sandbox.e2b.E2bConnection}。
 */
@Component
public class AgentRunConnection extends SysConfigBackedConnection {

    /** DB 配置 key（SPEC §16.5.1） */
    public static final String KEY_API_KEY = "agentrun.api_key";
    public static final String KEY_ACCOUNT_ID = "agentrun.account_id";
    public static final String KEY_REGION = "agentrun.region";
    public static final String KEY_MCP_URL = "agentrun.mcp_url";
    public static final String KEY_DEFAULT_TEMPLATE = "agentrun.default_template";

    /** 应急覆盖通道：env 存在时优先于 DB（SPEC §16.5） */
    private final String envApiKey;
    private final String envAccountId;
    private final String envRegion;
    private final String envMcpUrl;
    private final String envDefaultTemplate;

    public AgentRunConnection(SysConfigService sysConfigService,
                              @Value("${AGENTRUN_API_KEY:}") String envApiKey,
                              @Value("${ALIYUN_ACCOUNT_ID:}") String envAccountId,
                              @Value("${AGENTRUN_REGION:}") String envRegion,
                              @Value("${AGENTRUN_MCP_URL:}") String envMcpUrl,
                              @Value("${AGENTRUN_TEMPLATE:}") String envDefaultTemplate) {
        super(sysConfigService);
        this.envApiKey = envApiKey;
        this.envAccountId = envAccountId;
        this.envRegion = envRegion;
        this.envMcpUrl = envMcpUrl;
        this.envDefaultTemplate = envDefaultTemplate;
    }

    public String getApiKey() {
        return resolve(envApiKey, KEY_API_KEY);
    }

    public String getAccountId() {
        return resolve(envAccountId, KEY_ACCOUNT_ID);
    }

    public String getRegion() {
        return resolve(envRegion, KEY_REGION);
    }

    public String getMcpServerUrl() {
        return resolve(envMcpUrl, KEY_MCP_URL);
    }

    public String getDefaultTemplate() {
        return resolve(envDefaultTemplate, KEY_DEFAULT_TEMPLATE);
    }

    /** apiKey/accountId/mcpServerUrl 三项齐备（env 或 DB 合并后） */
    public boolean configured() {
        return notBlank(getApiKey()) && notBlank(getAccountId()) && notBlank(getMcpServerUrl());
    }
}
