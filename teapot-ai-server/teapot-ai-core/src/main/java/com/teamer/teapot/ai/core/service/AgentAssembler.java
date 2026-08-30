package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.agentscope.DangerousCommandGuardMiddleware;
import com.teamer.teapot.ai.core.agentscope.McpConfigToolMiddleware;
import com.teamer.teapot.ai.core.agentscope.McpConfigTools;
import com.teamer.teapot.ai.core.agentscope.MediaGenToolMiddleware;
import com.teamer.teapot.ai.core.agentscope.OssFileTools;
import com.teamer.teapot.ai.core.agentscope.OssToolMiddleware;
import com.teamer.teapot.ai.core.agentscope.PerMessageCheckpointMiddleware;
import com.teamer.teapot.ai.core.agentscope.PermissionModeMiddleware;
import com.teamer.teapot.ai.core.agentscope.ToolProvidedMiddleware;
import com.teamer.teapot.ai.core.config.AgentRunConnection;
import com.teamer.teapot.ai.core.config.OssConnection;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.dao.AgentMapper;
import com.teamer.teapot.ai.core.dao.AgentSkillMapper;
import com.teamer.teapot.ai.core.model.AgentDO;
import com.teamer.teapot.ai.core.model.AgentFeature;
import com.teamer.teapot.ai.core.model.SandboxConfigDO;
import com.teamer.teapot.ai.core.model.MCPConfigDO;
import com.teamer.teapot.ai.core.service.MCPConfigService;
import com.teamer.teapot.ai.core.storage.OssClientManager;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import com.teamer.teapot.ai.core.storage.OssSkillRepository;
import com.teamer.teapot.ai.core.storage.RedisMemoryFilesystems;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import io.agentscope.extensions.sandbox.agentrun.AgentRunFilesystemSpec;
import io.agentscope.extensions.sandbox.agentrun.AgentRunNasMountConfig;
import io.agentscope.extensions.sandbox.e2b.E2bCodec;
import io.agentscope.extensions.sandbox.e2b.E2bFilesystemSpec;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import io.agentscope.harness.agent.tools.ToolsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HarnessAgent 装配器（SPEC §24.2/§24.4）：
 * t_agent 记录 → HarnessAgent 的统一装配规则，Web 链路（AgentRegistry 每轮重建）
 * 与 channel 链路（ChannelHub 长驻实例）共享同一份 model/skill/sandbox/stateStore 装配。
 * extraMiddlewares 供调用方按链路追加（channel 链路追加会话索引中间件，Web 链路为空）。
 */
@Slf4j
@Component
public class AgentAssembler {

    private static final int DEFAULT_COMPACTION_TRIGGER = 30;
    private static final int DEFAULT_COMPACTION_KEEP = 10;

    private final AgentMapper agentMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final ModelRegistry modelRegistry;
    private final AgentStateStore stateStore;
    private final MysqlSkillRepository skillRepositoryAgent;
    private final TeapotAiProperties properties;
    /** Git skill 来源（enabled=false 时缺席，SPEC §15.6） */
    private final ObjectProvider<GitSkillRepository> gitRepoProvider;
    /** OSS skill 来源（enabled=false 时缺席：zip 导入 → OSS 对象挂载） */
    private final ObjectProvider<OssSkillRepository> ossRepoProvider;
    /** 记忆文件系统路由（memory-store=false 时缺席，SPEC §27） */
    private final ObjectProvider<RedisMemoryFilesystems> memoryRoutesProvider;
    private final AgentRunConnection agentRunConnection;
    private final SandboxConfigService sandboxConfigService;
    /** MCP 系统配置服务（Agent 内联配置时不需，引用模式时需查 t_mcp_config） */
    private final MCPConfigService mcpConfigService;
    /** OSS 客户端管理与全局连接（工具提供型中间件：upload_file / download_file） */
    private final OssClientManager ossClientManager;
    private final OssConnection ossConnection;
    /** 生图/生视频工具密钥（SPEC-media-gen §4.1：复用现有环境变量，不落库） */
    @Value("${DASHSCOPE_API_KEY:}")
    private String dashscopeApiKey;

