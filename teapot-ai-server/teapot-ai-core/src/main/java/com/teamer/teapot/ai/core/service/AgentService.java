package com.teamer.teapot.ai.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.common.model.PageData;
import com.teamer.teapot.ai.core.agui.TeapotAguiAgentRegistrar;
import com.teamer.teapot.ai.core.config.AgentRunConnection;
import com.teamer.teapot.ai.core.config.AuditService;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.dao.AgentMapper;
import com.teamer.teapot.ai.core.dao.AgentSkillMapper;
import com.teamer.teapot.ai.core.model.AgentDO;
import com.teamer.teapot.ai.core.model.AgentFeature;
import com.teamer.teapot.ai.core.model.AgentSkillBind;
import com.teamer.teapot.ai.core.model.SandboxConfigDO;
import com.teamer.teapot.ai.core.model.StorageConfigDO;
import com.teamer.teapot.ai.core.model.dto.AgentCreateRequest;
import com.teamer.teapot.ai.core.model.dto.AgentUpdateRequest;
import com.teamer.teapot.ai.core.model.dto.ChatDebugRequest;
import com.teamer.teapot.ai.core.model.vo.AgentDetailVO;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Agent 管理（SPEC §7）：CRUD + Skill 绑定 + 同步调试对话。
 * 写操作同步维护 AguiAgentRegistry / AgentRegistry 实例缓存。
 */
@Slf4j
@Service
public class AgentService {

    /** 调试对话最长等待（模型侧超时由 run-timeout 控制，这里做兜底） */
    private static final Duration CHAT_TIMEOUT = Duration.ofSeconds(180);

    /** 压缩策略默认值（与 AgentRegistry 构建时一致；DDL 列为 NOT NULL） */
    private static final int DEFAULT_COMPACTION_TRIGGER = 30;
    private static final int DEFAULT_COMPACTION_KEEP = 10;

    private final AgentMapper agentMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final AgentRegistry agentRegistry;
    private final TeapotAguiAgentRegistrar aguiRegistrar;
    private final AuditService auditService;
    private final TeapotAiProperties properties;
    private final AgentRunConnection agentRunConnection;
    private final SandboxConfigService sandboxConfigService;
    private final StorageConfigService storageConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentService(AgentMapper agentMapper, AgentSkillMapper agentSkillMapper,
                        AgentRegistry agentRegistry, TeapotAguiAgentRegistrar aguiRegistrar,
                        AuditService auditService, TeapotAiProperties properties,
                        AgentRunConnection agentRunConnection,
                        SandboxConfigService sandboxConfigService,
                        StorageConfigService storageConfigService) {
        this.agentMapper = agentMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.agentRegistry = agentRegistry;
        this.aguiRegistrar = aguiRegistrar;
        this.auditService = auditService;
        this.properties = properties;
        this.agentRunConnection = agentRunConnection;
        this.sandboxConfigService = sandboxConfigService;
        this.storageConfigService = storageConfigService;
    }

    public PageData<AgentDO> list(int page, int size, String keyword, boolean includeDisabled) {
        int offset = Math.max(page - 1, 0) * size;
        long total = agentMapper.count(keyword, includeDisabled);
        List<AgentDO> list = agentMapper.selectPage(keyword, includeDisabled, offset, size);
        return PageData.of(total, list);
    }

