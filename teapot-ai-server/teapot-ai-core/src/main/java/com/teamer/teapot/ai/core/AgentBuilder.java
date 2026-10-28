package com.teamer.teapot.ai.core;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.middleware.DangerousCommandGuardMiddleware;
import com.teamer.teapot.ai.core.middleware.LlmIntentGuardMiddleware;
import com.teamer.teapot.ai.core.middleware.SessionTitleGenMiddleware;
import com.teamer.teapot.ai.core.mcp.McpConfigToolMiddleware;
import com.teamer.teapot.ai.core.mcp.McpConfigTools;
import com.teamer.teapot.ai.core.middleware.MediaGenToolMiddleware;
import com.teamer.teapot.ai.core.model.*;
import com.teamer.teapot.ai.core.tool.MediaArtifactPersistTool;
import com.teamer.teapot.ai.core.tool.MediaModelCatalog;
import com.teamer.teapot.ai.core.middleware.MediaModalGuardMiddleware;
import com.teamer.teapot.ai.core.tool.OssFileTools;
import com.teamer.teapot.ai.core.middleware.OssToolMiddleware;
import com.teamer.teapot.ai.core.middleware.PerMessageCheckpointMiddleware;
import com.teamer.teapot.ai.core.middleware.PermissionModeMiddleware;
import com.teamer.teapot.ai.core.middleware.ToolProvidedMiddleware;
import com.teamer.teapot.ai.core.middleware.UserAttachmentRefMiddleware;
import com.teamer.teapot.ai.core.middleware.feature.FeatureMiddlewareRegistry;
import com.teamer.teapot.ai.core.sandbox.agentrun.AgentRunConnection;
import com.teamer.teapot.ai.core.sandbox.e2b.E2bConnection;
import com.teamer.teapot.ai.core.oss.OssConnection;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.crypto.ConfigCryptoService;
import com.teamer.teapot.ai.core.dao.AgentMapper;
import com.teamer.teapot.ai.core.dao.AgentSkillMapper;
import com.teamer.teapot.ai.core.dao.ChatSessionMapper;
import com.teamer.teapot.ai.core.service.AgentRuntimeHints;
import com.teamer.teapot.ai.core.service.MCPConfigService;
import com.teamer.teapot.ai.core.service.ModelRegistry;
import com.teamer.teapot.ai.core.service.SandboxConfigService;
import com.teamer.teapot.ai.core.storage.ImageStorageRouter;
import com.teamer.teapot.ai.core.storage.OssClientManager;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import com.teamer.teapot.ai.core.storage.OssSkillRepository;
import com.teamer.teapot.ai.core.storage.RedisMemoryFilesystems;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolGroupScope;
import io.agentscope.extensions.a2ui.A2uiConfig;
import io.agentscope.extensions.a2ui.A2uiMiddleware;
import io.agentscope.extensions.a2ui.ToolInstallingMiddleware;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.example.JevIntentGuardMiddleware;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import io.agentscope.extensions.sandbox.agentrun.AgentRunFilesystemSpec;
import io.agentscope.extensions.sandbox.agentrun.AgentRunNasMountConfig;
import io.agentscope.extensions.sandbox.e2b.E2bCodec;
import io.agentscope.extensions.sandbox.e2b.E2bFilesystemSpec;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
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
import java.util.Set;

/**
 * HarnessAgent 装配器（SPEC §24.2/§24.4）：
 * t_agent 记录 → HarnessAgent 的统一装配规则，Web 链路（AgentRegistry 每轮重建）
 * 与 channel 链路（ChannelHub 长驻实例）共享同一份 model/skill/sandbox/stateStore 装配。
 * extraMiddlewares 供调用方按链路追加（channel 链路追加会话索引中间件，Web 链路为空）。
 */
@Slf4j
@Component
public class AgentBuilder {

    private static final int DEFAULT_COMPACTION_TRIGGER = 30;
    private static final int DEFAULT_COMPACTION_KEEP = 10;

    private final AgentMapper agentMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final ChatSessionMapper chatSessionMapper;
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
    /** E2B 兼容链路全局凭证（SPEC §16 修订，配置后优先于 AgentRun MCP 链路） */
    private final E2bConnection e2bConnection;
    private final SandboxConfigService sandboxConfigService;
    /** MCP 系统配置服务（Agent 内联配置时不需，引用模式时需查 t_mcp_config） */
    private final MCPConfigService mcpConfigService;
    /** OSS 客户端管理与全局连接（工具提供型中间件：upload_file / download_file） */
    private final OssClientManager ossClientManager;
    private final OssConnection ossConnection;
    /** 按 Agent 生效的媒体存储载体（生成产物转存 OSS，SPEC-media-gen §4.9） */
    private final ImageStorageRouter imageStorageRouter;
    /** Agent 自带模型密钥的解密入口（feature.customModels，SPEC §29） */
    private final ConfigCryptoService crypto;
    /** feature 驱动中间件注册表（SPEC §29.23：feature 里的 middleware_* 命名空间 → 中间件装配） */
    private final FeatureMiddlewareRegistry featureMiddlewareRegistry;
    /** 生图/生视频工具密钥（SPEC-media-gen §4.1：复用现有环境变量，不落库） */
    @Value("${DASHSCOPE_API_KEY:}")
    private String dashscopeApiKey;
    /** JEV（TypeSafe System One）决策密钥（feature.guard 意图守卫；环境变量注入，不落库，与 DASHSCOPE 同款） */
    @Value("${TYPESAFE_API_KEY:}")
    private String jevApiKey;
    /** 共享 JevClient（懒建，缺密钥为 null；无状态 HTTP client，跨 Agent 复用） */
    private volatile JevClient jevClient;

