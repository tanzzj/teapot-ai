package com.teamer.teapot.ai.core.controller;

import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.config.AgentRunConnection;
import com.teamer.teapot.ai.core.config.ConfigCryptoService;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.service.SysConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统配置接口（SPEC §16.5.1/§16.11）：AgentRun 全局接入凭证与选项。
 * 读 = developer（sandbox-options），写 = admin 独占（/api/config/* 由 admin /* 覆盖）。
 * GET 回显一律脱敏：configured 布尔 + 末 4 位掩码，任何接口不返回明文。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final SysConfigService sysConfigService;
    private final AgentRunConnection agentRunConnection;
    private final TeapotAiProperties properties;

    public ConfigController(SysConfigService sysConfigService,
                            AgentRunConnection agentRunConnection,
                            TeapotAiProperties properties) {
        this.sysConfigService = sysConfigService;
        this.agentRunConnection = agentRunConnection;
        this.properties = properties;
    }

    /** 沙箱选项与接入状态（SPEC §16.11）：configured=false 时前端禁用启用开关 */
    @GetMapping("/sandbox-options")
    public Result<Map<String, Object>> sandboxOptions() {
        Map<String, Object> options = new LinkedHashMap<>();
        // 任一链路可用即视为已接入；实际链路由 sandbox.link 配置路由（AgentRegistry）
        options.put("configured", agentRunConnection.anyConfigured());
        options.put("e2bConfigured", agentRunConnection.e2bConfigured());
        // 双链路配置项（sandbox.link / enabled，SPEC §16.5 修订）
        options.put("link", properties.getSandbox().getLink());
        options.put("e2bEnabled", properties.getSandbox().getE2b().isEnabled());
        options.put("agentrunEnabled", properties.getSandbox().getAgentrun().isEnabled());
        options.put("region", agentRunConnection.getRegion());
        options.put("defaultTemplate", agentRunConnection.getDefaultTemplate());
        options.put("defaultWorkspaceRoot",
                properties.getSandbox().getAgentrun().getDefaultWorkspaceRoot());
        options.put("defaultIdleTimeoutSeconds",
                properties.getSandbox().getAgentrun().getDefaultIdleTimeoutSeconds());
        // 脱敏回显（SPEC §16.5.1）：只给掩码，不返回明文
        options.put("apiKeyMasked", ConfigCryptoService.mask(agentRunConnection.getApiKey()));
        options.put("accountIdMasked", ConfigCryptoService.mask(agentRunConnection.getAccountId()));
        options.put("mcpServerUrl", agentRunConnection.getMcpServerUrl());
        options.put("e2bApiKeyMasked", ConfigCryptoService.mask(agentRunConnection.getE2bApiKey()));
        options.put("e2bApiBaseUrl", agentRunConnection.getE2bApiBaseUrl());
        options.put("e2bDomain", agentRunConnection.getE2bDomain());
        options.put("e2bDefaultTemplate", agentRunConnection.getE2bDefaultTemplate());
        return Result.ok(options);
    }

    /**
     * 写入全局接入凭证（SPEC §16.5.1，仅 admin）：
     * apiKey/accountId AES-GCM 加密入库；region/mcpServerUrl/defaultTemplate 明文。
     * 只更新非 null 字段。
     */
    @PutMapping("/sandbox")
    public Result<Map<String, Object>> updateSandbox(@RequestBody Map<String, String> body) {
        putIfPresent(body, "apiKey", AgentRunConnection.KEY_API_KEY, true);
        putIfPresent(body, "accountId", AgentRunConnection.KEY_ACCOUNT_ID, true);
        putIfPresent(body, "region", AgentRunConnection.KEY_REGION, false);
        putIfPresent(body, "mcpServerUrl", AgentRunConnection.KEY_MCP_URL, false);
        putIfPresent(body, "defaultTemplate", AgentRunConnection.KEY_DEFAULT_TEMPLATE, false);
        // E2B 兼容链路（apiKey 加密入库，其余明文）
        putIfPresent(body, "e2bApiKey", AgentRunConnection.KEY_E2B_API_KEY, true);
        putIfPresent(body, "e2bApiBaseUrl", AgentRunConnection.KEY_E2B_API_BASE_URL, false);
        putIfPresent(body, "e2bDomain", AgentRunConnection.KEY_E2B_DOMAIN, false);
        putIfPresent(body, "e2bDefaultTemplate", AgentRunConnection.KEY_E2B_DEFAULT_TEMPLATE, false);
        return sandboxOptions();
    }

    private void putIfPresent(Map<String, String> body, String field, String configKey, boolean encrypt) {
        String value = body.get(field);
        if (value != null && !value.isBlank()) {
            sysConfigService.set(configKey, value.trim(), encrypt);
        }
    }
}