    public AgentAssembler(AgentMapper agentMapper, AgentSkillMapper agentSkillMapper,
                          ModelRegistry modelRegistry, AgentStateStore stateStore,
                          @Qualifier("skillRepositoryAgent") MysqlSkillRepository skillRepositoryAgent,
                          TeapotAiProperties properties,
                          ObjectProvider<GitSkillRepository> gitRepoProvider,
                          ObjectProvider<OssSkillRepository> ossRepoProvider,
                          ObjectProvider<RedisMemoryFilesystems> memoryRoutesProvider,
                          AgentRunConnection agentRunConnection,
                          SandboxConfigService sandboxConfigService,
                          MCPConfigService mcpConfigService,
                          OssClientManager ossClientManager,
                          OssConnection ossConnection) {
        this.agentMapper = agentMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.modelRegistry = modelRegistry;
        this.stateStore = stateStore;
        this.skillRepositoryAgent = skillRepositoryAgent;
        this.properties = properties;
        this.gitRepoProvider = gitRepoProvider;
        this.ossRepoProvider = ossRepoProvider;
        this.memoryRoutesProvider = memoryRoutesProvider;
        this.agentRunConnection = agentRunConnection;
        this.sandboxConfigService = sandboxConfigService;
        this.mcpConfigService = mcpConfigService;
        this.ossClientManager = ossClientManager;
        this.ossConnection = ossConnection;
    }