    public AgentDetailVO detail(String agentKey) {
        AgentDO agent = requireAgent(agentKey);
        List<String> skillNames = agentSkillMapper.selectByAgentKey(agentKey)
                .stream().map(AgentSkillBind::getSkillName).toList();
        return AgentDetailVO.of(agent, skillNames);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentDO create(AgentCreateRequest request) {
        AgentDO existed = agentMapper.selectByAgentKey(request.getAgentKey());
        if (existed != null && Integer.valueOf(1).equals(existed.getStatus())) {
            throw new BizException("agentKey 已存在：" + request.getAgentKey());
        }
        AgentDO agent = new AgentDO();
        agent.setAgentKey(request.getAgentKey());
        agent.setName(request.getName());
        agent.setDescription(request.getDescription());
        agent.setSysPrompt(request.getSysPrompt());
        agent.setModelId(request.getModelId());
        agent.setCompactionTrigger(request.getCompactionTrigger() == null
                ? DEFAULT_COMPACTION_TRIGGER : request.getCompactionTrigger());
        agent.setCompactionKeep(request.getCompactionKeep() == null
                ? DEFAULT_COMPACTION_KEEP : request.getCompactionKeep());
        agent.setFeature(validateFeature(request.getFeature()));
        agent.setStatus(1);
        agent.setCreatedBy(ContextUtil.currentUserId());
        if (existed != null) {
            // 软删行复活（唯一键冲突规避，SPEC §10.1）：全字段覆盖更新
            agentMapper.update(agent);
        } else {
            agentMapper.insert(agent);
        }
        replaceBindings(request.getAgentKey(), request.getSkillNames());
        writeAgentsMd(request.getAgentKey(), request.getSysPrompt());
        aguiRegistrar.register(request.getAgentKey());
        auditService.log("agent.create", request.getAgentKey(),
                "modelId=" + request.getModelId());
        return agentMapper.selectByAgentKey(request.getAgentKey());
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentDO update(String agentKey, AgentUpdateRequest request) {
        AgentDO agent = requireAgent(agentKey);
        if (!Integer.valueOf(1).equals(agent.getStatus())) {
            throw new BizException("Agent 已停用，不可修改：" + agentKey);
        }
        // 合并非空字段（null = 不修改），XML 为全字段更新
        if (request.getName() != null) {
            agent.setName(request.getName());
        }
        if (request.getDescription() != null) {
            agent.setDescription(request.getDescription());
        }
        if (request.getSysPrompt() != null) {
            agent.setSysPrompt(request.getSysPrompt());
        }
        if (request.getModelId() != null) {
            agent.setModelId(request.getModelId());
        }
        // compaction 列为 NOT NULL：合并后仍为 null 时回填默认值
        if (agent.getCompactionTrigger() == null) {
            agent.setCompactionTrigger(DEFAULT_COMPACTION_TRIGGER);
        }
        if (agent.getCompactionKeep() == null) {
            agent.setCompactionKeep(DEFAULT_COMPACTION_KEEP);
        }
        if (request.getCompactionTrigger() != null) {
            agent.setCompactionTrigger(request.getCompactionTrigger());
        }
        if (request.getCompactionKeep() != null) {
            agent.setCompactionKeep(request.getCompactionKeep());
        }
        // feature 非 null 时整体替换（SPEC §16.6；空对象 {} = 清空）
        if (request.getFeature() != null) {
            agent.setFeature(validateFeature(request.getFeature()));
        }
        agentMapper.update(agent);
        if (request.getSkillNames() != null) {
            replaceBindings(agentKey, request.getSkillNames());
        }
        writeAgentsMd(agentKey, agent.getSysPrompt());
        agentRegistry.invalidate(agentKey);
        auditService.log("agent.update", agentKey, null);
        return agent;
    }

    /** 更新 Agent 头像（SPEC §23：头像 URL 由上传端点产出，此处仅落库） */
    @Transactional(rollbackFor = Exception.class)
    public AgentDO updateAvatar(String agentKey, String avatarUrl) {
        AgentDO agent = requireAgent(agentKey);
        agent.setAvatar(avatarUrl);
        agentMapper.update(agent);
        auditService.log("agent.avatar", agentKey, null);
        return agent;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String agentKey) {
        AgentDO agent = requireAgent(agentKey);
        agentMapper.softDelete(agentKey);
        agentSkillMapper.deleteByAgentKey(agentKey);
        aguiRegistrar.unregister(agentKey);
        agentRegistry.invalidate(agentKey);
        auditService.log("agent.delete", agentKey,
                "name=" + agent.getName());
    }

    @Transactional(rollbackFor = Exception.class)
    public void bindSkill(String agentKey, String skillName) {
        requireAgent(agentKey);
        boolean exists = agentSkillMapper.selectByAgentKey(agentKey).stream()
                .anyMatch(b -> b.getSkillName().equals(skillName));
        if (!exists) {
            AgentSkillBind bind = new AgentSkillBind();
            bind.setAgentKey(agentKey);
            bind.setSkillName(skillName);
            bind.setCreatedBy(ContextUtil.currentUserId());
            agentSkillMapper.insert(bind);
        }
        agentRegistry.invalidate(agentKey);
        auditService.log("agent.bindSkill", agentKey, "skill=" + skillName);
    }

    @Transactional(rollbackFor = Exception.class)
    public void unbindSkill(String agentKey, String skillName) {
        requireAgent(agentKey);
        agentSkillMapper.delete(agentKey, skillName);
        agentRegistry.invalidate(agentKey);
        auditService.log("agent.unbindSkill", agentKey, "skill=" + skillName);
    }

    /**
     * 同步调试对话（SPEC §7.1 /api/agent/chat）：
     * 复用 HarnessAgent 实例 + RuntimeContext(userId, sessionId)，与 AG-UI 流式链路同状态域。
     */
    public String chat(String agentKey, ChatDebugRequest request) {
        requireAgent(agentKey);
        HarnessAgent agent = agentRegistry.getOrCreate(agentKey);
        String sessionId = request.getSessionId() == null || request.getSessionId().isBlank()
                ? "default" : request.getSessionId();
        RuntimeContext context = RuntimeContext.builder()
                .userId(ContextUtil.currentUserId())
                .sessionId(sessionId)
                .build();
        Msg reply = agent.call(request.getMessage(), context).block(CHAT_TIMEOUT);
        if (reply == null) {
            throw new BizException("模型未返回结果，请稍后重试");
        }
        return reply.getTextContent();
    }

    private AgentDO requireAgent(String agentKey) {
        AgentDO agent = agentMapper.selectByAgentKey(agentKey);
        if (agent == null) {
            throw new BizException("Agent 不存在：" + agentKey);
        }
        return agent;
    }

    /**
     * feature 保存前强校验（SPEC §16.6/§22）：枚举/范围/前缀/记录引用完整性，不合法直接拒绝；
     * 返回入库 JSON（空命名空间返回 null）。
     */
    private String validateFeature(Map<String, Object> featureMap) {
        try {
            AgentFeature feature = AgentFeature.parse(objectMapper.writeValueAsString(featureMap));
            feature.validate(agentRunConnection.anyConfigured());
            validateFeatureRecords(feature);
            return feature.toJson();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("feature 序列化失败：" + e.getMessage());
        }
    }

    /** 记录引用完整性（§22）：sandbox/storage 引用的记录必须存在且对应链路凭证齐备 */
    private void validateFeatureRecords(AgentFeature feature) {
        AgentFeature.Sandbox sb = feature.getSandbox();
        if (sb != null && sb.isEnabled() && sb.getSandboxRecord() != null && !sb.getSandboxRecord().isBlank()) {
            SandboxConfigDO rec = sandboxConfigService.getPlain(sb.getSandboxRecord().trim());
            if (rec == null) {
                throw new BizException("沙箱记录不存在：" + sb.getSandboxRecord());
            }
            if (!SandboxConfigService.linkConfigured(rec)) {
                throw new BizException("沙箱记录 " + rec.getName() + " 的 " + rec.getLinkType()
                        + " 链路凭证不齐，请在系统配置 - 沙箱中补全");
            }
        }
        AgentFeature.Storage st = feature.getStorage();
        if (st != null && "oss".equals(st.getMode())
                && st.getStorageRecord() != null && !st.getStorageRecord().isBlank()) {
            StorageConfigDO rec = storageConfigService.getPlain(st.getStorageRecord().trim());
            if (rec == null) {
                throw new BizException("OSS 连接记录不存在：" + st.getStorageRecord());
            }
            if (isBlank(rec.getAccessKeyId()) || isBlank(rec.getAccessKeySecret())
                    || isBlank(rec.getRegion()) || isBlank(rec.getBucket())) {
                throw new BizException("OSS 记录 " + rec.getName() + " 凭证不齐（需 AK/Secret/Region/Bucket）");
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 整体替换绑定集合（skillNames 为 null 时不动） */
    private void replaceBindings(String agentKey, List<String> skillNames) {
        if (skillNames == null) {
            return;
        }
        agentSkillMapper.deleteByAgentKey(agentKey);
        String userId = ContextUtil.currentUserId();
        for (String skillName : skillNames) {
            AgentSkillBind bind = new AgentSkillBind();
            bind.setAgentKey(agentKey);
            bind.setSkillName(skillName);
            bind.setCreatedBy(userId);
            agentSkillMapper.insert(bind);
        }
    }

    /** workspace/<agentKey>/AGENTS.md（内容 = sysPrompt，SPEC §7.1 create） */
    private void writeAgentsMd(String agentKey, String sysPrompt) {
        try {
            Path dir = Path.of(properties.getAgentscope().getWorkspaceRoot()).resolve(agentKey);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("AGENTS.md"), sysPrompt, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("AGENTS.md 写入失败 agentKey={}", agentKey, e);
        }
    }
}
