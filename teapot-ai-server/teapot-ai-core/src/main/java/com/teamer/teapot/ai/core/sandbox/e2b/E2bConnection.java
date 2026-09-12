package com.teamer.teapot.ai.core.sandbox.e2b;

import com.teamer.teapot.ai.core.sandbox.SysConfigBackedConnection;
import com.teamer.teapot.ai.core.service.SysConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * E2B 兼容链路全局连接配置（SPEC §16 修订）：AgentRun 的 E2B 端点，CLI 实测
 * template list/spawn 均可用，配置后优先于 MCP 链路。env 优先、DB（t_sys_config）次之；
 * {@link #configured()} = apiKey/apiBaseUrl/domain 三项齐备。
 */
@Component
public class E2bConnection extends SysConfigBackedConnection {

    /** DB 配置 key（沿用历史 {@code e2b.*} 命名，已入库不可改） */
    public static final String KEY_API_KEY = "e2b.api_key";
    public static final String KEY_API_BASE_URL = "e2b.api_base_url";
    public static final String KEY_DOMAIN = "e2b.domain";
    public static final String KEY_DEFAULT_TEMPLATE = "e2b.default_template";

    /** 应急覆盖通道：env 存在时优先于 DB（SPEC §16.5） */
    private final String envApiKey;
    private final String envApiBaseUrl;
    private final String envDomain;
    private final String envDefaultTemplate;

    public E2bConnection(SysConfigService sysConfigService,
                         @Value("${E2B_API_KEY:}") String envApiKey,
                         @Value("${E2B_API_URL:}") String envApiBaseUrl,
                         @Value("${E2B_DOMAIN:}") String envDomain,
                         @Value("${E2B_TEMPLATE:}") String envDefaultTemplate) {
        super(sysConfigService);
        this.envApiKey = envApiKey;
        this.envApiBaseUrl = envApiBaseUrl;
        this.envDomain = envDomain;
        this.envDefaultTemplate = envDefaultTemplate;
    }

    public String getApiKey() {
        return resolve(envApiKey, KEY_API_KEY);
    }

    public String getApiBaseUrl() {
        return resolve(envApiBaseUrl, KEY_API_BASE_URL);
    }

    public String getDomain() {
        return resolve(envDomain, KEY_DOMAIN);
    }

    public String getDefaultTemplate() {
        return resolve(envDefaultTemplate, KEY_DEFAULT_TEMPLATE);
    }

    /** 三项齐备；配置后沙箱链路路由优先选 E2B */
    public boolean configured() {
        return notBlank(getApiKey()) && notBlank(getApiBaseUrl()) && notBlank(getDomain());
    }
}
