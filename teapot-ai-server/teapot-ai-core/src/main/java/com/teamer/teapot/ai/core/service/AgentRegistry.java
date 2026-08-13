package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.config.AgentRunConnection;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.dao.AgentMapper;
import com.teamer.teapot.ai.core.dao.AgentSkillMapper;
import com.teamer.teapot.ai.core.model.AgentDO;
import com.teamer.teapot.ai.core.model.AgentFeature;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.extensions.sandbox.agentrun.AgentRunFilesystemSpec;
import io.agentscope.extensions.sandbox.agentrun.AgentRunNasMountConfig;
import io.agentscope.extensions.sandbox.e2b.E2bCodec;
import io.agentscope.extensions.sandbox.e2b.E2bFilesystemSpec;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentRegistry（SPEC §6.1 平台核心组件）：
 * t_agent 记录 → 进程内 HarnessAgent 实例缓存；配置变更时失效重建。
 * 无状态并发：同一实例服务全部用户，RuntimeContext(userId, sessionId) 隔离。
 * Skill 双来源（MySQL + Git，§15.7）；沙箱按 Agent feature 装配（§16.7）。
 */
@Slf4j
@Component
public class AgentRegistry {

    private static final int DEFAULT_COMPACTION_TRIGGER = 30;
    private static final int DEFAULT_COMPACTION_KEEP = 10;

    private final Map<String, HarnessAgent> cache = new ConcurrentHashMap<>();
    private final AgentMapper agentMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final ModelRegistry modelRegistry;
    private final MysqlAgentStateStore stateStore;
    private final MysqlSkillRepository skillRepositoryAgent;
    private final TeapotAiProperties properties;
    /** Git skill 来源（enabled=false 时缺席，SPEC §15.6） */
    private final ObjectProvider<GitSkillRepository> gitRepoProvider;
    private final AgentRunConnection agentRunConnection;

    public AgentRegistry(AgentMapper agentMapper, AgentSkillMapper agentSkillMapper,
                         ModelRegistry modelRegistry, MysqlAgentStateStore stateStore,
                         @Qualifier("skillRepositoryAgent") MysqlSkillRepository skillRepositoryAgent,
                         TeapotAiProperties properties,
                         ObjectProvider<GitSkillRepository> gitRepoProvider,
                         AgentRunConnection agentRunConnection) {
        this.agentMapper = agentMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.modelRegistry = modelRegistry;
        this.stateStore = stateStore;
        this.skillRepositoryAgent = skillRepositoryAgent;
        this.properties = properties;
        this.gitRepoProvider = gitRepoProvider;
        this.agentRunConnection = agentRunConnection;
    }

    /** 惰性构建（AG-UI registerFactory 的 supplier 入口） */
    public HarnessAgent getOrCreate(String agentKey) {
        return cache.computeIfAbsent(agentKey, this::build);
    }

    /** 配置变更/删除时失效重建（skill 内容变更无需重建，动态合并下一轮生效） */
    public void invalidate(String agentKey) {
        HarnessAgent removed = cache.remove(agentKey);
        if (removed != null) {
            try {
                removed.close();
            } catch (Exception e) {
                log.warn("关闭 HarnessAgent 失败 agentKey={}", agentKey, e);
            }
            log.info("AgentRegistry 实例已失效 agentKey={}", agentKey);
        }
    }

    private HarnessAgent build(String agentKey) {
        AgentDO agentDO = agentMapper.selectByAgentKey(agentKey);
        if (agentDO == null || agentDO.getStatus() == null || agentDO.getStatus() != 1) {
            throw new BizException("Agent 不存在或已停用：" + agentKey);
        }
        Path workspace = Path.of(properties.getAgentscope().getWorkspaceRoot()).resolve(agentKey);
        // Agent↔Skill 绑定过滤（SPEC §6.1 第 4 条）：空绑定 = 两来源全集
        List<String> bound = agentSkillMapper.selectByAgentKey(agentKey)
                .stream().map(b -> b.getSkillName()).toList();
        SkillFilter skillFilter = bound.isEmpty()
                ? SkillFilter.all()
                : SkillFilter.only(bound.toArray(new String[0]));
        int trigger = agentDO.getCompactionTrigger() == null
                ? DEFAULT_COMPACTION_TRIGGER : agentDO.getCompactionTrigger();
        int keep = agentDO.getCompactionKeep() == null
                ? DEFAULT_COMPACTION_KEEP : agentDO.getCompactionKeep();

        // skill 双来源（SPEC §15.7）：[mysql 只读, git?]，SkillFilter 按 name 跨来源过滤
        List<AgentSkillRepository> repos = new ArrayList<>();
        repos.add(skillRepositoryAgent);
        GitSkillRepository git = gitRepoProvider.getIfAvailable();
        if (git != null) {
            repos.add(git);
        }

        log.info("构建 HarnessAgent agentKey={} modelId={} workspace={} boundSkills={} skillRepos={}",
                agentKey, agentDO.getModelId(), workspace, bound, repos.size());
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(agentKey)
                .description(agentDO.getDescription() == null ? agentDO.getName() : agentDO.getDescription())
                .sysPrompt(agentDO.getSysPrompt())
                .model(modelRegistry.resolve(agentDO.getModelId()))
                .workspace(workspace)
                .stateStore(stateStore)
                .skillRepositories(repos)
                .skillFilter(skillFilter)
                .compaction(CompactionConfig.builder()
                        .triggerMessages(trigger)
                        .keepMessages(keep)
                        .build());
        applySandbox(builder, agentKey, agentDO);
        return builder.build();
    }

