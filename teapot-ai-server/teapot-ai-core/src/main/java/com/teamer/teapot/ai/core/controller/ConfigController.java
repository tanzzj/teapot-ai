package com.teamer.teapot.ai.core.controller;

import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.config.AgentRunConnection;
import com.teamer.teapot.ai.core.config.ConfigCryptoService;
import com.teamer.teapot.ai.core.config.OssConnection;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.model.SandboxConfigDO;
import com.teamer.teapot.ai.core.model.StorageConfigDO;
import com.teamer.teapot.ai.core.service.SandboxConfigService;
import com.teamer.teapot.ai.core.service.StorageConfigService;
import com.teamer.teapot.ai.core.service.SysConfigService;
import com.teamer.teapot.ai.core.storage.ImageStorageRouter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统配置接口（SPEC §16.5.1/§16.11/§22）：AgentRun 全局凭证、OSS/沙箱连接记录。
 * 读 = developer（sandbox-options 与 *-record-names），写 = admin 独占（/api/config/* 由 admin /* 覆盖）。
 * GET 回显一律脱敏：configured 布尔 + 末 4 位掩码，任何接口不返回明文。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final SysConfigService sysConfigService;
    private final AgentRunConnection agentRunConnection;
    private final TeapotAiProperties properties;
    private final OssConnection ossConnection;
    private final ImageStorageRouter imageStorageRouter;
    private final StorageConfigService storageConfigService;
    private final SandboxConfigService sandboxConfigService;

    public ConfigController(SysConfigService sysConfigService,
                            AgentRunConnection agentRunConnection,
                            TeapotAiProperties properties,
                            OssConnection ossConnection,
                            ImageStorageRouter imageStorageRouter,
                            StorageConfigService storageConfigService,
                            SandboxConfigService sandboxConfigService) {
        this.sysConfigService = sysConfigService;
        this.agentRunConnection = agentRunConnection;
        this.properties = properties;
        this.ossConnection = ossConnection;
        this.imageStorageRouter = imageStorageRouter;
        this.storageConfigService = storageConfigService;
        this.sandboxConfigService = sandboxConfigService;
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

    /** 图片存储策略与 OSS 接入状态（SPEC §20.5）：effectiveStrategy 为前端上传链路选择依据 */
    @GetMapping("/storage-options")
    public Result<Map<String, Object>> storageOptions() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("strategy", imageStorageRouter.configuredStrategy());
        options.put("effectiveStrategy", imageStorageRouter.effectiveStrategy());
        options.put("ossEnabled", properties.getStorage().getOss().isEnabled());
        options.put("ossConfigured", ossConnection.configured());
        // 激活记录名（§20.12）：前端上传链路无需感知，仅管理台展示用
        options.put("active", ossConnection.getActiveName());
        options.put("region", ossConnection.getRegion());
        options.put("bucket", ossConnection.getBucket());
        options.put("endpoint", ossConnection.getEndpoint());
        options.put("customDomain", ossConnection.getCustomDomain());
        options.put("keyPrefix", ossConnection.getKeyPrefix(properties.getStorage().getOss().getKeyPrefix()));
        // 脱敏回显（同 sandbox-options）：只给掩码，不返回明文
        options.put("accessKeyIdMasked", ConfigCryptoService.mask(ossConnection.getAccessKeyId()));
        options.put("accessKeySecretMasked", ConfigCryptoService.mask(ossConnection.getAccessKeySecret()));
        return Result.ok(options);
    }

    /**
     * 写入图片存储策略与激活记录（SPEC §20.5/§20.12，仅 admin）：
     * strategy 落 t_sys_config；active 切换激活记录（记录凭证校验由 Service 负责）。
     * 兼容旧字段：若仍传 accessKeyId 等单键字段，写入旧 t_sys_config 键（三级解析兜底）。
     */
    @PutMapping("/storage")
    public Result<Map<String, Object>> updateStorage(@RequestBody Map<String, String> body) {
        putIfPresent(body, "strategy", OssConnection.KEY_STRATEGY, false);
        String active = body.get("active");
        if (active != null && !active.isBlank()) {
            storageConfigService.setActive(active.trim());
        }
        putIfPresent(body, "accessKeyId", OssConnection.KEY_ACCESS_KEY_ID, true);
        putIfPresent(body, "accessKeySecret", OssConnection.KEY_ACCESS_KEY_SECRET, true);
        putIfPresent(body, "region", OssConnection.KEY_REGION, false);
        putIfPresent(body, "bucket", OssConnection.KEY_BUCKET, false);
        putIfPresent(body, "endpoint", OssConnection.KEY_ENDPOINT, false);
        putIfPresent(body, "customDomain", OssConnection.KEY_CUSTOM_DOMAIN, false);
        putIfPresent(body, "keyPrefix", OssConnection.KEY_KEY_PREFIX, false);
        return storageOptions();
    }

    /** OSS 连接记录列表（SPEC §20.12，仅 admin）：回显脱敏，同 storage-options 规则 */
    @GetMapping("/storage-list")
    public Result<Map<String, Object>> storageList() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", storageConfigService.getActiveName());
        List<Map<String, Object>> records = new ArrayList<>();
        for (StorageConfigDO row : storageConfigService.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.getName());
            item.put("region", row.getRegion());
            item.put("bucket", row.getBucket());
            item.put("endpoint", row.getEndpoint());
            item.put("customDomain", row.getCustomDomain());
            item.put("keyPrefix", row.getKeyPrefix());
            item.put("remark", row.getRemark());
            // 列表行 AK/Secret 为密文，不适合做末 4 位掩码；只回布尔（§20.12）
            item.put("accessKeyConfigured",
                    row.getAccessKeyId() != null && !row.getAccessKeyId().isBlank()
                            && row.getAccessKeySecret() != null && !row.getAccessKeySecret().isBlank());
            item.put("updatedAt", row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
            records.add(item);
        }
        result.put("records", records);
        return Result.ok(result);
    }

    /** 新建 OSS 连接记录（§20.12，仅 admin）：AK/Secret 加密入库 */
    @PostMapping("/storage-record")
    public Result<Map<String, Object>> createStorageRecord(@RequestBody StorageConfigDO record) {
        storageConfigService.create(record);
        return storageList();
    }

    /** 更新 OSS 连接记录（§20.12，仅 admin）：AK/Secret 留空不修改 */
    @PutMapping("/storage-record")
    public Result<Map<String, Object>> updateStorageRecord(@RequestBody StorageConfigDO record) {
        storageConfigService.update(record);
        return storageList();
    }

    /** 删除 OSS 连接记录（§20.12，仅 admin）：激活中的记录禁止删除 */
    @DeleteMapping("/storage-record/{name}")
    public Result<Map<String, Object>> deleteStorageRecord(@PathVariable("name") String name) {
        storageConfigService.delete(name);
        return storageList();
    }

    /** OSS 记录轻量名单（§22.1）：仅名称/region/bucket，供 Agent 配置下拉选择（developer/viewer 可读） */
    @GetMapping("/storage-record-names")
    public Result<List<Map<String, Object>>> storageRecordNames() {
        List<Map<String, Object>> records = new ArrayList<>();
        for (StorageConfigDO row : storageConfigService.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.getName());
            item.put("region", row.getRegion());
            item.put("bucket", row.getBucket());
            records.add(item);
        }
        return Result.ok(records);
    }

    /** 沙箱连接记录列表（SPEC §22.2，仅 admin）：敏感列只回布尔，不回明文 */
    @GetMapping("/sandbox-list")
    public Result<Map<String, Object>> sandboxList() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> records = new ArrayList<>();
        for (SandboxConfigDO row : sandboxConfigService.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.getName());
            item.put("linkType", row.getLinkType());
            item.put("e2bApiBaseUrl", row.getE2bApiBaseUrl());
            item.put("e2bDomain", row.getE2bDomain());
            item.put("e2bDefaultTemplate", row.getE2bDefaultTemplate());
            item.put("region", row.getRegion());
            item.put("defaultTemplate", row.getDefaultTemplate());
            item.put("mcpServerUrl", row.getMcpServerUrl());
            item.put("remark", row.getRemark());
            item.put("e2bConfigured",
                    row.getE2bApiKey() != null && !row.getE2bApiKey().isBlank()
                            && row.getE2bApiBaseUrl() != null && !row.getE2bApiBaseUrl().isBlank()
                            && row.getE2bDomain() != null && !row.getE2bDomain().isBlank());
            item.put("agentrunConfigured",
                    row.getApiKey() != null && !row.getApiKey().isBlank()
                            && row.getAccountId() != null && !row.getAccountId().isBlank()
                            && row.getMcpServerUrl() != null && !row.getMcpServerUrl().isBlank());
            item.put("updatedAt", row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
            records.add(item);
        }
        result.put("records", records);
        return Result.ok(result);
    }

    /** 新建沙箱连接记录（§22.2，仅 admin）：敏感列加密入库 */
    @PostMapping("/sandbox-record")
    public Result<Map<String, Object>> createSandboxRecord(@RequestBody SandboxConfigDO record) {
        sandboxConfigService.create(record);
        return sandboxList();
    }

    /** 更新沙箱连接记录（§22.2，仅 admin）：敏感列留空不修改 */
    @PutMapping("/sandbox-record")
    public Result<Map<String, Object>> updateSandboxRecord(@RequestBody SandboxConfigDO record) {
        sandboxConfigService.update(record);
        return sandboxList();
    }

    /** 删除沙箱连接记录（§22.2，仅 admin） */
    @DeleteMapping("/sandbox-record/{name}")
    public Result<Map<String, Object>> deleteSandboxRecord(@PathVariable("name") String name) {
        sandboxConfigService.delete(name);
        return sandboxList();
    }

    /** 沙箱记录轻量名单（§22.2）：仅名称/linkType，供 Agent 配置下拉选择（developer/viewer 可读） */
    @GetMapping("/sandbox-record-names")
    public Result<List<Map<String, Object>>> sandboxRecordNames() {
        List<Map<String, Object>> records = new ArrayList<>();
        for (SandboxConfigDO row : sandboxConfigService.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.getName());
            item.put("linkType", row.getLinkType());
            records.add(item);
        }
        return Result.ok(records);
    }
}