    /** 按 t_agent 记录装配 HarnessAgent；extraMiddlewares 允许为空 */
    public HarnessAgent assemble(String agentKey, List<MiddlewareBase> extraMiddlewares) {
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
        AgentFeature feature = AgentFeature.parse(agentDO.getFeature());
        AgentFeature.Sandbox sb = feature.getSandbox();
        AgentFeature.Runtime rt = feature.getRuntime();
        boolean thinking = rt != null && Boolean.TRUE.equals(rt.getThinkingMode());

        // skill 多来源（SPEC §15.7 扩展）：[mysql 只读, git?, oss?]，SkillFilter 按 name 跨来源过滤
        List<AgentSkillRepository> repos = new ArrayList<>();
        repos.add(skillRepositoryAgent);
        GitSkillRepository git = gitRepoProvider.getIfAvailable();
        if (git != null) {
            repos.add(git);
        }
        OssSkillRepository oss = ossRepoProvider.getIfAvailable();
        if (oss != null) {
            repos.add(oss);
        }

        log.info("构建 HarnessAgent agentKey={} modelId={} thinking={} workspace={} boundSkills={} skillRepos={}",
                agentKey, agentDO.getModelId(), thinking, workspace, bound, repos.size());
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(agentKey)
                .description(agentDO.getDescription() == null ? agentDO.getName() : agentDO.getDescription())
                .sysPrompt(agentDO.getSysPrompt())
                .model(modelRegistry.resolve(agentDO.getModelId(), thinking))
                .workspace(workspace)
                .stateStore(stateStore)
                .skillRepositories(repos)
                .skillFilter(skillFilter);
        // compaction：trigger/keep 任一为 -1 表示关闭压缩，其余走既有配置
        if (trigger < 0 || keep < 0) {
            builder.disableCompaction();
        } else {
            builder.compaction(CompactionConfig.builder()
                    .triggerMessages(trigger)
                    .keepMessages(keep)
                    .build());
        }
        applyRuntime(builder, rt);
        // 请求级计划模式覆盖（chat 界面 forwardedProps.planMode，SPEC §25）：
        // 优先于 Agent 配置；null = 未传参，回落 applyRuntime 已设置的值
        Boolean planHint = AgentRuntimeHints.getPlanMode();
        if (planHint != null) {
            builder.enablePlanMode(planHint);
            log.info("Agent 计划模式已覆盖（请求级开关={} 配置={}）", planHint,
                    rt != null && Boolean.TRUE.equals(rt.getEnablePlanMode()));
        }
        // 权限模式（permission-system）：chat 面板请求级提示优先于 Agent 配置；
        // 须在 applyMemory 清理 ThreadLocal 之前读取；null = 不设权限上下文（存量行为）
        String permHint = AgentRuntimeHints.getPermissionMode();
        String permConfig = rt == null ? null : rt.getPermissionMode();
        String permMode = permHint != null ? permHint : permConfig;
        // MultiAgent（SPEC §25）：关闭时剥夺 subagent 委派能力；缺省（无命名空间）启用，对齐 SDK 默认
        AgentFeature.MultiAgent ma = feature.getMultiAgent();
        if (ma != null && !ma.isEnabled()) {
            builder.disableSubagents();
            builder.disableDynamicSubagents();
        }
        // 记忆（SPEC §25）：请求级开关（chat 界面参数）优先于 Agent 配置
        applyMemory(builder, feature.getMemory());
        // shell 门控：显式配置优先；未配置（存量）跟随沙箱启用，保证存量行为不回退
        boolean shellEnabled = rt != null && rt.getEnableShell() != null
                ? rt.getEnableShell() : (sb != null && sb.isEnabled());
        if (!shellEnabled) {
            builder.disableShellTool();
        }
        boolean sandboxApplied = applySandbox(builder, agentKey, sb);
        // 记忆路由（SPEC §27）：memory-store 启用时，非沙箱（含降级）路径的 MEMORY.md/memory/ 改走 Redis；
        // 沙箱文件系统为 2.0.1 固定实现，无路由挂载点，记忆留在沙箱。降级路径已禁 shell，路由仍保留本地叠加能力
        if (!sandboxApplied) {
            RedisMemoryFilesystems memoryRoutes = memoryRoutesProvider.getIfAvailable();
            if (memoryRoutes != null) {
                builder.abstractFilesystem(memoryRoutes.localShellOverlay(agentKey, workspace));
                log.info("记忆文件系统已路由到 Redis agentKey={}", agentKey);
            }
        }
        // 工具提供型中间件（OSS 文件 / MCP 配置，按 runtime 开关）：
        // 中间件须在 build 前挂载（onSystemPrompt 注入），工具在 build 后注册到 Toolkit（MCP 同款链路）
        List<ToolProvidedMiddleware> toolMiddlewares = buildToolMiddlewares(agentKey, workspace, rt, feature.getMcp());
        if (extraMiddlewares != null) {
            for (MiddlewareBase middleware : extraMiddlewares) {
                builder.middleware(middleware);
            }
        }
        for (ToolProvidedMiddleware middleware : toolMiddlewares) {
            builder.middleware(middleware);
        }
        // 消息级落盘（SPEC-checkpoint-persist §3.1）：阶段入口 checkpoint，与轮末落盘叠加；
        // Web（AgentRegistry）与 channel（ChannelHub）链路共用本装配点，同时生效
        if (properties.getAgentscope().isCheckpointPerMessage()) {
            builder.middleware(new PerMessageCheckpointMiddleware());
        }
        // 权限模式（permission-system）：EXPLORE=只读探索；BYPASS=全部放行；
        // BLOCK_DANGEROUS=BYPASS + 危险命令守卫（2.0.1 shell 工具不支持内容级规则，守卫由中间件实现）。
        // PermissionModeMiddleware 在每轮调用入口把生效模式写入会话槽位（含已持久化的旧槽位），
        // 保证「chat 面板 > Agent 配置」按请求收敛；permissionContext 同时作为新建槽位的初始值
        if (permMode != null) {
            PermissionMode pm = "EXPLORE".equals(permMode) ? PermissionMode.EXPLORE : PermissionMode.BYPASS;
            PermissionContextState permCtx = PermissionContextState.builder().mode(pm).build();
            builder.permissionContext(permCtx);
            builder.middleware(new PermissionModeMiddleware(permCtx));
            if ("BLOCK_DANGEROUS".equals(permMode)) {
                builder.middleware(new DangerousCommandGuardMiddleware());
            }
            log.info("权限模式已生效 agentKey={} mode={} 来源={}",
                    agentKey, permMode, permHint != null ? "chat 面板" : "Agent 配置");
        }
        HarnessAgent agent = builder.build();
        for (ToolProvidedMiddleware middleware : toolMiddlewares) {
            Object tools = middleware.providedTools();
            if (tools != null && agent.getToolkit() != null) {
                agent.getToolkit().registerTool(tools);
            }
        }
        // MCP Server 注册（SPEC §MCP）：Agent 级配置，引用系统记录或内联完整配置
        applyMcp(agent, feature.getMcp());
        return agent;
    }