    /**
     * 按 feature.sandbox 装配沙箱（SPEC §16.7 / 修订）：
     * 未启用 → disableShellTool（脚本仅分发不执行）；
     * 启用 → 按 sandbox.link 配置路由（e2b / agentrun），首选不可用时自动回落。
     */
    private void applySandbox(HarnessAgent.Builder builder, String agentKey, AgentDO agentDO) {
        AgentFeature.Sandbox sb = AgentFeature.parse(agentDO.getFeature()).getSandbox();
        if (sb == null || !sb.isEnabled()) {
            // 无沙箱：禁用 shell 工具，脚本类 skill 仅分发不执行（SPEC §1.1/风险 7）
            builder.disableShellTool();
            return;
        }
        String link = resolveSandboxLink();
        if (link == null) {
            // 防御：正常已在保存时拦截（AgentService 校验），此处兜底降级不阻断对话
            log.error("沙箱链路未接入但 Agent 已启用沙箱，降级为无 shell agentKey={}", agentKey);
            builder.disableShellTool();
            return;
        }
        if ("e2b".equals(link)) {
            applyE2b(builder, agentKey, sb);
            return;
        }
        TeapotAiProperties.Sandbox.Agentrun defaults = properties.getSandbox().getAgentrun();
        String persistence = sb.getPersistence() == null ? "LOCAL_SNAPSHOT" : sb.getPersistence();
        AgentRunFilesystemSpec spec = new AgentRunFilesystemSpec()
                .apiKey(agentRunConnection.getApiKey())
                .accountId(agentRunConnection.getAccountId())
                .region(agentRunConnection.getRegion())
                .templateName(resolveTemplate(sb))
                .mcpServerUrl(agentRunConnection.getMcpServerUrl())
                .workspaceRoot(sb.getWorkspaceRoot() != null
                        ? sb.getWorkspaceRoot() : defaults.getDefaultWorkspaceRoot())
                .sandboxIdleTimeoutSeconds(sb.getIdleTimeoutSeconds() != null
                        ? sb.getIdleTimeoutSeconds() : defaults.getDefaultIdleTimeoutSeconds());
        // isolationScope 为基类方法（返回基类型），不参与子类 fluent 链
        spec.isolationScope(IsolationScope.valueOf(
                sb.getIsolationScope() == null ? "SESSION" : sb.getIsolationScope()));
        switch (persistence) {
            case "NONE" -> spec.snapshotSpec(new NoopSnapshotSpec());
            case "NAS" -> {
                // workspaceRoot 落挂载目录内，快照自动退化为 no-op（官方 Branch A）
                spec.snapshotSpec(new NoopSnapshotSpec());
                spec.nasConfig(toNasConfig(sb.getNas()));
            }
            default -> spec.snapshotSpec(new LocalSnapshotSpec(
                    Path.of(defaults.getSnapshotPath()).resolve(agentKey).toString()));
        }
        builder.filesystem(spec);
        log.info("Agent 沙箱已启用 agentKey={} isolation={} persistence={} template={}",
                agentKey, spec.getIsolationScope(), persistence, resolveTemplate(sb));
    }

    /**
     * 链路路由（sandbox.link 配置项）：首选链路 enabled 且凭证齐备则用之，
     * 否则回落另一链路；两者都不可用返回 null（降级无 shell）。
     */
    private String resolveSandboxLink() {
        TeapotAiProperties.Sandbox cfg = properties.getSandbox();
        String preferred = cfg.getLink() == null ? "e2b" : cfg.getLink().trim().toLowerCase();
        boolean e2bUsable = cfg.getE2b().isEnabled() && agentRunConnection.e2bConfigured();
        boolean agentrunUsable = cfg.getAgentrun().isEnabled() && agentRunConnection.configured();
        boolean preferE2b = !"agentrun".equals(preferred);
        if (preferE2b) {
            if (e2bUsable) {
                return "e2b";
            }
            if (agentrunUsable) {
                log.warn("E2B 链路未启用/凭证不齐，回落 AgentRun MCP 链路");
                return "agentrun";
            }
        } else {
            if (agentrunUsable) {
                return "agentrun";
            }
            if (e2bUsable) {
                log.warn("AgentRun MCP 链路未启用/凭证不齐，回落 E2B 链路");
                return "e2b";
            }
        }
        return null;
    }