    public AgentBuilder(AgentMapper agentMapper, AgentSkillMapper agentSkillMapper,
                          ChatSessionMapper chatSessionMapper,
                          ModelRegistry modelRegistry, AgentStateStore stateStore,
                          @Qualifier("skillRepositoryAgent") MysqlSkillRepository skillRepositoryAgent,
                          TeapotAiProperties properties,
                          ObjectProvider<GitSkillRepository> gitRepoProvider,
                          ObjectProvider<OssSkillRepository> ossRepoProvider,
                          ObjectProvider<RedisMemoryFilesystems> memoryRoutesProvider,
                          AgentRunConnection agentRunConnection,
                          E2bConnection e2bConnection,
                          SandboxConfigService sandboxConfigService,
                          MCPConfigService mcpConfigService,
                          OssClientManager ossClientManager,
                          OssConnection ossConnection,
                          ImageStorageRouter imageStorageRouter,
                          ConfigCryptoService crypto,
                          FeatureMiddlewareRegistry featureMiddlewareRegistry) {
        this.agentMapper = agentMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.chatSessionMapper = chatSessionMapper;
        this.modelRegistry = modelRegistry;
        this.stateStore = stateStore;
        this.skillRepositoryAgent = skillRepositoryAgent;
        this.properties = properties;
        this.gitRepoProvider = gitRepoProvider;
        this.ossRepoProvider = ossRepoProvider;
        this.memoryRoutesProvider = memoryRoutesProvider;
        this.agentRunConnection = agentRunConnection;
        this.e2bConnection = e2bConnection;
        this.sandboxConfigService = sandboxConfigService;
        this.mcpConfigService = mcpConfigService;
        this.ossClientManager = ossClientManager;
        this.ossConnection = ossConnection;
        this.imageStorageRouter = imageStorageRouter;
        this.crypto = crypto;
        this.featureMiddlewareRegistry = featureMiddlewareRegistry;
    }

