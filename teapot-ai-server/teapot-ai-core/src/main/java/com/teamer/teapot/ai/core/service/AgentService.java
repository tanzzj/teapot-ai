package com.teamer.teapot.ai.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.common.model.PageData;
import com.teamer.teapot.ai.core.agui.TeapotAguiAgentRegistrar;
import com.teamer.teapot.ai.core.channel.ChannelHub;
import com.teamer.teapot.ai.core.crypto.ConfigCryptoService;
import com.teamer.teapot.ai.core.sandbox.SandboxConnectivity;
import com.teamer.teapot.ai.core.audit.AuditService;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.dao.AgentMapper;
import com.teamer.teapot.ai.core.dao.AgentSkillMapper;
import com.teamer.teapot.ai.core.model.AgentDO;
import com.teamer.teapot.ai.core.model.AgentFeature;
import com.teamer.teapot.ai.core.model.AgentSkillBind;
import com.teamer.teapot.ai.core.model.provider.ThinkingSupport;
import com.teamer.teapot.ai.core.model.ChannelConfigDO;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final SandboxConnectivity sandboxConnectivity;
    private final SandboxConfigService sandboxConfigService;
    private final StorageConfigService storageConfigService;
    private final ChannelConfigService channelConfigService;
    private final ChannelHub channelHub;
    private final WorkspaceService workspaceService;
    private final ModelService modelService;
    private final ConfigCryptoService crypto;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentService(AgentMapper agentMapper, AgentSkillMapper agentSkillMapper,
                        AgentRegistry agentRegistry, TeapotAguiAgentRegistrar aguiRegistrar,
                        AuditService auditService, TeapotAiProperties properties,
                        SandboxConnectivity sandboxConnectivity,
                        SandboxConfigService sandboxConfigService,
                        StorageConfigService storageConfigService,
                        ChannelConfigService channelConfigService,
                        ChannelHub channelHub,
                        WorkspaceService workspaceService,
                        ModelService modelService,
                        ConfigCryptoService crypto) {
        this.agentMapper = agentMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.agentRegistry = agentRegistry;
        this.aguiRegistrar = aguiRegistrar;
        this.auditService = auditService;
        this.properties = properties;
        this.sandboxConnectivity = sandboxConnectivity;
        this.sandboxConfigService = sandboxConfigService;
        this.storageConfigService = storageConfigService;
        this.channelConfigService = channelConfigService;
        this.channelHub = channelHub;
        this.workspaceService = workspaceService;
        this.modelService = modelService;
        this.crypto = crypto;
    }

    public PageData<AgentDO> list(int page, int size, String keyword, boolean includeDisabled, Long workspaceId) {
        int offset = Math.max(page - 1, 0) * size;
        // 空间作用域（SPEC §28.4）：null = 个人空间默认视图（读不懒建，无空间即空列表）；非空先过跨空间隔离
        Long scopeId;
        if (workspaceId == null) {
            scopeId = workspaceService.personalWorkspaceIdOrNull(ContextUtil.currentUserId());
            if (scopeId == null) {
                return PageData.of(0, List.of());
            }
        } else {
            workspaceService.requireMember(workspaceId);
            scopeId = workspaceId;
        }
        long total = agentMapper.count(keyword, includeDisabled, scopeId);
        List<AgentDO> list = agentMapper.selectPage(keyword, includeDisabled, scopeId, offset, size);
        list.forEach(this::redactSecrets);
        return PageData.of(total, list);
    }

    public AgentDetailVO detail(String agentKey) {
        AgentDO agent = requireAgent(agentKey);
        redactSecrets(agent);
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
        validateCompaction(request.getCompactionTrigger(), request.getCompactionKeep());
        agent.setCompactionTrigger(request.getCompactionTrigger() == null
                ? DEFAULT_COMPACTION_TRIGGER : request.getCompactionTrigger());
        agent.setCompactionKeep(request.getCompactionKeep() == null
                ? DEFAULT_COMPACTION_KEEP : request.getCompactionKeep());
        agent.setFeature(validateFeature(request.getFeature(), existed == null ? null : existed.getFeature()));
        assertMessagesTrigger(agent);
        agent.setStatus(1);
        agent.setCreatedBy(ContextUtil.currentUserId());
        // 归属空间：null → 个人空间（首个 Agent 触发懒创建）；非 null → 校验成员后归属（SPEC §28.4）
        agent.setWorkspaceId(workspaceService.resolveTargetWorkspace(request.getWorkspaceId()));
        // 模型开放校验（SPEC §28.11）：白名单放行；否则所选模型必须开放给目标空间桶
        assertModelOpen(request.getModelId(), request.getWorkspaceId() == null ? 0L : request.getWorkspaceId(),
                agent.getFeature());
        assertProviderAndThinking(request.getModelId(), agent.getFeature());
        if (existed != null) {
            // 软删行复活（唯一键冲突规避，SPEC §10.1）：全字段覆盖更新
            agentMapper.update(agent);
        } else {
            agentMapper.insert(agent);
        }
        replaceBindings(request.getAgentKey(), request.getSkillNames());
        writeAgentsMd(request.getAgentKey(), request.getSysPrompt());
        aguiRegistrar.register(request.getAgentKey());
        // channel 启用时启动连接器（未启用时 sync 静默跳过，SPEC §24.4）
        channelHub.sync(request.getAgentKey());
        auditService.log("agent.create", request.getAgentKey(),
                "modelId=" + request.getModelId());
        AgentDO created = agentMapper.selectByAgentKey(request.getAgentKey());
        redactSecrets(created);
        return created;
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
        validateCompaction(request.getCompactionTrigger(), request.getCompactionKeep());
        // feature 非 null 时整体替换（SPEC §16.6；空对象 {} = 清空）
        if (request.getFeature() != null) {
            String previousFeature = agent.getFeature();
            agent.setFeature(validateFeature(request.getFeature(), previousFeature));
        }
        assertMessagesTrigger(agent);
        // 模型开放校验放在 feature 合并之后：本次保存可能同时带来新模型与它的自带凭证（SPEC §29）
        if (request.getModelId() != null) {
            assertModelOpen(request.getModelId(),
                    workspaceService.modelBucketForWorkspace(agent.getWorkspaceId()), agent.getFeature());
        }
        // 供应商/思考档校验按合并后的主模型跑：只改思考档、不动模型的保存同样要拦（SPEC §29.16）
        assertProviderAndThinking(agent.getModelId(), agent.getFeature());
        agentMapper.update(agent);
        if (request.getSkillNames() != null) {
            replaceBindings(agentKey, request.getSkillNames());
        }
        writeAgentsMd(agentKey, agent.getSysPrompt());
        agentRegistry.invalidate(agentKey);
        // channel 改绑/停用即时生效（SPEC §24.7 验收 5）：sync 内部 restart 或 stop
        channelHub.sync(agentKey);
        auditService.log("agent.update", agentKey, null);
        redactSecrets(agent);
        return agent;
    }

    /** compaction 取值校验：-1 = 关闭压缩，0 = 不按条数触发（只按 token/窗口），其余须落既有区间 */
    private static void validateCompaction(Integer trigger, Integer keep) {
        if (trigger != null && trigger != -1 && (trigger < 0 || trigger > 200)) {
            throw new BizException("compactionTrigger 须为 -1（关闭）/ 0（不按条数触发）/ 1–200");
        }
        if (keep != null && keep != -1 && (keep < 0 || keep > 100)) {
            throw new BizException("compactionKeep 须为 -1（关闭）或 0–100");
        }
    }

    /**
     * 触发口径选「按条数」时轮数就是主输入，填 0（=不按条数触发）等于该口径下没有条数触发（SPEC §29.22）。
     * 轮数为 null 视为未指定，运行时回落默认 30，不拦。
     */
    private static void assertMessagesTrigger(AgentDO agent) {
        if (agent.getFeature() == null || agent.getFeature().isBlank()) {
            return;
        }
        AgentFeature.Compaction c = AgentFeature.parse(agent.getFeature()).getCompaction();
        if (c == null || !"messages".equals(c.getTriggerMode())) {
            return;
        }
        Integer trigger = agent.getCompactionTrigger();
        if (trigger != null && trigger == 0) {
            throw new BizException("触发口径选「按条数」时，触发轮数不能为 0（0 = 不按条数触发）");
        }
    }

    /** 更新 Agent 头像（SPEC §23：头像 URL 由上传端点产出，此处仅落库） */
    @Transactional(rollbackFor = Exception.class)
    public AgentDO updateAvatar(String agentKey, String avatarUrl) {
        AgentDO agent = requireAgent(agentKey);
        agent.setAvatar(avatarUrl);
        agentMapper.update(agent);
        auditService.log("agent.avatar", agentKey, null);
        redactSecrets(agent);
        return agent;
    }

    /** 移动 Agent 到别的空间（SPEC §28.4 需求 5）；targetWorkspaceId=null = 移回个人空间 */
    @Transactional(rollbackFor = Exception.class)
    public AgentDO moveWorkspace(String agentKey, Long targetWorkspaceId) {
        AgentDO agent = requireAgent(agentKey);
        if (!Integer.valueOf(1).equals(agent.getStatus())) {
            throw new BizException("Agent 已停用，不可移动：" + agentKey);
        }
        // 对源空间要有访问权（存量未归属 agent 的 workspaceId 为 null，跳过源校验）
        if (agent.getWorkspaceId() != null) {
            workspaceService.requireMember(agent.getWorkspaceId());
        }
        agent.setWorkspaceId(workspaceService.resolveTargetWorkspace(targetWorkspaceId));
        agentMapper.update(agent);
        agentRegistry.invalidate(agentKey);
        auditService.log("agent.moveWorkspace", agentKey, "workspaceId=" + agent.getWorkspaceId());
        redactSecrets(agent);
        return agent;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String agentKey) {
        AgentDO agent = requireAgent(agentKey);
        agentMapper.softDelete(agentKey);
        agentSkillMapper.deleteByAgentKey(agentKey);
        aguiRegistrar.unregister(agentKey);
        agentRegistry.invalidate(agentKey);
        channelHub.stop(agentKey);
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
     * 返回入库 JSON（空命名空间返回 null）。自定义模型的 apiKey 在此加密入库（SPEC §29）。
     *
     * @param previousFeatureJson 该 Agent 上一版入库 JSON（create 时为软删复活行的 feature，可为 null），
     *                            用于「apiKey 留空 = 沿用原密钥」
     */
    private String validateFeature(Map<String, Object> featureMap, String previousFeatureJson) {
        try {
            AgentFeature feature = AgentFeature.parse(objectMapper.writeValueAsString(featureMap));
            sealCustomModelKeys(feature, previousFeatureJson);
            sealSandboxKey(feature, previousFeatureJson);
            feature.validate(sandboxConnectivity.anyConfigured());
            validateFeatureRecords(feature);
            return feature.toJson();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("feature 序列化失败：" + e.getMessage());
        }
    }

    /**
     * 自定义模型密钥入库（SPEC §29）：传了明文就加密替换；留空则沿用上一版同 modelId 的密文
     * （与沙箱/OSS 记录的「留空=不改」同语义）。modelId 变了视为新模型，需重填密钥。
     */
    private void sealCustomModelKeys(AgentFeature feature, String previousFeatureJson) {
        List<AgentFeature.CustomModel> models = feature.getCustomModels();
        if (models.isEmpty()) {
            return;
        }
        Map<String, String> previous = new HashMap<>();
        for (AgentFeature.CustomModel old : AgentFeature.parse(previousFeatureJson).getCustomModels()) {
            if (!isBlank(old.getApiKey())) {
                previous.put(old.modelId(), old.getApiKey());
            }
        }
        for (AgentFeature.CustomModel model : models) {
            String apiKey = model.getApiKey();
            if (isBlank(apiKey)) {
                model.setApiKey(previous.get(model.modelId()));
            } else {
                if (!crypto.available()) {
                    throw new BizException("服务器未配置 TEAPOT_SECRET_KEY，无法保存自定义模型密钥");
                }
                model.setApiKey(crypto.encrypt(apiKey.trim()));
            }
            // 回显标记是出参派生值，前端会原样带回来，不落库
            model.setHasApiKey(null);
        }
        feature.setCustomModels(models);
    }

    /**
     * 出参脱敏（SPEC §29）：自定义模型的 apiKey 与自带沙箱的 e2bApiKey 都是 AES-GCM 密文，
     * 一律不回前端，只回 hasApiKey 标记供界面提示「已配置密钥，留空则不修改」。
     */
    private void redactSecrets(AgentDO agent) {
        if (agent == null || isBlank(agent.getFeature())) {
            return;
        }
        AgentFeature feature = AgentFeature.parse(agent.getFeature());
        List<AgentFeature.CustomModel> models = feature.getCustomModels();
        if (!models.isEmpty()) {
            for (AgentFeature.CustomModel model : models) {
                model.setHasApiKey(!isBlank(model.getApiKey()));
                model.setApiKey(null);
            }
            feature.setCustomModels(models);
        }
        AgentFeature.Sandbox sb = feature.getSandbox();
        if (sb != null && sb.isCustom()) {
            sb.setHasE2bApiKey(!isBlank(sb.getE2bApiKey()));
            sb.setE2bApiKey(null);
            feature.setSandbox(sb);
        }
        agent.setFeature(feature.toJson());
    }

    /**
     * 自带沙箱密钥入库（SPEC §29.17）：传了明文就加密替换；留空则沿用上一版的密文
     * （与 §29 自带模型、沙箱/OSS 记录同语义）。切回系统记录承载时整段凭证随命名空间消失。
     */
    private void sealSandboxKey(AgentFeature feature, String previousFeatureJson) {
        AgentFeature.Sandbox sb = feature.getSandbox();
        if (sb == null || !sb.isCustom()) {
            return;
        }
        AgentFeature.Sandbox old = AgentFeature.parse(previousFeatureJson).getSandbox();
        String previousKey = old != null && old.isCustom() ? old.getE2bApiKey() : null;
        if (isBlank(sb.getE2bApiKey())) {
            sb.setE2bApiKey(previousKey);
        } else {
            if (!crypto.available()) {
                throw new BizException("服务器未配置 TEAPOT_SECRET_KEY，无法保存自带沙箱密钥");
            }
            sb.setE2bApiKey(crypto.encrypt(sb.getE2bApiKey().trim()));
        }
        // 回显标记是出参派生值，前端会原样带回来，不落库
        sb.setHasE2bApiKey(null);
        feature.setSandbox(sb);
    }

    /** 该 Agent 自带模型的 modelId 集合（SPEC §29）：这些模型不走空间授权 */
    private Set<String> customModelIds(String featureJson) {
        Set<String> ids = new HashSet<>();
        for (AgentFeature.CustomModel model : AgentFeature.parse(featureJson).getCustomModels()) {
            ids.add(model.modelId());
        }
        return ids;
    }

    /**
     * 模型对空间开放的校验闸门：白名单用户放行；Agent 自带模型（SPEC §29，凭证是用户自己的）
     * 放行；其余必须已开放给目标空间桶（SPEC §28.11）。
     */
    private void assertModelOpen(String modelId, long bucket, String featureJson) {
        if (workspaceService.isWhitelisted(ContextUtil.currentUserId())
                || customModelIds(featureJson).contains(modelId)) {
            return;
        }
        modelService.assertOpenToWorkspace(modelId, bucket);
    }

    /**
     * 供应商白名单 + 思考档表达力校验（SPEC §29.16）。白名单来自已接入的策略 Bean
     * （{@code ModelService#providers()}），思考档按所选主模型的供应商判定：
     * 供应商压根没有这条通道、或只有"开"没有"关"时**拒绝保存**并要求改回「默认」，
     * 而不是收下这个值再在运行期静默忽略。
     */
    private void assertProviderAndThinking(String modelId, String featureJson) {
        AgentFeature feature = AgentFeature.parse(featureJson);
        for (AgentFeature.CustomModel cm : feature.getCustomModels()) {
            if (!modelService.providers().contains(cm.getProvider())) {
                throw new BizException("自定义模型供应商未接入，可选值：" + modelService.providers());
            }
        }
        AgentFeature.Runtime rt = feature.getRuntime();
        Boolean thinking = rt == null ? null : rt.getThinkingMode();
        if (thinking == null) {
            return;
        }
        ThinkingSupport support = modelService.thinkingSupport(modelId);
        // 供应商未知时交给模型开放闸门报错，这里不重复报同一个问题
        if (support == ThinkingSupport.NONE
                || (support == ThinkingSupport.ON_ONLY && !thinking)) {
            throw new BizException("模型 " + modelId + " 的供应商"
                    + (support == ThinkingSupport.NONE ? "不支持思考模式开关" : "没有关闭思考的通道")
                    + "，请把思考模式改回「默认」");
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
        AgentFeature.Channel ch = feature.getChannel();
        if (ch != null && ch.isEnabled()
                && ch.getChannelRecord() != null && !ch.getChannelRecord().isBlank()) {
            ChannelConfigDO rec = channelConfigService.getPlain(ch.getChannelRecord().trim());
            if (rec == null) {
                throw new BizException("连接器记录不存在：" + ch.getChannelRecord());
            }
            if (!ChannelConfigService.configured(rec)) {
                throw new BizException("连接器记录 " + rec.getName()
                        + " 凭证不齐，请在系统配置 - 连接器中补全");
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
