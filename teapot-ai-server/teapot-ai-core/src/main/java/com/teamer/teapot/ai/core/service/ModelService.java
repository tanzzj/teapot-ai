package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.config.AuditService;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.dao.ModelEntryMapper;
import com.teamer.teapot.ai.core.model.ModelEntryDO;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import com.teamer.teapot.ai.rbac.model.TeapotUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
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

    public List<ModelEntryDO> listAll() {
        requireAdmin();
        return modelEntryMapper.selectAll();
    }

    /** 启用入口的能力位（SPEC §19 前端 gating，任意登录用户可读，不含密钥） */
    public List<ModelEntryDO> listEnabledCapabilities() {
        return modelEntryMapper.selectAllEnabled();
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

    private void requireAdmin() {
        TeapotUser user = ContextUtil.getUserFromContext();
        String roles = user == null ? null : user.getRoles();
        if (roles == null || !Arrays.asList(roles.split(",")).contains("admin")) {
            throw new BizException("模型入口管理仅 admin 可操作");
        }
    }
}
