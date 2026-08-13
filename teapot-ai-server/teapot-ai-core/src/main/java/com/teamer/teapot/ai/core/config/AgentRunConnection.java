package com.teamer.teapot.ai.core.config;

import com.teamer.teapot.ai.core.service.SysConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AgentRun 全局连接配置（SPEC §16.5）：env 优先、DB（t_sys_config）次之。
 * 无全局 enabled 开关；{@link #configured()} = apiKey/accountId/mcpServerUrl 三项齐备。
 * E2B 兼容链路（§16 修订）：AgentRun 的 E2B 端点已验证可用，配置后优先于 MCP 链路；
 * {@link #e2bConfigured()} = e2bApiKey/e2bApiBaseUrl/e2bDomain 三项齐备。
 */
@Slf4j
@Component
public class AgentRunConnection {

    /** DB 配置 key（SPEC §16.5.1） */
    public static final String KEY_API_KEY = "agentrun.api_key";
    public static final String KEY_ACCOUNT_ID = "agentrun.account_id";
    public static final String KEY_REGION = "agentrun.region";
    public static final String KEY_MCP_URL = "agentrun.mcp_url";
    public static final String KEY_DEFAULT_TEMPLATE = "agentrun.default_template";
    /** E2B 兼容链路 DB 配置 key */
    public static final String KEY_E2B_API_KEY = "e2b.api_key";
    public static final String KEY_E2B_API_BASE_URL = "e2b.api_base_url";
    public static final String KEY_E2B_DOMAIN = "e2b.domain";
    public static final String KEY_E2B_DEFAULT_TEMPLATE = "e2b.default_template";

    private final SysConfigService sysConfigService;
    /** 应急覆盖通道：env 存在时优先于 DB（SPEC §16.5） */
    private final String envApiKey;
    private final String envAccountId;
    private final String envRegion;
    private final String envMcpUrl;
    private final String envDefaultTemplate;
    private final String envE2bApiKey;
    private final String envE2bApiBaseUrl;
    private final String envE2bDomain;
    private final String envE2bDefaultTemplate;

    public AgentRunConnection(SysConfigService sysConfigService,
                              @Value("${AGENTRUN_API_KEY:}") String envApiKey,
                              @Value("${ALIYUN_ACCOUNT_ID:}") String envAccountId,
                              @Value("${AGENTRUN_REGION:}") String envRegion,
                              @Value("${AGENTRUN_MCP_URL:}") String envMcpUrl,
                              @Value("${AGENTRUN_TEMPLATE:}") String envDefaultTemplate,
                              @Value("${E2B_API_KEY:}") String envE2bApiKey,
                              @Value("${E2B_API_URL:}") String envE2bApiBaseUrl,
                              @Value("${E2B_DOMAIN:}") String envE2bDomain,
                              @Value("${E2B_TEMPLATE:}") String envE2bDefaultTemplate) {
        this.sysConfigService = sysConfigService;
        this.envApiKey = envApiKey;
        this.envAccountId = envAccountId;
        this.envRegion = envRegion;
        this.envMcpUrl = envMcpUrl;
        this.envDefaultTemplate = envDefaultTemplate;
        this.envE2bApiKey = envE2bApiKey;
        this.envE2bApiBaseUrl = envE2bApiBaseUrl;
        this.envE2bDomain = envE2bDomain;
        this.envE2bDefaultTemplate = envE2bDefaultTemplate;
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

    public String getE2bApiKey() {
        return resolve(envE2bApiKey, KEY_E2B_API_KEY);
    }

    public String getE2bApiBaseUrl() {
        return resolve(envE2bApiBaseUrl, KEY_E2B_API_BASE_URL);
    }

    public String getE2bDomain() {
        return resolve(envE2bDomain, KEY_E2B_DOMAIN);
    }

    public String getE2bDefaultTemplate() {
        return resolve(envE2bDefaultTemplate, KEY_E2B_DEFAULT_TEMPLATE);
    }

    /** apiKey/accountId/mcpServerUrl 三项齐备（env 或 DB 合并后） */
    public boolean configured() {
        return notBlank(getApiKey()) && notBlank(getAccountId()) && notBlank(getMcpServerUrl());
    }

    /** E2B 兼容链路三项齐备；配置后 AgentRegistry 优先走此链路 */
    public boolean e2bConfigured() {
        return notBlank(getE2bApiKey()) && notBlank(getE2bApiBaseUrl()) && notBlank(getE2bDomain());
    }

    /** 任一链路可用（前端 configured 门控） */
    public boolean anyConfigured() {
        return e2bConfigured() || configured();
    }

    private String resolve(String envValue, String dbKey) {
        if (notBlank(envValue)) {
            return envValue.trim();
        }
        try {
            String dbValue = sysConfigService.getPlain(dbKey);
            return notBlank(dbValue) ? dbValue.trim() : null;
        } catch (Exception e) {
            // 解密失败（主密钥变更等）不拖垮 Agent 构建，按未配置处理
            log.warn("读取系统配置失败 key={}", dbKey, e);
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