    /**
     * 工具提供型中间件构建（ToolProvidedMiddleware）：
     * runtime.enableOssFile → OssToolMiddleware（upload_file / download_file + system prompt 用法注入）；
     * runtime.enableMcpConfig → McpConfigToolMiddleware（list_mcp_servers / get_mcp_server）；
     * runtime.enableMediaGen → MediaGenToolMiddleware（DashScope 生图/生视频，密钥缺省不挂载，SPEC-media-gen §4.1）。
     * 开关缺省/关闭均不挂载，存量行为不变。
     */
    private List<ToolProvidedMiddleware> buildToolMiddlewares(String agentKey, Path workspace,
                                                              AgentFeature.Runtime rt, AgentFeature.MCP mcp) {
        List<ToolProvidedMiddleware> middlewares = new ArrayList<>();
        if (rt != null && Boolean.TRUE.equals(rt.getEnableOssFile())) {
            middlewares.add(new OssToolMiddleware(new OssFileTools(workspace, ossClientManager, ossConnection)));
            log.info("OSS 文件工具已启用 agentKey={}", agentKey);
        }
        if (rt != null && Boolean.TRUE.equals(rt.getEnableMcpConfig())) {
            middlewares.add(new McpConfigToolMiddleware(new McpConfigTools(agentKey, mcpConfigService, mcp)));
            log.info("MCP 配置查询工具已启用 agentKey={}", agentKey);
        }
        if (rt != null && Boolean.TRUE.equals(rt.getEnableMediaGen())) {
            if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) {
                log.warn("生图/生视频已启用但 DASHSCOPE_API_KEY 未配置，跳过挂载 agentKey={}", agentKey);
            } else {
                middlewares.add(new MediaGenToolMiddleware(new DashScopeMultiModalTool(dashscopeApiKey)));
                log.info("生图/生视频工具已启用 agentKey={}", agentKey);
            }
        }
        return middlewares;
    }

    /**
     * runtime 命名空间 → HarnessAgent 映射（Agent 高级配置）：
     * 采样参数 → GenerateOptions；maxIterations → maxIters；
     * enablePlanMode → 计划模式；allowedTools → ToolsConfig 白名单。
     */
    private void applyRuntime(HarnessAgent.Builder builder, AgentFeature.Runtime rt) {
        if (rt == null) {
            return;
        }
        if (rt.getTemperature() != null || rt.getTopP() != null || rt.getMaxTokens() != null) {
            GenerateOptions.Builder go = GenerateOptions.builder();
            if (rt.getTemperature() != null) {
                go.temperature(rt.getTemperature());
            }
            if (rt.getTopP() != null) {
                go.topP(rt.getTopP());
            }
            if (rt.getMaxTokens() != null) {
                go.maxTokens(rt.getMaxTokens());
            }
            builder.generateOptions(go.build());
        }
        if (rt.getMaxIterations() != null) {
            builder.maxIters(rt.getMaxIterations());
        }
        if (Boolean.TRUE.equals(rt.getEnablePlanMode())) {
            builder.enablePlanMode(true);
        }
        if (rt.getAllowedTools() != null && !rt.getAllowedTools().isEmpty()) {
            ToolsConfig toolsConfig = new ToolsConfig();
            toolsConfig.setAllow(rt.getAllowedTools());
            builder.toolsConfig(toolsConfig);
        }
    }

    /**
     * memory 命名空间 → HarnessAgent 映射（SPEC §25，对齐官方 Memory 文档）：
     * 请求级提示（chat 界面 forwardedProps.memoryMode，ThreadLocal 同线程传递）优先于 Agent 配置；
     * 关闭 = disableMemoryHooks（flush+后台维护）+ disableMemoryTools（memory_search 等）；
     * 开启时按配置设 flush 触发策略（always / never / throttled，缺省 always = SDK 默认）。
     */
    private void applyMemory(HarnessAgent.Builder builder, AgentFeature.Memory mem) {
        Boolean hint = AgentRuntimeHints.getMemoryMode();
        AgentRuntimeHints.clear();
        boolean enabled = hint != null
                ? hint
                : (mem == null || mem.getEnabled() == null || mem.getEnabled());
        if (!enabled) {
            builder.disableMemoryHooks();
            builder.disableMemoryTools();
            log.info("Agent 记忆已关闭（请求级开关={} 配置={}）", hint, mem == null ? "-" : mem.getEnabled());
            return;
        }
        if (mem != null && mem.getFlushTrigger() != null) {
            MemoryConfig.FlushTrigger trigger = switch (mem.getFlushTrigger()) {
                case "never" -> MemoryConfig.FlushTrigger.never();
                case "throttled" -> MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(
                        mem.getFlushThrottleMinutes() == null ? 10 : mem.getFlushThrottleMinutes()));
                default -> MemoryConfig.FlushTrigger.always();
            };
            builder.memory(MemoryConfig.builder().flushTrigger(trigger).build());
        }
    }

    /**
     * MCP Server 注册（Agent 级配置，不依赖系统配置）：
     * 每条 server 可引用系统记录（record）或内联完整配置（transport + command/url）；
     * 引用记录时从 t_mcp_config 查完整配置，内联时直接使用。
     * 通过 McpServerRegistrar.register() 将 MCP 工具注册到 HarnessAgent 的 Toolkit。
     */
    private void applyMcp(HarnessAgent agent, AgentFeature.MCP mcp) {
        if (mcp == null || !mcp.isEnabled() || mcp.getServers() == null || mcp.getServers().isEmpty()) {
            return;
        }
        Map<String, McpServerConfig> configs = new LinkedHashMap<>();
        for (AgentFeature.MCP.Server srv : mcp.getServers()) {
            McpServerConfig cfg = resolveMcpServer(srv);
            if (cfg != null) {
                String name = srv.getRecord() != null ? srv.getRecord() : (srv.getCommand() != null ? srv.getCommand() : srv.getUrl());
                configs.put(name, cfg);
            }
        }
        if (configs.isEmpty()) {
            return;
        }
        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) {
            log.warn("MCP 配置已启用但 HarnessAgent Toolkit 为空，跳过 MCP 注册 agentKey 将在运行时生效");
            return;
        }
        McpServerRegistrar.register(toolkit, configs);
        log.info("Agent MCP Server 已注册 agentKey={} count={}", agent.getName(), configs.size());
    }

    /** 将单条 AgentFeature.MCP.Server 解析为 SDK McpServerConfig（引用记录时查库，内联时直接构造） */
    private McpServerConfig resolveMcpServer(AgentFeature.MCP.Server srv) {
        McpServerConfig cfg = new McpServerConfig();
        if (srv.getRecord() != null && !srv.getRecord().isBlank()) {
            // 引用系统记录：从 t_mcp_config 查完整配置
            MCPConfigDO row = mcpConfigService.getByName(srv.getRecord());
            if (row == null) {
                log.warn("MCP Server 引用记录不存在，跳过 record={}", srv.getRecord());
                return null;
            }
            if (!row.getEnabled()) {
                log.warn("MCP Server 引用记录已禁用，跳过 record={}", srv.getRecord());
                return null;
            }
            cfg.setTransport(row.getTransport());
            cfg.setCommand(row.getCommand());
            cfg.setArgs(MCPConfigService.parseArgs(row.getArgs()));
            cfg.setEnv(MCPConfigService.parseMap(row.getEnv()));
            cfg.setUrl(row.getUrl());
            cfg.setHeaders(MCPConfigService.parseMap(row.getHeaders()));
        } else {
            // 内联配置：直接使用
            cfg.setTransport(srv.getTransport());
            cfg.setCommand(srv.getCommand());
            cfg.setArgs(srv.getArgs());
            cfg.setEnv(srv.getEnv());
            cfg.setUrl(srv.getUrl());
            cfg.setHeaders(srv.getHeaders());
        }
        return cfg;
    }

    /**
     * 按 feature.sandbox 装配沙箱（SPEC §16.7 / §22.2 修订）：
     * shell 工具门控由 assemble() 统一负责（runtime.enableShell 优先，存量跟随沙箱）；
     * 启用且指定 sandboxRecord → 链路与凭证由记录决定（§22.2）；
     * 启用无记录（存量）→ 按 sandbox.link 全局路由（e2b / agentrun），首选不可用时自动回落。
     * 返回是否实际装配了沙箱文件系统（false = 非沙箱或降级，供记忆路由决策，SPEC §27）。
     */
    private boolean applySandbox(HarnessAgent.Builder builder, String agentKey, AgentFeature.Sandbox sb) {
        if (sb == null || !sb.isEnabled()) {
            // 无沙箱：不装配文件系统，shell 门控见 assemble()
            return false;
        }
        // §22.2 记录优先：指定 sandboxRecord 时链路与凭证由记录决定
        SandboxConfigDO record = null;
        if (sb.getSandboxRecord() != null && !sb.getSandboxRecord().isBlank()) {
            record = sandboxConfigService.getPlain(sb.getSandboxRecord().trim());
            if (!SandboxConfigService.linkConfigured(record)) {
                // 防御：正常已在保存时拦截（AgentService 校验），此处兜底降级不阻断对话
                log.error("沙箱记录不可用（不存在或凭证不齐）record={} agentKey={}，降级为无 shell",
                        sb.getSandboxRecord(), agentKey);
                builder.disableShellTool();
                return false;
            }
        }
        String link = record != null ? record.getLinkType() : resolveSandboxLink(sb.getLink());
        if (link == null) {
            // 防御：正常已在保存时拦截（AgentService 校验），此处兜底降级不阻断对话
            log.error("沙箱链路未接入但 Agent 已启用沙箱，降级为无 shell agentKey={}", agentKey);
            builder.disableShellTool();
            return false;
        }
        if ("e2b".equals(link)) {
            applyE2b(builder, agentKey, sb, record);
            return true;
        }
        TeapotAiProperties.Sandbox.Agentrun defaults = properties.getSandbox().getAgentrun();
        String persistence = sb.getPersistence() == null ? "LOCAL_SNAPSHOT" : sb.getPersistence();
        AgentRunFilesystemSpec spec = new AgentRunFilesystemSpec()
                .apiKey(record != null ? record.getApiKey() : agentRunConnection.getApiKey())
                .accountId(record != null ? record.getAccountId() : agentRunConnection.getAccountId())
                .region(record != null && notBlank(record.getRegion())
                        ? record.getRegion() : agentRunConnection.getRegion())
                .templateName(resolveTemplate(sb, record))
                .mcpServerUrl(record != null ? record.getMcpServerUrl() : agentRunConnection.getMcpServerUrl())
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
        log.info("Agent 沙箱已启用 agentKey={} record={} isolation={} persistence={} template={}",
                agentKey, record == null ? "-" : record.getName(),
                spec.getIsolationScope(), persistence, resolveTemplate(sb, record));
        return true;
    }

    /**
     * 链路路由（SPEC §21.4）：优先级 agent 级 feature.sandbox.link > 全局 sandbox.link 配置；
     * agent 显式指定的链路不可用时回落自动路由；首选链路 enabled 且凭证齐备则用之，
     * 否则回落另一链路；两者都不可用返回 null（降级无 shell）。
     */
    private String resolveSandboxLink(String agentLink) {
        TeapotAiProperties.Sandbox cfg = properties.getSandbox();
        boolean e2bUsable = cfg.getE2b().isEnabled() && agentRunConnection.e2bConfigured();
        boolean agentrunUsable = cfg.getAgentrun().isEnabled() && agentRunConnection.configured();
        // agent 级覆盖：显式指定且可用则直接生效
        if (agentLink != null && !agentLink.isBlank() && !"auto".equalsIgnoreCase(agentLink)) {
            String wanted = agentLink.trim().toLowerCase();
            if (("e2b".equals(wanted) && e2bUsable) || ("agentrun".equals(wanted) && agentrunUsable)) {
                return wanted;
            }
            log.warn("Agent 指定沙箱链路 {} 不可用，回落全局自动路由", wanted);
        }
        String preferred = cfg.getLink() == null ? "e2b" : cfg.getLink().trim().toLowerCase();
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
     * record 非空时凭证取记录（§22.2），否则取全局 AgentRunConnection（存量兼容）。
     */
    private void applyE2b(HarnessAgent.Builder builder, String agentKey, AgentFeature.Sandbox sb,
                          SandboxConfigDO record) {
        TeapotAiProperties.Sandbox.E2b defaults = properties.getSandbox().getE2b();
        String persistence = sb.getPersistence() == null ? "LOCAL_SNAPSHOT" : sb.getPersistence();
        String workspaceRoot = sb.getWorkspaceRoot() != null
                ? sb.getWorkspaceRoot() : defaults.getDefaultWorkspaceRoot();
        E2bFilesystemSpec spec = new E2bFilesystemSpec()
                .apiKey(record != null ? record.getE2bApiKey() : agentRunConnection.getE2bApiKey())
                .apiBaseUrl(record != null ? record.getE2bApiBaseUrl() : agentRunConnection.getE2bApiBaseUrl())
                .domain(record != null ? record.getE2bDomain() : agentRunConnection.getE2bDomain())
                .templateId(resolveE2bTemplate(sb, record))
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
        log.info("Agent 沙箱已启用(E2B) agentKey={} record={} isolation={} persistence={} template={}",
                agentKey, record == null ? "-" : record.getName(),
                spec.getIsolationScope(), persistence, resolveE2bTemplate(sb, record));
    }

    /** E2B 模板解析：feature 覆盖 → 记录默认 → E2B 全局默认 → AgentRun 全局默认（同账号模板互通） */
    private String resolveE2bTemplate(AgentFeature.Sandbox sb, SandboxConfigDO record) {
        if (sb.getTemplateName() != null && !sb.getTemplateName().isBlank()) {
            return sb.getTemplateName();
        }
        if (record != null) {
            if (notBlank(record.getE2bDefaultTemplate())) {
                return record.getE2bDefaultTemplate();
            }
            if (notBlank(record.getDefaultTemplate())) {
                return record.getDefaultTemplate();
            }
        }
        String t = agentRunConnection.getE2bDefaultTemplate();
        if (t == null || t.isBlank()) {
            t = agentRunConnection.getDefaultTemplate();
        }
        if (t == null || t.isBlank()) {
            throw new BizException("沙箱模板未配置：请在 Agent 配置 templateName 或记录/全局默认模板");
        }
        return t;
    }

    /** feature 模板覆盖记录/全局默认（SPEC §16.6 templateName） */
    private String resolveTemplate(AgentFeature.Sandbox sb, SandboxConfigDO record) {
        if (sb.getTemplateName() != null && !sb.getTemplateName().isBlank()) {
            return sb.getTemplateName();
        }
        if (record != null && notBlank(record.getDefaultTemplate())) {
            return record.getDefaultTemplate();
        }
        String defaultTemplate = agentRunConnection.getDefaultTemplate();
        if (defaultTemplate == null || defaultTemplate.isBlank()) {
            throw new BizException("沙箱模板未配置：请在 Agent 配置 templateName 或记录/全局默认模板");
        }
        return defaultTemplate;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private AgentRunNasMountConfig toNasConfig(AgentFeature.Sandbox.Nas nas) {
        return new AgentRunNasMountConfig()
                .setServerAddr(nas.getServerAddr())
                .setMountDir(nas.getMountDir())
                .setRemotePath(nas.getRemotePath() == null ? "/" : nas.getRemotePath())
                .setEnableTLS(nas.isEnableTLS());
    }
}
