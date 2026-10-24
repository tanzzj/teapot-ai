package com.teamer.teapot.ai.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.audit.AuditService;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.dao.ModelEntryMapper;
import com.teamer.teapot.ai.core.model.ModelEntryDO;
import com.teamer.teapot.ai.core.model.ModelWorkspaceGrantDO;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import com.teamer.teapot.ai.rbac.model.TeapotUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型入口管理（SPEC §6.4 修订：模型入口界面配置化，替代 yml 白名单）。
 * 写操作仅 admin；变更后同步失效 ModelRegistry 实例缓存；API Key 不入库。
 */
@Slf4j
@Service
public class ModelService {

    private static final Set<String> PROVIDERS = Set.of("dashscope", "openai");
    /** 能力位白名单（SPEC §19；一期界面仅开放 image） */
    private static final Set<String> CAPABILITIES = Set.of("image", "audio", "video");

    private final ModelEntryMapper modelEntryMapper;
    private final ModelRegistry modelRegistry;
    private final AuditService auditService;
    private final TeapotAiProperties properties;

    @Value("${DASHSCOPE_API_KEY:}")
    private String dashscopeApiKey;

    /** DashScope 模型清单缓存（列表庞大且变动低频，10 分钟 TTL） */
    private volatile List<String> vendorModelsCache;
    private volatile long vendorModelsCacheAt;
    private static final long VENDOR_MODELS_TTL_MS = 10 * 60 * 1000L;
    private static final String DASHSCOPE_MODELS_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/models";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public ModelService(ModelEntryMapper modelEntryMapper, ModelRegistry modelRegistry,
                        AuditService auditService, TeapotAiProperties properties) {
        this.modelEntryMapper = modelEntryMapper;
        this.modelRegistry = modelRegistry;
        this.auditService = auditService;
        this.properties = properties;
    }

    /** Agent 下拉枚举：DB 启用入口优先；DB 为空时兜底 yml 预设（平滑迁移） */
    public List<String> listEnabledModelIds() {
        List<String> ids = modelEntryMapper.selectAllEnabled().stream()
                .map(ModelEntryDO::modelId).toList();
        if (ids.isEmpty()) {
            return properties.getModelPresets();
        }
        return ids;
    }

    /**
     * Agent 下拉枚举（按空间过滤，SPEC §28.11）：workspaceId 为空间桶（0=全部/个人，>0=团队）。
     * 命中「该桶或全局 0 桶」授权的启用入口；不回落 yml（空集即「该空间无可用模型」，deny-by-default）。
     * workspaceId 为 null 表示无空间上下文，退回不过滤的全量枚举（向后兼容）。
     */
    public List<String> listEnabledModelIds(Long workspaceId) {
        if (workspaceId == null) {
            return listEnabledModelIds();
        }
        return modelEntryMapper.selectEnabledForWorkspace(workspaceId).stream()
                .map(ModelEntryDO::modelId).toList();
    }

    public List<ModelEntryDO> listAll() {
        requireAdmin();
        List<ModelEntryDO> all = modelEntryMapper.selectAll();
        Map<Long, List<Long>> grantsByEntry = new HashMap<>();
        for (ModelWorkspaceGrantDO grant : modelEntryMapper.selectAllGrants()) {
            grantsByEntry.computeIfAbsent(grant.getModelEntryId(), k -> new ArrayList<>())
                    .add(grant.getWorkspaceId());
        }
        for (ModelEntryDO entry : all) {
            entry.setWorkspaceIds(grantsByEntry.getOrDefault(entry.getId(), List.of()));
        }
        return all;
    }

    /** 启用入口的能力位（SPEC §19 前端 gating，任意登录用户可读，不含密钥） */
    public List<ModelEntryDO> listEnabledCapabilities() {
        return modelEntryMapper.selectAllEnabled();
    }

    /**
     * DashScope 在售模型清单（新建/编辑模型入口的下拉数据源）。
     * 服务器 DASHSCOPE_API_KEY 代拉，密钥不出后端；仅保留 qwen 系 chat 模型；10 分钟缓存。
     */
    public List<String> listDashScopeVendorModels() {
        requireAdmin();
        List<String> cached = vendorModelsCache;
        if (cached != null && System.currentTimeMillis() - vendorModelsCacheAt < VENDOR_MODELS_TTL_MS) {
            return cached;
        }
        if (dashscopeApiKey.isBlank()) {
            throw new BizException("未配置 DASHSCOPE_API_KEY，无法拉取模型清单");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(DASHSCOPE_MODELS_URL))
                    .header("Authorization", "Bearer " + dashscopeApiKey)
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new BizException("DashScope 模型清单拉取失败：HTTP " + resp.statusCode());
            }
            JsonNode data = JSON.readTree(resp.body()).path("data");
            List<String> ids = new ArrayList<>();
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                String lower = id.toLowerCase();
                // 只留 qwen 系；排除非文本对话模型（图像生成 / ASR / 实时语音）
                if (id.isBlank() || !lower.startsWith("qwen")) {
                    continue;
                }
                if (lower.contains("image") || lower.contains("asr") || lower.contains("realtime")) {
                    continue;
                }
                ids.add(id);
            }
            ids.sort(Comparator.naturalOrder());
            vendorModelsCache = ids;
            vendorModelsCacheAt = System.currentTimeMillis();
            return ids;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("DashScope 模型清单拉取异常", e);
            throw new BizException("DashScope 模型清单拉取失败：" + e.getMessage());
        }
    }

    public ModelEntryDO create(ModelEntryDO request) {
        requireAdmin();
        validate(request);
        ModelEntryDO existed = modelEntryMapper.selectByModelId(request.getProvider(), request.getModelName());
        if (existed != null) {
            throw new BizException("模型入口已存在：" + request.modelId());
        }
        request.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        request.setCapabilities(normalizeCapabilities(request.getCapabilities()));
        request.setCreatedBy(ContextUtil.currentUserId());
        modelEntryMapper.insert(request);
        saveGrants(request.getId(), request.getWorkspaceIds());
        modelRegistry.evict(request.modelId());
        auditService.log("model.create", request.modelId(), "baseUrl=" + request.getBaseUrl()
                + ", capabilities=" + request.getCapabilities());
        return request;
    }

    public ModelEntryDO update(Long id, ModelEntryDO request) {
        requireAdmin();
        ModelEntryDO existed = modelEntryMapper.selectById(id);
        if (existed == null) {
            throw new BizException("模型入口不存在：id=" + id);
        }
        String oldModelId = existed.modelId();
        if (request.getProvider() != null) {
            existed.setProvider(request.getProvider());
        }
        if (request.getModelName() != null) {
            existed.setModelName(request.getModelName());
        }
        validate(existed);
        // 唯一性校验（排除自身）
        ModelEntryDO dup = modelEntryMapper.selectByModelId(existed.getProvider(), existed.getModelName());
        if (dup != null && !dup.getId().equals(id)) {
            throw new BizException("模型入口已存在：" + existed.modelId());
        }
        // 显式传 null 表示清空展示名/baseUrl/能力位
        existed.setDisplayName(request.getDisplayName());
        existed.setBaseUrl(request.getBaseUrl());
        existed.setCapabilities(normalizeCapabilities(request.getCapabilities()));
        if (request.getStatus() != null) {
            existed.setStatus(request.getStatus());
        }
        modelEntryMapper.update(existed);
        if (request.getWorkspaceIds() != null) {
            saveGrants(id, request.getWorkspaceIds());
        }
        modelRegistry.evict(oldModelId);
        modelRegistry.evict(existed.modelId());
        auditService.log("model.update", existed.modelId(), "baseUrl=" + existed.getBaseUrl()
                + ", capabilities=" + existed.getCapabilities());
        return existed;
    }

    public void delete(Long id) {
        requireAdmin();
        ModelEntryDO existed = modelEntryMapper.selectById(id);
        if (existed == null) {
            throw new BizException("模型入口不存在：id=" + id);
        }
        modelEntryMapper.deleteGrants(id);
        modelEntryMapper.deleteById(id);
        modelRegistry.evict(existed.modelId());
        auditService.log("model.delete", existed.modelId(), null);
    }

    private void validate(ModelEntryDO entry) {
        if (entry.getProvider() == null || !PROVIDERS.contains(entry.getProvider())) {
            throw new BizException("供应商仅支持：" + String.join("/", PROVIDERS));
        }
        if (entry.getModelName() == null || entry.getModelName().isBlank()) {
            throw new BizException("模型名不能为空");
        }
        if (entry.getModelName().length() > 64) {
            throw new BizException("模型名长度不超过 64 位");
        }
    }

    /** 能力位规范化：去空白、校验白名单、去重；null/空 → null（纯文本） */
    private String normalizeCapabilities(String capabilities) {
        if (capabilities == null || capabilities.isBlank()) {
            return null;
        }
        List<String> parts = Arrays.stream(capabilities.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        for (String part : parts) {
            if (!CAPABILITIES.contains(part)) {
                throw new BizException("能力位仅支持：" + String.join("/", CAPABILITIES));
            }
        }
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    /**
     * 校验模型是否开放给该空间桶（SPEC §28.11，deny-by-default）：
     * 未登记或既未授权该桶也未授权全局 0 桶，即拒绝。Agent 建/改时调用（白名单在调用方放行）。
     */
    public void assertOpenToWorkspace(String modelId, long bucket) {
        if (modelId == null || modelId.isBlank()) {
            throw new BizException("模型不能为空");
        }
        int idx = modelId.indexOf(':');
        if (idx <= 0 || idx == modelId.length() - 1) {
            throw new BizException("模型标识形如 provider:model：" + modelId);
        }
        ModelEntryDO entry = modelEntryMapper.selectByModelId(modelId.substring(0, idx), modelId.substring(idx + 1));
        if (entry == null) {
            throw new BizException("模型未配置：" + modelId);
        }
        List<Long> granted = modelEntryMapper.selectGrantedWorkspaceIds(entry.getId());
        if (!granted.contains(bucket) && !granted.contains(0L)) {
            throw new BizException("模型 " + modelId + " 未开放给当前空间");
        }
    }

    /** 覆盖式落库空间授权（SPEC §28.11）：先删后插，去重并跳过 null 桶 */
    private void saveGrants(Long modelEntryId, List<Long> workspaceIds) {
        modelEntryMapper.deleteGrants(modelEntryId);
        if (workspaceIds == null || workspaceIds.isEmpty()) {
            return;
        }
        String uid = ContextUtil.currentUserId();
        for (Long ws : new LinkedHashSet<>(workspaceIds)) {
            if (ws != null) {
                modelEntryMapper.insertGrant(modelEntryId, ws, uid);
            }
        }
    }

    private void requireAdmin() {
        TeapotUser user = ContextUtil.getUserFromContext();
        String roles = user == null ? null : user.getRoles();
        if (roles == null || !Arrays.asList(roles.split(",")).contains("admin")) {
            throw new BizException("模型入口管理仅 admin 可操作");
        }
    }
}
