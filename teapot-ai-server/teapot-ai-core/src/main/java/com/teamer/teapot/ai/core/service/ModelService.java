package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.audit.AuditService;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.dao.ModelEntryMapper;
import com.teamer.teapot.ai.core.model.ModelEntryDO;
import com.teamer.teapot.ai.core.model.ModelWorkspaceGrantDO;
import com.teamer.teapot.ai.core.model.provider.ThinkingSupport;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import com.teamer.teapot.ai.rbac.model.TeapotUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final ModelVendorClient vendorClient;

    public ModelService(ModelEntryMapper modelEntryMapper, ModelRegistry modelRegistry,
                        AuditService auditService, TeapotAiProperties properties,
                        ModelVendorClient vendorClient) {
        this.modelEntryMapper = modelEntryMapper;
        this.modelRegistry = modelRegistry;
        this.auditService = auditService;
        this.properties = properties;
        this.vendorClient = vendorClient;
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
     * 某供应商的在售模型清单（新建/编辑模型入口的模型名下拉数据源，SPEC §30.2）。
     * 服务器密钥代拉，密钥不出后端；缓存由 {@link ModelVendorClient} 按供应商+网关持有。
     */
    public List<String> listVendorModels(String provider) {
        requireAdmin();
        return vendorClient.filterChatModels(vendorClient.listModels(provider, null, null), provider);
    }

    /**
     * 模型入口探活（SPEC §30.2）：确认密钥可用、模型存在，并把供应商报出的上下文窗口与输入模态
     * （即能力位，SPEC §19）回填进入口——这两项供应商自己是权威，不必靠模型名猜。
     *
     * <p>探活本身失败不抛异常也不改库存——「错在哪」就是要给界面看的内容；只有入口不存在才抛。
     * 供应商没报的项（窗口 0 / 模态空列表）保持原值不动，空字符串能力位表示「确认为纯文本」。
     * 有变化时失效模型实例缓存，下一轮装配即生效。
     */
    public ModelVendorClient.ProbeResult probe(Long id) {
        requireAdmin();
        ModelEntryDO entry = modelEntryMapper.selectById(id);
        if (entry == null) {
            throw new BizException("模型入口不存在：id=" + id);
        }
        ModelVendorClient.ProbeResult result = vendorClient.probe(
                entry.getProvider(), entry.getModelName(), entry.getBaseUrl(), null);
        Integer windowPatch = result.contextWindowSize() > 0
                && !Objects.equals(entry.getContextWindowSize(), result.contextWindowSize())
                ? result.contextWindowSize() : null;
        String capsPatch = capabilitiesPatch(entry, result.inputModalities());
        if (windowPatch != null || capsPatch != null) {
            modelEntryMapper.updateProbe(id, windowPatch, capsPatch);
            modelRegistry.evict(entry.modelId());
            auditService.log("model.probe", entry.modelId(),
                    "contextWindowSize=" + windowPatch + ", capabilities=" + capsPatch);
            log.info("探活回填 modelId={} window={} capabilities={}", entry.modelId(), windowPatch, capsPatch);
            return new ModelVendorClient.ProbeResult(result.ok(), result.httpStatus(),
                    result.contextWindowSize(), result.inputModalities(),
                    result.message() + "（已回填入口）");
        }
        return result;
    }

    /** 供应商报出的输入模态与库存能力位的差异；无模态信息或值相同 → null（不改） */
    private String capabilitiesPatch(ModelEntryDO entry, List<String> vendorCapabilities) {
        if (vendorCapabilities.isEmpty()) {
            return null;
        }
        String vendor = vendorCapabilities.stream().distinct().sorted().collect(Collectors.joining(","));
        String current = entry.getCapabilities() == null ? "" : Arrays.stream(entry.getCapabilities().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
        return vendor.equals(current) ? null : vendor;
    }

    /**
     * 供应商能报的就不让人手填（SPEC §30.6）：可发现供应商（当前 DashScope）在保存时以探活结果为权威，
     * 覆盖上下文窗口与能力位；空模态即供应商确认「纯文本」，能力位置空。
     *
     * <p>探活不成功（模型不在目录、密钥未配、网络异常）不阻断保存，沿用提交值并留 WARN——
     * 否则像 qwen-turbo 这种目录里已查不到的存量入口会连改名都做不到。
     */
    private void syncFromVendor(ModelEntryDO entry) {
        if (!vendorClient.supportsDiscovery(entry.getProvider())) {
            return;
        }
        ModelVendorClient.ProbeResult result = vendorClient.probe(
                entry.getProvider(), entry.getModelName(), entry.getBaseUrl(), null);
        if (!result.ok()) {
            log.warn("保存时供应商同步未成功，沿用提交值 modelId={} reason={}",
                    entry.modelId(), result.message());
            return;
        }
        if (result.contextWindowSize() > 0) {
            entry.setContextWindowSize(result.contextWindowSize());
        }
        entry.setCapabilities(String.join(",", result.inputModalities()));
    }

    /**
     * 未落库的模型探活（SPEC §30.6）：Agent 自带模型在配置页就要拿到窗口与能力位，
     * 密钥用用户填的那把（留空回落服务端环境变量）。
     *
     * <p>只对「可发现」的供应商开放：这类供应商的端点是固定的官方地址；其余供应商的 baseUrl
     * 由调用方自定，放开等于让服务端去访问任意地址，故直接拒绝。
     */
    public ModelVendorClient.ProbeResult probeDraft(String provider, String modelName, String apiKey) {
        if (!vendorClient.supportsDiscovery(provider)) {
            throw new BizException(provider + " 不提供模型元数据，请手工填写上下文窗口与多模态能力");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new BizException("模型名不能为空");
        }
        return vendorClient.probe(provider, modelName.trim(), null,
                apiKey == null ? null : apiKey.trim());
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
        syncFromVendor(request);
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
        // 窗口允许显式清零（0 = 交回框架推断），但不传（null）表示这次不动它
        if (request.getContextWindowSize() != null) {
            existed.setContextWindowSize(request.getContextWindowSize());
        }
        if (request.getStatus() != null) {
            existed.setStatus(request.getStatus());
        }
        syncFromVendor(existed);
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
    /**
     * 已接入的模型供应商（SPEC §29.16）：由 {@link ModelRegistry} 的策略 Bean 汇总，
     * 是 Agent 自带模型 provider 白名单的唯一来源，界面下拉也从这里取。
     */
    public Set<String> providers() {
        return modelRegistry.providers();
    }

    /** 某供应商标识是否能自己报出窗口与输入模态（SPEC §30.6）：界面据此决定是否还让人手填 */
    public boolean supportsDiscovery(String provider) {
        return vendorClient.supportsDiscovery(provider);
    }

    /** 某 modelId 所属供应商的思考档表达力；供应商未接入时返回 null */
    public ThinkingSupport thinkingSupport(String modelId) {
        return modelRegistry.thinkingSupport(modelId);
    }

    /** 按供应商标识取思考档表达力；未接入时返回 null */
    public ThinkingSupport thinkingSupportOf(String provider) {
        return modelRegistry.thinkingSupportOf(provider);
    }

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