    /** 按 t_agent 记录装配 HarnessAgent；extraMiddlewares 允许为空 */
    public HarnessAgent assemble(String agentKey, List<MiddlewareBase> extraMiddlewares) {
        AgentDO agentDO = agentMapper.selectByAgentKey(agentKey);
        if (agentDO == null || agentDO.getStatus() == null || agentDO.getStatus() != 1) {
            throw new BizException("Agent 不存在或已停用：" + agentKey);
        }
        Path workspace = Path.of(properties.getAgentscope().getWorkspaceRoot()).resolve(agentKey);
        // Agent ↔ Skill
        List<String> bound = agentSkillMapper.selectByAgentKey(agentKey)
                .stream()
                .map(AgentSkillBind::getSkillName)
                .toList();
        // 无「全局 skill」概念（SPEC §28.1）：未显式绑定即不装配任何 skill，每个 Agent 只用自己的
        SkillFilter skillFilter = bound.isEmpty() ?
                SkillFilter.none() :
                SkillFilter.only(bound.toArray(new String[0]));

        int trigger = agentDO.getCompactionTrigger() == null
                ? DEFAULT_COMPACTION_TRIGGER : agentDO.getCompactionTrigger();
        int keep = agentDO.getCompactionKeep() == null
                ? DEFAULT_COMPACTION_KEEP : agentDO.getCompactionKeep();

        AgentFeature feature = AgentFeature.parse(agentDO.getFeature());
        AgentFeature.Sandbox sb = feature.getSandbox();
        AgentFeature.Runtime rt = feature.getRuntime();

        // 思考模式三态原样透传（SPEC §29.15）：null = 不下发 enableThinking，跟随模型服务端默认
        Boolean thinking = rt == null ? null : rt.getThinkingMode();
        // Agent 自带模型凭证（SPEC §29）：主模型与各界点子模型解析时按 modelId 取用，
        // 取不到即回落系统入口与环境变量（存量 Agent 无该命名空间，行为不变）
        Map<String, ModelRegistry.Credential> customCreds = customCredentials(feature);

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
                .model(modelRegistry.resolve(agentDO.getModelId(), thinking,
                        credOf(customCreds, agentDO.getModelId())))
                .workspace(workspace)
                .stateStore(stateStore)
                // 框架 2.1.0 起默认 EVENT_LOG，会把消息落本地 journal 而非 MySQL stateStore，
                // 与 ChatSessionService 只读 stateStore 的链路错位 → 历史空白；强制 legacy 兼容模式回写 MySQL
                .legacySessionHistory(true)
                .skillRepositories(repos)
                .skillFilter(skillFilter);
        // compaction：trigger/keep 任一为 -1 表示关闭压缩，其余走既有配置
        AgentFeature.Compaction compactionCfg = feature.getCompaction();
        if (trigger < 0 || keep < 0) {
            builder.disableCompaction();
        } else {
            CompactionConfig.Builder ccBuilder = CompactionConfig.builder()
                    .triggerMessages(trigger)
                    .keepMessages(keep);
            applyTriggerMode(ccBuilder, compactionCfg);
            applyKeepMode(ccBuilder, compactionCfg);
            applyPrune(ccBuilder, compactionCfg);
            if (compactionCfg != null && compactionCfg.getModelId() != null && !compactionCfg.getModelId().isBlank()) {
                String compactionModelId = compactionCfg.getModelId();
                ccBuilder.model(modelRegistry.resolve(compactionModelId, false,
                        credOf(customCreds, compactionModelId)));
                log.info("压缩摘要使用独立模型 modelId={}", compactionModelId);
            }
            builder.compaction(ccBuilder.build());
        }
        // 超大工具输出落盘（SPEC §29.20）：与摘要压缩正交（管单条宽度，不管历史长度）
        applyEviction(builder, compactionCfg);
        applyRuntime(builder, rt);
        // 工具分组（SPEC §31）：有组就要 meta tool，否则入组工具被隐藏后再没人能装备回来；
        // 开关必须在 build 前给（框架在 build 里注册 meta tool），组本身在 build 后才建（见 applyToolGroups）
        List<AgentFeature.ToolGroup> toolGroups = feature.getToolGroups();
        if (!toolGroups.isEmpty()) {
            builder.enableMetaTool(true);
        }
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
        applyMemory(builder, feature.getMemory(), customCreds);
        // shell 门控：显式配置优先；未配置（存量）跟随沙箱启用，保证存量行为不回退
        boolean shellEnabled = rt != null && rt.getEnableShell() != null
                ? rt.getEnableShell() : (sb != null && sb.isEnabled());
        if (!shellEnabled) {
            builder.disableShellTool();
        }
        boolean sandboxApplied = applySandbox(builder, agentKey, sb);
        // 记忆路由（SPEC §27，本次修订）：MEMORY.md / memory/ 无论沙箱与否一律直达 Redis，两条链路同源，
        // 记忆管理接口因此能读到沙箱 Agent 的记忆。旧限制（2.0.1 沙箱文件系统无挂载点）已由
        // 2.0.3 的 filesystemRoute + RoutedSandboxFilesystem 解除：路由只接管记忆前缀，
        // 其余路径与 shell_execute 仍归沙箱（故沙箱内的存量副本不会被自动遮住，需一次性归档，见 SPEC §27）。
        RedisMemoryFilesystems memoryRoutes = memoryRoutesProvider.getIfAvailable();
        if (memoryRoutes != null) {
            if (sandboxApplied) {
                IsolationScope scope = isolationScope(sb);
                AbstractFilesystem memoryFs = memoryRoutes.memoryFilesystem(agentKey, scope);
                builder.filesystemRoute(RedisMemoryFilesystems.MEMORY_ROUTE_FILE, memoryFs);
                builder.filesystemRoute(RedisMemoryFilesystems.MEMORY_ROUTE_DIR, memoryFs);
                // 会话日志（agents/ 前缀）不能落沙箱：SandboxBackedFilesystem 有意不支持
                // durable sessionStorage，未注入时整轮调用直接抛 UnsupportedOperationException
                // （上游 a2cbea5c 起 WorkspaceSessionLogStore 不再隐式用工作区后端）。
                // 落本地磁盘 journal 目录，与沙箱生命周期解耦。
                builder.sessionLogStore(new WorkspaceSessionLogStore(
                        new LocalFilesystem(workspace.resolve("journal"))));
                log.info("沙箱 Agent 记忆已统一路由到 Redis agentKey={} isolationScope={}", agentKey, scope);
            } else {
                // 非沙箱（含降级）路径：本地叠加（含 shell）+ 记忆路径改指 Redis；降级路径已禁 shell，路由仍保留本地叠加能力
                builder.abstractFilesystem(memoryRoutes.localShellOverlay(agentKey, workspace));
                log.info("记忆文件系统已路由到 Redis agentKey={}", agentKey);
            }
        }
        // 工具提供型中间件（OSS 文件 / MCP 配置，按 runtime 开关）：
        // 中间件须在 build 前挂载（onSystemPrompt 注入），工具在 build 后注册到 Toolkit（MCP 同款链路）
        List<ToolProvidedMiddleware> toolMiddlewares = buildToolMiddlewares(agentKey, workspace, rt, feature.getMcp());
        // 自带工具的中间件（澄清档 / a2ui 档）：SDK 已取消 build 期回调中间件装工具的机制，
        // 改由装配方在 build 后按挂载顺序显式 installTools；同名工具后注册者覆盖，故顺序不可调换
        List<ToolInstallingMiddleware> toolInstalling = new ArrayList<>();
        if (extraMiddlewares != null) {
            for (MiddlewareBase middleware : extraMiddlewares) {
                builder.middleware(middleware);
                if (middleware instanceof ToolInstallingMiddleware installer) {
                    toolInstalling.add(installer);
                }
            }
        }
        for (ToolProvidedMiddleware middleware : toolMiddlewares) {
            builder.middleware(middleware);
        }
        // feature 驱动中间件（SPEC §29.23）：按 feature 里的 middleware_* 命名空间装配，
        // 配置在本次装配的快照上懒解析；新增中间件只加 Bean，本类与 AgentFeature 都不用改
        for (MiddlewareBase middleware : featureMiddlewareRegistry.assemble(agentKey, feature)) {
            builder.middleware(middleware);
        }
        // A2UI 界面生成（feature.a2ui）：启用即挂 A2uiMiddleware，注册 a2ui_render + ask_user_question
        // （表单档，覆盖 Web 链路默认挂的纯文本澄清档；组件 DSL 在 SDK 渲染子 Agent 内）
        A2uiMiddleware a2uiMiddleware =
                applyA2ui(builder, feature.getA2ui(), agentKey, agentDO.getModelId(), customCreds);
        if (a2uiMiddleware != null) {
            toolInstalling.add(a2uiMiddleware);
        }
        // 意图守卫（feature.guard）：启用且有规则 → 按 mode 挂 JEV 判定或大模型判定守卫
        applyGuard(builder, feature.getGuard(), agentKey, agentDO.getModelId(), customCreds);
        // 媒体模态守卫（SPEC-media-gen §4.4）：模型能力位未声明的媒体块（如 qwen3.8-max 的 audio）
        // 在请求视图里降级为文本引用，避免产物块一旦进入历史就被每轮重放、被平台 400 钉死整个会话；
        // 不依赖生成开关，任何来源的媒体块（工具产物/用户上传）都走同一守卫
        Set<String> modalities = modelRegistry.capabilities(agentDO.getModelId(),
                credOf(customCreds, agentDO.getModelId()));
        builder.middleware(new MediaModalGuardMiddleware(modalities));
        log.info("媒体模态守卫已挂载 agentKey={} 模型可输入模态={}", agentKey, modalities);
        // 用户附件地址注入（SPEC-media-gen §4.5）：透传给视觉模型的附件只有像素、没有地址文本，
        // 图生图/图生视频类工具拿不到 image_url，只能退化成文生图重绘；在守卫之后挂载，
        // 已被降级为文本引用的块不会被重复注入
        builder.middleware(new UserAttachmentRefMiddleware());
        // 会话标题异步生成（首条用户消息 → LLM 总结 → DB + CUSTOM 事件推前端）
        AgentFeature.SessionTitle stCfg = feature.getSessionTitle();
        boolean titleEnabled = stCfg == null || stCfg.getEnabled() == null || stCfg.getEnabled();
        if (titleEnabled) {
            boolean dedicatedTitleModel = stCfg != null && notBlank(stCfg.getModelId());
            String titleModelId = dedicatedTitleModel ? stCfg.getModelId() : agentDO.getModelId();
            ModelRegistry.Credential titleCred = credOf(customCreds, titleModelId);
            Model titleModel = dedicatedTitleModel
                    ? modelRegistry.resolve(titleModelId, false, titleCred)
                    : modelRegistry.resolve(titleModelId, thinking, titleCred);
            builder.middleware(new SessionTitleGenMiddleware(
                    agentKey, agentDO.getName(), chatSessionMapper, titleModel));
        } else {
            log.info("会话标题生成已关闭 agentKey={}", agentKey);
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
        for (ToolInstallingMiddleware installer : toolInstalling) {
            installer.installTools(agent.getToolkit());
        }
        // 生成产物转存（SPEC-media-gen §4.9）：必须在工具注册之后，按名取回已注册工具再同名覆盖为装饰器
        applyMediaArtifactPersist(agent, agentKey, rt);
        // MCP Server 注册（SPEC §MCP）：Agent 级配置，引用系统记录或内联完整配置
        applyMcp(agent, feature.getMcp());
        // 工具分组（SPEC §31）：排在所有工具注册之后，addToolToGroup 要求工具已在注册表里
        applyToolGroups(agent, toolGroups);
        return agent;
    }

    /**
     * 触发口径 → 框架 triggerTokens（SPEC §29.21/§29.22）。框架的 token 触发无法关闭：{@code 0}=按
     * 「窗口 − reserved」动态算，{@code >0}=固定阈值但仍被窗口取小。条数触发走 compaction_trigger 列
     * （0 = 不按条数），与 token 触发先到先算；{@code messages} 只是把条数当主输入，token 侧仍回落动态，
     * 并忽略 reserved（该口径下界面不摆这个框，避免存量值静默生效）。
     */
    private static void applyTriggerMode(CompactionConfig.Builder builder, AgentFeature.Compaction c) {
        if (c == null || c.getTriggerMode() == null || c.getTriggerMode().isBlank()) {
            return;
        }
        switch (c.getTriggerMode()) {
            case "tokens" -> builder.triggerTokens(c.getTriggerTokens());
            case "messages" -> builder.triggerTokens(0);
            default -> {
                builder.triggerTokens(0);
                if (c.getReserved() != null) {
                    builder.reserved(c.getReserved());
                }
            }
        }
    }

    /**
     * 尾部保留方式 → 框架 keepTokens 三态（SPEC §29.20）。框架里 {@code >0}=静态 token 预算、
     * {@code 0}=按条数、{@code -1}=按窗口动态；不配置时保持框架默认（动态），行为与接入本项之前一致。
     */
    private static void applyKeepMode(CompactionConfig.Builder builder, AgentFeature.Compaction c) {
        if (c == null || c.getKeepMode() == null || c.getKeepMode().isBlank()) {
            return;
        }
        int keepTokens = switch (c.getKeepMode()) {
            case "messages" -> 0;
            case "tokens" -> c.getKeepTokens();
            default -> -1;
        };
        builder.keepTokens(keepTokens);
    }

    /**
     * 工具结果聚合裁剪（SPEC §29.20）：框架默认开启（保护最近 40k tokens、可裁总量过 20k 才动手、
     * 每条留 2k 字符预览）。未配置 = 跟随默认；{@code enabled=false} = 传 null 关掉；
     * 开启态下只覆盖显式填了的阈值。
     */
    private static void applyPrune(CompactionConfig.Builder builder, AgentFeature.Compaction c) {
        AgentFeature.Compaction.Prune p = c == null ? null : c.getPrune();
        if (p == null) {
            return;
        }
        if (!p.isEnabled()) {
            builder.prune(null);
            log.info("工具结果聚合裁剪已关闭");
            return;
        }
        CompactionConfig.PruneConfig.PruneBuilder pb = CompactionConfig.PruneConfig.builder();
        if (p.getProtectTokens() != null) {
            pb.protectTokens(p.getProtectTokens());
        }
        if (p.getMinimumTokens() != null) {
            pb.minimumTokens(p.getMinimumTokens());
        }
        if (p.getMaxOutputChars() != null) {
            pb.maxOutputChars(p.getMaxOutputChars());
        }
        builder.prune(pb.build());
    }

    /** 单条超大工具输出落盘（SPEC §29.20）：{@code enabled=false} 走 {@code toolResultEviction(null)} 整体关闭 */
    private static void applyEviction(HarnessAgent.Builder builder, AgentFeature.Compaction c) {
        AgentFeature.Compaction.Eviction e = c == null ? null : c.getEviction();
        if (e == null) {
            return;
        }
        if (!e.isEnabled()) {
            builder.toolResultEviction(null);
            log.info("工具输出落盘已关闭");
            return;
        }
        ToolResultEvictionConfig.Builder eb = ToolResultEvictionConfig.builder();
        if (e.getMaxResultChars() != null) {
            eb.maxResultChars(e.getMaxResultChars());
        }
        if (e.getPreviewChars() != null) {
            eb.previewChars(e.getPreviewChars());
        }
        builder.toolResultEviction(eb.build());
    }

    /**
     * feature.customModels → modelId 凭证表（SPEC §29）：入库为 AES-GCM 密文，装配时解密。
     * 主密钥缺失或密文不可解时直接失败并点名模型——不能回落平台密钥冒用用户自带的模型，
     * 否则请求会静默计费到系统账号上。
     */
    private Map<String, ModelRegistry.Credential> customCredentials(AgentFeature feature) {
        List<AgentFeature.CustomModel> models = feature.getCustomModels();
        if (models.isEmpty()) {
            return Map.of();
        }
        Map<String, ModelRegistry.Credential> creds = new HashMap<>();
        for (AgentFeature.CustomModel model : models) {
            String apiKey = model.getApiKey();
            if (notBlank(apiKey)) {
                if (!crypto.available()) {
                    throw new BizException("服务器未配置 TEAPOT_SECRET_KEY，无法使用 Agent 自带模型 " + model.modelId());
                }
                try {
                    apiKey = crypto.decrypt(apiKey);
                } catch (BizException e) {
                    throw new BizException("Agent 自带模型 " + model.modelId() + " 的密钥无法解密，请在配置页重新填写："
                            + e.getMessage());
                }
            }
            creds.put(model.modelId(), new ModelRegistry.Credential(
                    apiKey, model.getBaseUrl(), model.capabilitySet(),
                    model.getContextWindowSize() == null ? 0 : model.getContextWindowSize()));
        }
        return creds;
    }

    /** 按 modelId 取 Agent 自带凭证（SPEC §29）；未自带时返回 null = 走系统入口与环境变量 */
    private static ModelRegistry.Credential credOf(Map<String, ModelRegistry.Credential> creds, String modelId) {
        return modelId == null ? null : creds.get(modelId);
    }

    /**
     * 给六个 DashScope 生成工具套上产物转存装饰器（SPEC-media-gen §4.9）：
     * 未套装饰器时产物只有百炼临时链接（约 24h 失效），会话隔天回看即裂图。
     * Toolkit.registerAgentTool 同名覆盖（ToolRegistry 内部 map.put），故装饰器可直接替下反射工具；
     * 生效载体非 oss 时装饰器内部自行放行，不改变存量行为。
     */
    private void applyMediaArtifactPersist(HarnessAgent agent, String agentKey, AgentFeature.Runtime rt) {
        if (rt == null || !Boolean.TRUE.equals(rt.getEnableMediaGen())) {
            return;
        }
        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) {
            return;
        }
        int wrapped = 0;
        for (MediaModelCatalog.Entry entry : MediaModelCatalog.entries()) {
            AgentTool tool = toolkit.getTool(entry.tool());
            if (tool == null || tool instanceof MediaArtifactPersistTool) {
                continue;
            }
            toolkit.registerAgentTool(new MediaArtifactPersistTool(tool, agentKey, imageStorageRouter));
            wrapped++;
        }
        log.info("生成产物转存装饰器已挂载 agentKey={} 覆盖工具数={}", agentKey, wrapped);
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
                Map<String, String> locked = MediaModelCatalog.lockedModels(rt.getMediaModels());
                middlewares.add(new MediaGenToolMiddleware(new DashScopeMultiModalTool(dashscopeApiKey), locked));
                log.info("生图/生视频工具已启用 agentKey={} 指定模型={}", agentKey, locked);
            }
        }
        return middlewares;
    }

    /**
     * a2ui 命名空间 → A2uiMiddleware 挂载（Agent 画 UI）：
     * enabled=true 时按配置构建 A2uiConfig（字段留空 = 回落 SDK 默认：内置 basic catalog、
     * 组件数 50、surface 跨轮持久化）；middleware 在 build 前挂载（onSystemPrompt
     * 注入需要），其 a2ui_render / ask_user_question（表单档）两工具由调用方在 build 后经
     * installTools 注册（SDK 已无 build 期自动装档回调；自然语言意图入参；组件 DSL 收进 SDK
     * 内部的渲染子 Agent——它只持有私有的 a2ui_present 提交工具，组件目录指南编进该子 Agent 的
     * system prompt，主 Agent 不感知；渲染成功后不再中断本轮 acting，由主模型依提示词自行收尾）；
     * 渲染子模型缺省回落本 Agent 模型；renderNoThinking=true 时显式注入本 Agent 模型的
     * 无思考实例（同守卫判定同款 resolveNoThinking 链路）。
     * catalog 在构造期加载，加载失败降级为不挂载并告警，不阻断对话。
     *
     * @return 已挂载的中间件（供 build 后装工具）；未启用或挂载失败时为 {@code null}
     */
    private A2uiMiddleware applyA2ui(HarnessAgent.Builder builder, AgentFeature.A2ui cfg,
                                     String agentKey, String defaultModelId,
                                     Map<String, ModelRegistry.Credential> customCreds) {
        if (cfg == null || !Boolean.TRUE.equals(cfg.getEnabled())) {
            return null;
        }
        A2uiConfig.Builder cb = A2uiConfig.builder();
        if (notBlank(cfg.getCatalogId())) {
            cb.catalogId(cfg.getCatalogId().trim());
        }
        if (notBlank(cfg.getCatalogResource())) {
            cb.catalogResource(cfg.getCatalogResource().trim());
        }
        if (cfg.getMaxComponents() != null) {
            cb.maxComponents(cfg.getMaxComponents());
        }
        if (cfg.getSurfacePersistEnabled() != null) {
            cb.surfacePersistEnabled(cfg.getSurfacePersistEnabled());
        }
        if (notBlank(cfg.getAppendPreference())) {
            cb.appendPreference(cfg.getAppendPreference().trim());
        }
        A2uiConfig config = cb.build();
        A2uiMiddleware a2ui = null;
        try {
            A2uiMiddleware middleware = Boolean.TRUE.equals(cfg.getRenderNoThinking())
                    // 子模型输出结构化组件树，思考纯属拖慢渲染：显式关闭（同守卫判定同款链路）
                    ? new A2uiMiddleware(config, modelRegistry.resolveNoThinking(
                            defaultModelId, credOf(customCreds, defaultModelId)))
                    : new A2uiMiddleware(config);
            builder.middleware(middleware);
            a2ui = middleware;
        } catch (Exception e) {
            log.error("A2UI 挂载失败（catalog/渲染子模型解析），界面能力降级为不挂载 agentKey={}：{}",
                    agentKey, e.getMessage());
            return null;
        }
        log.info("A2UI 界面生成已启用 agentKey={} catalog={} surfacePersist={} 渲染子模型={}",
                agentKey, config.catalogResource(), config.surfacePersistEnabled(),
                Boolean.TRUE.equals(cfg.getRenderNoThinking())
                        ? defaultModelId + "(关闭思考)" : "跟随主模型");
        return a2ui;
    }

    /**
     * 意图守卫（feature.guard）：接受/拒绝意图规则拼成判断文本，主模型看到请求前判定意图，
     * 被拒请求由内置拒答模型直接回绝（不进主模型）。拦截方式按 mode 分支：
     * jev=JEV 决策模型（需 TYPESAFE_API_KEY，缺密钥跳过挂载并告警，与生图工具同款先例）；
     * llm=大模型判定（判定模型按 modelId 解析，留空跟随 Agent 主模型；判定实例统一显式关闭思考）。
     * 门控：enabled + 至少一条规则。
     */
    private void applyGuard(HarnessAgent.Builder builder, AgentFeature.Guard cfg, String agentKey,
                            String defaultModelId, Map<String, ModelRegistry.Credential> customCreds) {
        if (cfg == null || !Boolean.TRUE.equals(cfg.getEnabled())) {
            return;
        }
        List<String> rules = new ArrayList<>();
        if (cfg.getAcceptIntents() != null) {
            cfg.getAcceptIntents().stream().filter(AgentBuilder::notBlank)
                    .forEach(intent -> rules.add("接受的意图：" + intent.trim()));
        }
        if (cfg.getRejectIntents() != null) {
            cfg.getRejectIntents().stream().filter(AgentBuilder::notBlank)
                    .forEach(intent -> rules.add("拒绝的意图：" + intent.trim()));
        }
        if (rules.isEmpty()) {
            log.info("意图守卫已启用但未配置意图规则，跳过挂载 agentKey={}", agentKey);
            return;
        }
        String mode = notBlank(cfg.getMode()) ? cfg.getMode().trim().toLowerCase() : "jev";
        if ("llm".equals(mode)) {
            // 判定输出只有 ACCEPT/REJECT，思考纯属拖慢守卫：统一显式关闭思考
            // （qwen3 混合思考模型不下发参数时服务端默认开启）
            String judgeModelId = notBlank(cfg.getModelId()) ? cfg.getModelId().trim() : defaultModelId;
            Model judgeModel = modelRegistry.resolveNoThinking(
                    judgeModelId, credOf(customCreds, judgeModelId));
            builder.middleware(new LlmIntentGuardMiddleware(judgeModel, rules,
                    notBlank(cfg.getDenyMessage()) ? cfg.getDenyMessage().trim() : null));
            log.info("意图守卫已挂载(LLM 判定) agentKey={} 规则数={} 判定模型={}",
                    agentKey, rules.size(), judgeModel.getModelName());
            return;
        }
        JevClient client = jevClientOrNull();
        if (client == null) {
            log.warn("意图守卫已启用但 TYPESAFE_API_KEY 未配置，跳过挂载 agentKey={}", agentKey);
            return;
        }
        JevIntentGuardMiddleware.Builder gb = JevIntentGuardMiddleware.builder(client)
                .guardRules(rules.toArray(new String[0]));
        if (cfg.getAllowThreshold() != null) {
            gb.allowThreshold(cfg.getAllowThreshold());
        }
        if (notBlank(cfg.getDenyMessage())) {
            gb.denyMessage(cfg.getDenyMessage().trim());
        }
        builder.middleware(gb.build());
        log.info("意图守卫已挂载(JEV) agentKey={} 规则数={} allowThreshold={}",
                agentKey, rules.size(), cfg.getAllowThreshold() == null ? 0.5 : cfg.getAllowThreshold());
    }

    /** 共享 JevClient（懒建双检锁）；密钥未配置返回 null */
    private JevClient jevClientOrNull() {
        if (!notBlank(jevApiKey)) {
            return null;
        }
        JevClient client = jevClient;
        if (client == null) {
            synchronized (this) {
                if (jevClient == null) {
                    jevClient = JevClient.builder().apiKey(jevApiKey.trim()).build();
                }
                client = jevClient;
            }
        }
        return client;
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
        if (Boolean.TRUE.equals(rt.getEnablePendingToolRecovery())) {
            builder.enablePendingToolRecovery(true);
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
    private void applyMemory(HarnessAgent.Builder builder, AgentFeature.Memory mem,
                             Map<String, ModelRegistry.Credential> customCreds) {
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
            MemoryConfig.Builder mcBuilder = MemoryConfig.builder().flushTrigger(trigger);
            if (mem.getModelId() != null && !mem.getModelId().isBlank()) {
                String memoryModelId = mem.getModelId();
                mcBuilder.model(modelRegistry.resolve(memoryModelId, false,
                        credOf(customCreds, memoryModelId)));
                log.info("记忆摘要使用独立模型 modelId={}", memoryModelId);
            }
            builder.memory(mcBuilder.build());
        } else if (mem != null && mem.getModelId() != null && !mem.getModelId().isBlank()) {
            // flushTrigger 未配置但 modelId 配置了，仍需构建 MemoryConfig 传入模型
            String memoryModelId = mem.getModelId();
            builder.memory(MemoryConfig.builder()
                    .model(modelRegistry.resolve(memoryModelId, false,
                            credOf(customCreds, memoryModelId)))
                    .build());
            log.info("记忆摘要使用独立模型 modelId={}", memoryModelId);
        }
    }

    /**
     * MCP Server 注册（Agent 级配置，不依赖系统配置）：
     * 每条 server 可引用系统记录（record）或内联完整配置（transport + command/url）；
     * 引用记录时从 t_mcp_config 查完整配置，内联时直接使用。
     * 通过 McpServerRegistrar.register() 将 MCP 工具注册到 HarnessAgent 的 Toolkit。
     */
    private void applyMcp(HarnessAgent agent, AgentFeature.MCP mcp) {
        if (mcp == null || !mcp.isEnabled() || mcp.getMcpServers() == null || mcp.getMcpServers().isEmpty()) {
            return;
        }
        Map<String, McpServerConfig> configs = new LinkedHashMap<>();
        for (AgentFeature.MCP.Server srv : mcp.getMcpServers()) {
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
     * 工具分组装配（SPEC §31）：每组建成一个 META 作用域、初始不激活的组，再把工具名逐个挂进去。
     * 入组的工具随即退出模型的默认工具表，改由模型自己调 meta tool（{@code reset_equipped_tools}）
     * 按名装备；没入组的工具不受影响 —— 框架里「无分组」等于始终可见。
     *
     * <p>必须排在所有工具注册之后：{@code addToolToGroup} 要求组与工具都已在册，否则抛
     * IllegalArgumentException。组名撞车或工具名对不上（拼错、被 runtime.allowedTools 白名单摘掉、
     * MCP 没连上）都只跳过并告警 —— 一条配置写错不该让整轮对话装配失败。
     */
    private void applyToolGroups(HarnessAgent agent, List<AgentFeature.ToolGroup> groups) {
        if (groups.isEmpty()) {
            return;
        }
        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) {
            log.warn("工具分组已配置但 HarnessAgent Toolkit 为空，跳过装配 agentKey={}", agent.getName());
            return;
        }
        int grouped = 0;
        int created = 0;
        for (AgentFeature.ToolGroup group : groups) {
            String name = group.getName().trim();
            String description = group.getDescription() == null ? "" : group.getDescription().trim();
            try {
                toolkit.createToolGroup(name, description, false, ToolGroupScope.META);
                created++;
            } catch (RuntimeException e) {
                log.warn("工具分组创建失败，跳过该组 agentKey={} group={}：{}", agent.getName(), name, e.getMessage());
                continue;
            }
            for (String tool : group.getTools()) {
                String toolName = tool.trim();
                try {
                    toolkit.addToolToGroup(name, toolName);
                    grouped++;
                } catch (RuntimeException e) {
                    log.warn("工具入组失败，跳过该工具 agentKey={} group={} tool={}：{}",
                            agent.getName(), name, toolName, e.getMessage());
                }
            }
        }
        log.info("工具分组已装配 agentKey={} 组={}/{} 入组工具={} meta tool=reset_equipped_tools",
                agent.getName(), created, groups.size(), grouped);
    }

    /**
     * feature.sandbox.isolationScope → {@link IsolationScope}（缺省 SESSION）。
     * 沙箱与记忆路由必须用同一个作用域，否则记忆命名空间与沙箱复用粒度会错位。
     */
    private static IsolationScope isolationScope(AgentFeature.Sandbox sb) {
        String raw = sb == null ? null : sb.getIsolationScope();
        return IsolationScope.valueOf(raw == null || raw.isBlank() ? "SESSION" : raw.trim());
    }

    /**
     * {@code feature.sandbox} 的自带 E2B 凭证 → 一条虚拟承载记录（SPEC §29.17），
     * 让下游的链路判定与取值路径跟系统记录完全同构。未自带时返回 null（走系统记录 / 全局接入）。
     * 主密钥缺失或密文不可解直接失败：静默改用平台 E2B 密钥会把用户自带沙箱的开销计费到系统账号。
     */
    private SandboxConfigDO customE2bRecord(AgentFeature.Sandbox sb, String agentKey) {
        if (!sb.isCustom()) {
            return null;
        }
        String apiKey = sb.getE2bApiKey();
        if (notBlank(apiKey)) {
            if (!crypto.available()) {
                throw new BizException("服务器未配置 TEAPOT_SECRET_KEY，无法使用 Agent " + agentKey + " 的自带沙箱密钥");
            }
            try {
                apiKey = crypto.decrypt(apiKey);
            } catch (BizException e) {
                throw new BizException("Agent " + agentKey + " 的自带沙箱密钥无法解密，请在沙箱分区重新填写：" + e.getMessage());
            }
        }
        SandboxConfigDO record = new SandboxConfigDO();
        record.setName("(agent:" + agentKey + ")");
        record.setLinkType("e2b");
        record.setE2bApiKey(apiKey);
        record.setE2bApiBaseUrl(sb.getE2bApiBaseUrl().trim());
        record.setE2bDomain(sb.getE2bDomain().trim());
        record.setE2bDefaultTemplate(sb.getTemplateName().trim());
        return record;
    }

    /**
     * 按 feature.sandbox 装配沙箱（SPEC §16.7 / §22.2 修订）：
     * shell 工具门控由 assemble() 统一负责（runtime.enableShell 优先，存量跟随沙箱）；
     * 自带 E2B 凭证（§29.17）优先于系统记录；启用且指定 sandboxRecord → 链路与凭证由记录决定（§22.2）；
     * 启用无记录（存量）→ 按 sandbox.link 全局路由（e2b / agentrun），首选不可用时自动回落。
     * 返回是否实际装配了沙箱文件系统（false = 非沙箱或降级，供记忆路由决策，SPEC §27）。
     */
    private boolean applySandbox(HarnessAgent.Builder builder, String agentKey, AgentFeature.Sandbox sb) {
        if (sb == null || !sb.isEnabled()) {
            // 无沙箱：不装配文件系统，shell 门控见 assemble()
            return false;
        }
        // §29.17 自带 E2B 承载优先：整套凭证来自 feature，不引用系统记录、也不逐字段混用
        SandboxConfigDO record = customE2bRecord(sb, agentKey);
        if (record == null && sb.getSandboxRecord() != null && !sb.getSandboxRecord().isBlank()) {
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
        spec.isolationScope(isolationScope(sb));
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
        boolean e2bUsable = cfg.getE2b().isEnabled() && e2bConnection.configured();
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
     * record 非空时凭证取记录（§22.2），否则取全局 {@link E2bConnection}（存量兼容）。
     */
    private void applyE2b(HarnessAgent.Builder builder, String agentKey, AgentFeature.Sandbox sb,
                          SandboxConfigDO record) {
        TeapotAiProperties.Sandbox.E2b defaults = properties.getSandbox().getE2b();
        String persistence = sb.getPersistence() == null ? "LOCAL_SNAPSHOT" : sb.getPersistence();
        String workspaceRoot = sb.getWorkspaceRoot() != null
                ? sb.getWorkspaceRoot() : defaults.getDefaultWorkspaceRoot();
        E2bFilesystemSpec spec = new E2bFilesystemSpec()
                .apiKey(record != null ? record.getE2bApiKey() : e2bConnection.getApiKey())
                .apiBaseUrl(record != null ? record.getE2bApiBaseUrl() : e2bConnection.getApiBaseUrl())
                .domain(record != null ? record.getE2bDomain() : e2bConnection.getDomain())
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
        spec.isolationScope(isolationScope(sb));
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
        String t = e2bConnection.getDefaultTemplate();
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