    /**
     * E2B 兼容链路装配（AgentRun E2B 端点，CLI 实测 template list/spawn 均可用）。
     * NAS 挂载为 AgentRun MCP 专属能力，E2B 链路降级为 no-op 快照并告警。
     */
    private void applyE2b(HarnessAgent.Builder builder, String agentKey, AgentFeature.Sandbox sb) {
        TeapotAiProperties.Sandbox.E2b defaults = properties.getSandbox().getE2b();
        String persistence = sb.getPersistence() == null ? "LOCAL_SNAPSHOT" : sb.getPersistence();
        String workspaceRoot = sb.getWorkspaceRoot() != null
                ? sb.getWorkspaceRoot() : defaults.getDefaultWorkspaceRoot();
        E2bFilesystemSpec spec = new E2bFilesystemSpec()
                .apiKey(agentRunConnection.getE2bApiKey())
                .apiBaseUrl(agentRunConnection.getE2bApiBaseUrl())
                .domain(agentRunConnection.getE2bDomain())
                .templateId(resolveE2bTemplate(sb))
                .workspaceRoot(workspaceRoot)
                .sandboxTimeoutSeconds(sb.getIdleTimeoutSeconds() != null
                        ? sb.getIdleTimeoutSeconds() : defaults.getDefaultIdleTimeoutSeconds())
                // 阿里云 E2B 兼容端点的 envd 不支持 connect+proto（HTTP 400 JSON 解析错），配置项默认 JSON
                .codec(E2bCodec.valueOf(defaults.getCodec().trim().toUpperCase()));
        // 工作区根必须同时经 WorkspaceSpec 下发：harness 默认 root=/workspace，
        // E2B 模板内 user 无权在根目录建目录，会 WorkspaceStartException
        WorkspaceSpec ws = new WorkspaceSpec();
        ws.setRoot(workspaceRoot);
        spec.workspaceSpec(ws);
        spec.isolationScope(IsolationScope.valueOf(
                sb.getIsolationScope() == null ? "SESSION" : sb.getIsolationScope()));
        switch (persistence) {
            case "NONE", "NAS" -> {
                if ("NAS".equals(persistence)) {
                    log.warn("E2B 链路不支持 NAS 挂载，快照降级为 no-op agentKey={}", agentKey);
                }
                spec.snapshotSpec(new NoopSnapshotSpec());
            }
            default -> spec.snapshotSpec(new LocalSnapshotSpec(
                    Path.of(defaults.getSnapshotPath()).resolve(agentKey).toString()));
        }
        builder.filesystem(spec);
        log.info("Agent 沙箱已启用(E2B) agentKey={} isolation={} persistence={} template={}",
                agentKey, spec.getIsolationScope(), persistence, resolveE2bTemplate(sb));
    }

    /** E2B 模板解析：feature 覆盖 → E2B 全局默认 → AgentRun 全局默认（同账号模板互通） */
    private String resolveE2bTemplate(AgentFeature.Sandbox sb) {
        if (sb.getTemplateName() != null && !sb.getTemplateName().isBlank()) {
            return sb.getTemplateName();
        }
        String t = agentRunConnection.getE2bDefaultTemplate();
        if (t == null || t.isBlank()) {
            t = agentRunConnection.getDefaultTemplate();
        }
        if (t == null || t.isBlank()) {
            throw new BizException("沙箱模板未配置：请在 Agent 配置 templateName 或全局默认模板");
        }
        return t;
    }

    /** feature 模板覆盖全局默认（SPEC §16.6 templateName） */
    private String resolveTemplate(AgentFeature.Sandbox sb) {
        if (sb.getTemplateName() != null && !sb.getTemplateName().isBlank()) {
            return sb.getTemplateName();
        }
        String defaultTemplate = agentRunConnection.getDefaultTemplate();
        if (defaultTemplate == null || defaultTemplate.isBlank()) {
            throw new BizException("沙箱模板未配置：请在 Agent 配置 templateName 或全局默认模板");
        }
        return defaultTemplate;
    }

    private AgentRunNasMountConfig toNasConfig(AgentFeature.Sandbox.Nas nas) {
        return new AgentRunNasMountConfig()
                .setServerAddr(nas.getServerAddr())
                .setMountDir(nas.getMountDir())
                .setRemotePath(nas.getRemotePath() == null ? "/" : nas.getRemotePath())
                .setEnableTLS(nas.isEnableTLS());
    }
}
