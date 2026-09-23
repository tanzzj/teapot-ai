package com.teamer.teapot.ai.core.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamer.teapot.ai.common.exception.BizException;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * t_agent.feature 扩展功能配置模型（SPEC §16.6/§22.2/§24.3）：
 * 通用命名空间容器，消费 sandbox、storage 与 channel；未知顶层命名空间原样保留（不拒不改）。
 */
@Data
public class AgentFeature {

    public static final String NS_SANDBOX = "sandbox";
    /** 图片存储载体（SPEC §22.1）：base64 默认 / oss 记录引用 */
    public static final String NS_STORAGE = "storage";
    /** Channel 连接器（SPEC §24.3）：Agent 对外消息通道 */
    public static final String NS_CHANNEL = "channel";
    /** 运行期高级配置：thinking/采样参数/工具与迭代开关等（Agent 配置页 Basic Info + Tool & Advanced） */
    public static final String NS_RUNTIME = "runtime";
    /** MultiAgent（SPEC §25）：subagent 能力开关（缺省开启，对齐 SDK 默认） */
    public static final String NS_MULTIAGENT = "multiagent";
    /** 长期记忆（SPEC §25）：两层记忆管线开关与 flush 触发策略（缺省开启，对齐 SDK 默认） */
    public static final String NS_MEMORY = "memory";
    /** MCP Server 配置：Agent 可引用系统记录（record）或内联完整配置（transport + ...），不依赖系统配置 */
    public static final String NS_MCP = "mcp";
    /** 异步会话标题生成：开关 + 专用模型（缺省启用，跟随 Agent 主模型） */
    public static final String NS_SESSION_TITLE = "sessionTitle";
    /** 压缩摘要模型覆盖（trigger/keep 仍在 AgentDO 列，本命名空间仅 modelId） */
    public static final String NS_COMPACTION = "compaction";
    /** A2UI 界面生成（AgentScope a2ui 扩展）：Agent 画 UI 能力开关 + catalog 与停止语义配置 */
    public static final String NS_A2UI = "a2ui";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ISOLATION_SCOPES = Set.of("SESSION", "USER", "AGENT", "GLOBAL");
    private static final Set<String> PERSISTENCE_MODES = Set.of("NONE", "LOCAL_SNAPSHOT", "NAS");
    /** 沙箱链路可选值（SPEC §21.4）：auto=跟随全局配置 */
    private static final Set<String> SANDBOX_LINKS = Set.of("auto", "e2b", "agentrun");
    /** channel 会话粒度可选值（SPEC §24.3） */
    private static final Set<String> DM_SCOPES = Set.of("MAIN", "PER_PEER", "PER_CHANNEL_PEER");
    /** 记忆 flush 触发策略可选值（SPEC §25，对齐 MemoryConfig.FlushTrigger） */
    private static final Set<String> FLUSH_TRIGGERS = Set.of("always", "never", "throttled");
    /** 权限模式可选值（permission-system 落地：只读探索 / 阻止危险命令 / 全部放行） */
    private static final Set<String> PERMISSION_MODES = Set.of("EXPLORE", "BLOCK_DANGEROUS", "BYPASS");
    private static final List<String> NAS_MOUNT_PREFIXES = List.of("/home/", "/mnt/", "/data/");
    /** 闲置超时合法区间（SPEC §16.6） */
    private static final int IDLE_MIN_SECONDS = 300;
    private static final int IDLE_MAX_SECONDS = 21600;
    /** a2ui 单信封组件数上限（配置校验区间） */
    private static final int A2UI_MAX_COMPONENTS_LIMIT = 500;

    /** 原始命名空间（保留未知项，序列化时原样写回） */
    private Map<String, Object> namespaces = new LinkedHashMap<>();

    /** 解析 feature JSON；null/空/非法 JSON 返回空 feature（非法 JSON 记警告不拒读） */
    public static AgentFeature parse(String json) {
        AgentFeature feature = new AgentFeature();
        if (json == null || json.isBlank()) {
            return feature;
        }
        try {
            Map<String, Object> map = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            if (map != null) {
                feature.namespaces.putAll(map);
            }
        } catch (Exception e) {
            throw new BizException("Agent feature JSON 非法：" + e.getMessage());
        }
        return feature;
    }

    /** 序列化为入库 JSON（空命名空间返回 null，保持列为 NULL 语义） */
    public String toJson() {
        if (namespaces.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(namespaces);
        } catch (Exception e) {
            throw new BizException("Agent feature 序列化失败：" + e.getMessage());
        }
    }

    /** sandbox 命名空间；未配置返回 null */
    @SuppressWarnings("unchecked")
    public Sandbox getSandbox() {
        Object raw = namespaces.get(NS_SANDBOX);
        if (!(raw instanceof Map)) {
            return null;
        }
        return MAPPER.convertValue(raw, Sandbox.class);
    }

    /** 写入/替换 sandbox 命名空间 */
    public void setSandbox(Sandbox sandbox) {
        if (sandbox == null) {
            namespaces.remove(NS_SANDBOX);
        } else {
            namespaces.put(NS_SANDBOX, MAPPER.convertValue(sandbox, new TypeReference<Map<String, Object>>() {
            }));
        }
    }

    /** storage 命名空间（SPEC §22.1）；未配置返回 null（按 base64 处理） */
    @SuppressWarnings("unchecked")
    public Storage getStorage() {
        Object raw = namespaces.get(NS_STORAGE);
        if (!(raw instanceof Map)) {
            return null;
        }
        return MAPPER.convertValue(raw, Storage.class);
    }

    /** 写入/替换 storage 命名空间 */
    public void setStorage(Storage storage) {
        if (storage == null) {
            namespaces.remove(NS_STORAGE);
        } else {
            namespaces.put(NS_STORAGE, MAPPER.convertValue(storage, new TypeReference<Map<String, Object>>() {
            }));
        }
    }

    /** channel 命名空间（SPEC §24.3）；未配置返回 null */
    @SuppressWarnings("unchecked")
    public Channel getChannel() {
        Object raw = namespaces.get(NS_CHANNEL);
        if (!(raw instanceof Map)) {
            return null;
        }
        return MAPPER.convertValue(raw, Channel.class);
    }

    /** 写入/替换 channel 命名空间 */
    public void setChannel(Channel channel) {
        if (channel == null) {
            namespaces.remove(NS_CHANNEL);
        } else {
            namespaces.put(NS_CHANNEL, MAPPER.convertValue(channel, new TypeReference<Map<String, Object>>() {
            }));
        }
    }

    /** runtime 命名空间；未配置返回 null */
    @SuppressWarnings("unchecked")
    public Runtime getRuntime() {
        Object raw = namespaces.get(NS_RUNTIME);
        if (!(raw instanceof Map)) {
            return null;
        }
        return MAPPER.convertValue(raw, Runtime.class);
    }

    /** 写入/替换 runtime 命名空间 */
    public void setRuntime(Runtime runtime) {
        if (runtime == null) {
            namespaces.remove(NS_RUNTIME);
        } else {
            namespaces.put(NS_RUNTIME, MAPPER.convertValue(runtime, new TypeReference<Map<String, Object>>() {
            }));
        }
    }

    /** multiagent 命名空间；未配置返回 null（按启用处理，对齐 SDK 默认） */
    @SuppressWarnings("unchecked")
    public MultiAgent getMultiAgent() {
        Object raw = namespaces.get(NS_MULTIAGENT);
        if (!(raw instanceof Map)) {
            return null;
        }
        return MAPPER.convertValue(raw, MultiAgent.class);
    }

    /** 写入/替换 multiagent 命名空间 */
    public void setMultiAgent(MultiAgent multiAgent) {
        if (multiAgent == null) {
            namespaces.remove(NS_MULTIAGENT);
        } else {
            namespaces.put(NS_MULTIAGENT, MAPPER.convertValue(multiAgent, new TypeReference<Map<String, Object>>() {
            }));
        }
    }

    /** memory 命名空间；未配置返回 null（按启用处理，对齐 SDK 默认） */
    @SuppressWarnings("unchecked")
    public Memory getMemory() {
        Object raw = namespaces.get(NS_MEMORY);
        if (!(raw instanceof Map)) {
            return null;
        }
        return MAPPER.convertValue(raw, Memory.class);
    }

    /** 写入/替换 memory 命名空间 */
    public void setMemory(Memory memory) {
        if (memory == null) {
            namespaces.remove(NS_MEMORY);
        } else {
            namespaces.put(NS_MEMORY, MAPPER.convertValue(memory, new TypeReference<Map<String, Object>>() {
            }));
        }
    }

    /** sessionTitle 命名空间；未配置返回 null（按启用处理） */
    @SuppressWarnings("unchecked")
    public SessionTitle getSessionTitle() {
        Object raw = namespaces.get(NS_SESSION_TITLE);
        if (!(raw instanceof Map)) {
            return null;
        }
        return MAPPER.convertValue(raw, SessionTitle.class);
    }

    /** 写入/替换 sessionTitle 命名空间 */
    public void setSessionTitle(SessionTitle sessionTitle) {
        if (sessionTitle == null) {
            namespaces.remove(NS_SESSION_TITLE);
        } else {
            namespaces.put(NS_SESSION_TITLE, MAPPER.convertValue(sessionTitle, new TypeReference<Map<String, Object>>() {
            }));
        }
    }

    /** compaction 命名空间；未配置返回 null */
    @SuppressWarnings("unchecked")
    public Compaction getCompaction() {
        Object raw = namespaces.get(NS_COMPACTION);
        if (!(raw instanceof Map)) {
            return null;
        }
        return MAPPER.convertValue(raw, Compaction.class);
    }

    /** 写入/替换 compaction 命名空间 */
    public void setCompaction(Compaction compaction) {
        if (compaction == null) {
            namespaces.remove(NS_COMPACTION);
        } else {
            namespaces.put(NS_COMPACTION, MAPPER.convertValue(compaction, new TypeReference<Map<String, Object>>() {
            }));
        }
    }

    /** mcp 命名空间；未配置返回 null */
    @SuppressWarnings("unchecked")
    public MCP getMcp() {
        Object raw = namespaces.get(NS_MCP);
        if (!(raw instanceof Map)) {
            return null;
        }
        return MAPPER.convertValue(raw, MCP.class);
    }

    /** 写入/替换 mcp 命名空间 */
    public void setMcp(MCP mcp) {
        if (mcp == null) {
            namespaces.remove(NS_MCP);
        } else {
            namespaces.put(NS_MCP, MAPPER.convertValue(mcp, new TypeReference<Map<String, Object>>() {
            }));
        }
    }

    /** a2ui 命名空间；未配置返回 null（按不启用处理，存量 Agent 零影响） */
    @SuppressWarnings("unchecked")
    public A2ui getA2ui() {
        Object raw = namespaces.get(NS_A2UI);
        if (!(raw instanceof Map)) {
            return null;
        }
        return MAPPER.convertValue(raw, A2ui.class);
    }

    /** 写入/替换 a2ui 命名空间 */
    public void setA2ui(A2ui a2ui) {
        if (a2ui == null) {
            namespaces.remove(NS_A2UI);
        } else {
            namespaces.put(NS_A2UI, MAPPER.convertValue(a2ui, new TypeReference<Map<String, Object>>() {
            }));
        }
    }

    /**
     * 保存时强校验（SPEC §16.6/§22 校验表）：不合法直接抛 BizException 拒绝。
     * 记录存在性由 AgentService 补充校验（模型层不依赖 Service）。
     *
     * @param agentRunConfigured 全局 AgentRun 是否已接入（存量无记录 Agent 的兼容门控）
     */
    public void validate(boolean agentRunConfigured) {
        validateStorage();
        validateChannel();
        validateMcp();
        validateRuntime();
        validateMemory();
        validateA2ui();
        Sandbox sb = getSandbox();
        if (sb == null) {
            return;
        }
        if (sb.isEnabled()) {
            // §22.2：启用沙箱必须选择沙箱记录作为承载；存量无记录 Agent 回落全局接入门控
            if (isBlank(sb.getSandboxRecord())) {
                if (!agentRunConfigured) {
                    throw new BizException("启用沙箱必须选择沙箱记录（系统配置 - 沙箱中维护）");
                }
            }
        }
        if (sb.getIsolationScope() != null && !ISOLATION_SCOPES.contains(sb.getIsolationScope())) {
            throw new BizException("isolationScope 非法，可选值：" + ISOLATION_SCOPES);
        }
        if (sb.getLink() != null && !SANDBOX_LINKS.contains(sb.getLink())) {
            throw new BizException("link 非法，可选值：" + SANDBOX_LINKS);
        }
        if (sb.getPersistence() != null && !PERSISTENCE_MODES.contains(sb.getPersistence())) {
            throw new BizException("persistence 非法，可选值：" + PERSISTENCE_MODES);
        }
        if (sb.getIdleTimeoutSeconds() != null
                && (sb.getIdleTimeoutSeconds() < IDLE_MIN_SECONDS || sb.getIdleTimeoutSeconds() > IDLE_MAX_SECONDS)) {
            throw new BizException("idleTimeoutSeconds 超出范围（" + IDLE_MIN_SECONDS + "–" + IDLE_MAX_SECONDS + "）");
        }
        if (sb.getWorkspaceRoot() != null && !sb.getWorkspaceRoot().startsWith("/")) {
            throw new BizException("workspaceRoot 必须为绝对路径");
        }
        if ("NAS".equals(sb.getPersistence())) {
            Sandbox.Nas nas = sb.getNas();
            if (nas == null || isBlank(nas.getServerAddr()) || isBlank(nas.getMountDir())) {
                throw new BizException("persistence=NAS 时必须配置 nas.serverAddr 与 nas.mountDir");
            }
            if (NAS_MOUNT_PREFIXES.stream().noneMatch(p -> nas.getMountDir().startsWith(p))) {
                throw new BizException("nas.mountDir 必须以 /home/、/mnt/ 或 /data/ 开头（官方约束）");
            }
            if (sb.getWorkspaceRoot() != null && !sb.getWorkspaceRoot().startsWith(nas.getMountDir())) {
                throw new BizException("persistence=NAS 时 workspaceRoot 必须以 nas.mountDir 为前缀");
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** storage 命名空间校验（SPEC §22.1）：mode 枚举 + oss 模式必选记录 */
    private void validateStorage() {
        Storage st = getStorage();
        if (st == null) {
            return;
        }
        String mode = st.getMode();
        if (!isBlank(mode) && !"base64".equals(mode) && !"oss".equals(mode)) {
            throw new BizException("storage.mode 非法，可选值：base64 / oss");
        }
        if ("oss".equals(mode) && isBlank(st.getStorageRecord())) {
            throw new BizException("图片存储选择 OSS 时必须选择一条 OSS 连接记录");
        }
    }

    /** runtime 命名空间校验：采样参数与迭代数区间，越界拒绝保存 */
    private void validateRuntime() {
        Runtime rt = getRuntime();
        if (rt == null) {
            return;
        }
        if (rt.getTemperature() != null && (rt.getTemperature() < 0 || rt.getTemperature() > 2)) {
            throw new BizException("temperature 超出范围（0–2）");
        }
        if (rt.getTopP() != null && (rt.getTopP() <= 0 || rt.getTopP() > 1)) {
            throw new BizException("topP 超出范围（0–1]）");
        }
        if (rt.getMaxTokens() != null && (rt.getMaxTokens() < 1 || rt.getMaxTokens() > 65536)) {
            throw new BizException("maxTokens 超出范围（1–65536）");
        }
        if (rt.getMaxIterations() != null && (rt.getMaxIterations() < 1 || rt.getMaxIterations() > 100)) {
            throw new BizException("maxIterations 超出范围（1–100）");
        }
        if (rt.getPermissionMode() != null && !PERMISSION_MODES.contains(rt.getPermissionMode())) {
            throw new BizException("permissionMode 非法，可选值：" + PERMISSION_MODES);
        }
    }

    /** channel 命名空间校验（SPEC §24.3）：启用必选记录、dmScope 枚举；与沙箱可共存（§24.2 修订） */
    private void validateChannel() {
        Channel ch = getChannel();
        if (ch == null || !ch.isEnabled()) {
            return;
        }
        if (isBlank(ch.getChannelRecord())) {
            throw new BizException("启用连接器必须选择一条连接器记录（系统配置 - 连接器中维护）");
        }
        if (ch.getDmScope() != null && !DM_SCOPES.contains(ch.getDmScope())) {
            throw new BizException("dmScope 非法，可选值：" + DM_SCOPES);
        }
    }

    /** memory 命名空间校验（SPEC §25）：flushTrigger 枚举；throttled 时节流分钟数区间 */
    private void validateMemory() {
        Memory mem = getMemory();
        if (mem == null) {
            return;
        }
        if (mem.getFlushTrigger() != null && !FLUSH_TRIGGERS.contains(mem.getFlushTrigger())) {
            throw new BizException("flushTrigger 非法，可选值：" + FLUSH_TRIGGERS);
        }
        if ("throttled".equals(mem.getFlushTrigger())) {
            Integer minutes = mem.getFlushThrottleMinutes();
            if (minutes == null || minutes < 1 || minutes > 1440) {
                throw new BizException("flushThrottleMinutes 超出范围（1–1440）");
            }
        }
    }

    /**
     * a2ui 命名空间校验：启用时自定义 catalogResource 必须真实存在于服务端 classpath
     * （catalog 在 A2uiMiddleware 构造期加载，缺失会导致该 Agent 每轮构建失败，故保存即拦截）；
     * maxComponents 区间 [1,500]。
     */
    private void validateA2ui() {
        A2ui cfg = getA2ui();
        if (cfg == null || !Boolean.TRUE.equals(cfg.getEnabled())) {
            return;
        }
        if (cfg.getMaxComponents() != null
                && (cfg.getMaxComponents() < 1 || cfg.getMaxComponents() > A2UI_MAX_COMPONENTS_LIMIT)) {
            throw new BizException("a2ui maxComponents 超出范围（1–" + A2UI_MAX_COMPONENTS_LIMIT + "）");
        }
        String resource = cfg.getCatalogResource();
        if (!isBlank(resource)
                && AgentFeature.class.getClassLoader().getResource(resource.trim()) == null) {
            throw new BizException("a2ui catalogResource 不存在（服务端 classpath）：" + resource.trim());
        }
    }

    /** mcp 命名空间校验：启用时 servers 不能为空；每条 server 必须 record 或 transport 二选一 */
    private void validateMcp() {
        MCP mcp = getMcp();
        if (mcp == null || !mcp.isEnabled()) {
            return;
        }
        if (mcp.getServers() == null || mcp.getServers().isEmpty()) {
            throw new BizException("启用 MCP 必须配置至少一条 MCP Server（引用系统记录或内联配置）");
        }
        for (MCP.Server srv : mcp.getServers()) {
            boolean hasRecord = !isBlank(srv.getRecord());
            boolean hasTransport = !isBlank(srv.getTransport());
            if (!hasRecord && !hasTransport) {
                throw new BizException("MCP Server 必须指定 record（引用系统记录）或 transport（内联配置）");
            }
            if (hasTransport) {
                if ("stdio".equals(srv.getTransport()) && isBlank(srv.getCommand())) {
                    throw new BizException("MCP Server transport=stdio 时必须指定 command");
                }
                if (("streamable_http".equals(srv.getTransport()) || "sse".equals(srv.getTransport()))
                        && isBlank(srv.getUrl())) {
                    throw new BizException("MCP Server transport=" + srv.getTransport() + " 时必须指定 url");
                }
            }
        }
    }

    /** storage 命名空间结构（SPEC §22.1：图片存储载体按 Agent 选择，非全局） */
    @Data
    public static class Storage {
        /** base64（默认，图片随消息体传输）/ oss（上传为对象，消息体仅存 URL） */
        private String mode;
        /** mode=oss 时引用的 t_storage_config 记录名 */
        private String storageRecord;
    }

    /** channel 命名空间结构（SPEC §24.3：Agent 对外消息通道） */
    @Data
    public static class Channel {
        private boolean enabled;
        /** 引用的 t_channel_config 记录名（启用必填） */
        private String channelRecord;
        /** 会话粒度：MAIN / PER_PEER / PER_CHANNEL_PEER，缺省 PER_CHANNEL_PEER */
        private String dmScope;
    }

    /** multiagent 命名空间结构（SPEC §25：subagent 委派能力） */
    @Data
    public static class MultiAgent {
        /** false 时装配 disableSubagents + disableDynamicSubagents；缺省（无命名空间）启用 */
        private boolean enabled;
    }

    /** memory 命名空间结构（SPEC §25：两层长期记忆管线） */
    @Data
    public static class Memory {
        /** false 时装配 disableMemoryHooks + disableMemoryTools；缺省（无命名空间）启用 */
        private Boolean enabled;
        /** flush 触发策略：always（每次对话后）/ never（仅随压缩）/ throttled（节流）；缺省 always */
        private String flushTrigger;
        /** flushTrigger=throttled 时的最小间隔分钟数（1–1440） */
        private Integer flushThrottleMinutes;
        /** 记忆操作（flush + consolidation）专用模型，留空跟随 Agent 主模型 */
        private String modelId;
    }

    /** sessionTitle 命名空间结构：异步会话标题生成（开关 + 专用模型） */
    @Data
    public static class SessionTitle {
        /** false 时不挂载 SessionTitleGenMiddleware；缺省（无命名空间）启用 */
        private Boolean enabled;
        /** 标题生成专用模型，留空跟随 Agent 主模型 */
        private String modelId;
    }

    /** compaction 命名空间结构：压缩摘要模型覆盖（trigger/keep 仍在 AgentDO 列） */
    @Data
    public static class Compaction {
        /** 压缩摘要专用模型，留空跟随 Agent 主模型 */
        private String modelId;
    }

    /**
     * a2ui 命名空间结构（AgentScope a2ui 扩展）：Agent 画 UI 能力。
     * 字段全部可空（null = 回落 A2uiConfig SDK 默认）；enabled 缺省不启用，存量 Agent 零影响。
     */
    @Data
    public static class A2ui {
        /** 是否启用（挂载 A2uiMiddleware，获得 a2ui_render / a2ui_present / a2ui_catalog / a2ui_ask_user_question） */
        private Boolean enabled;
        /** 组件目录标识（信封 catalogId 字段），留空 = agentscope.io:a2ui/basic */
        private String catalogId;
        /** 组件目录 classpath 资源路径，留空 = a2ui/basic-catalog.json（扩展 jar 内置基础目录） */
        private String catalogResource;
        /** 渲染后中断：a2ui_present 成功后是否停止本轮 acting（等用户操作），留空 = true */
        private Boolean stopAfterPresent;
        /** 单信封组件数上限 [1,500]，留空 = 50 */
        private Integer maxComponents;
        /** surface 状态跨轮持久化（AgentStateStore），留空 = true；关闭则 surface 仅存活于单轮 */
        private Boolean surfacePersistEnabled;
    }

    /** mcp 命名空间结构：Agent 级 MCP Server 配置（引用系统记录 或 内联完整配置） */
    @Data
    public static class MCP {
        /** 是否启用 MCP 工具 */
        private boolean enabled;
        /** MCP Server 列表：每条可引用系统记录（record）或内联完整配置（transport + ...） */
        private List<Server> servers;

        /** 单条 MCP Server 配置（record 与 inline 二选一） */
        @Data
        public static class Server {
            /** 引用 t_mcp_config 记录名（与 inline 配置二选一） */
            private String record;
            /** 传输协议：stdio / streamable_http / sse（inline 必填） */
            private String transport;
            /** stdio 启动命令（transport=stdio 时必填） */
            private String command;
            /** stdio 命令参数 */
            private List<String> args;
            /** 环境变量 */
            private Map<String, String> env;
            /** HTTP/SSE 远程 URL（transport=streamable_http/sse 时必填） */
            private String url;
            /** HTTP 请求头 */
            private Map<String, String> headers;
            /** 描述（仅展示用） */
            private String description;
        }
    }

    /** runtime 命名空间结构：字段全部可空（null = 未配置，回落 SDK 默认 / 存量行为） */
    @Data
    public static class Runtime {
        /** 思考模式（DashScope enableThinking；openai 供应商忽略） */
        private Boolean thinkingMode;
        /** 采样温度 [0,2] */
        private Double temperature;
        /** 核采样 (0,1] */
        private Double topP;
        /** 最大生成 tokens [1,65536] */
        private Integer maxTokens;
        /** 计划模式 */
        private Boolean enablePlanMode;
        /** shell 工具开关；null = 跟随沙箱启用（存量兼容） */
        private Boolean enableShell;
        /** OSS 文件上传/下载工具开关（upload_file / download_file，ToolProvidedMiddleware 挂载） */
        private Boolean enableOssFile;
        /** MCP 配置查询工具开关（list_mcp_servers / get_mcp_server，ToolProvidedMiddleware 挂载） */
        private Boolean enableMcpConfig;
        /** 生图/生视频工具开关（DashScopeMultiModalTool，ToolProvidedMiddleware 挂载，SPEC-media-gen §4.1） */
        private Boolean enableMediaGen;
        /** 各生成能力指定模型（SPEC-media-gen §4.8）：字段留空 = 跟随工具默认模型 */
        private MediaModels mediaModels;
        /** 权限模式（Agent 级默认，chat 面板可按请求覆盖）：EXPLORE / BLOCK_DANGEROUS / BYPASS；null = 不设权限上下文（存量行为） */
        private String permissionMode;
        /** 工具白名单（空 = 不限制） */
        private List<String> allowedTools;
        /** ReAct 最大迭代轮数 [1,100]；null = SDK 默认 */
        private Integer maxIterations;
    }

    /**
     * 生成模型指定（SPEC-media-gen §4.8）：按能力位给 DashScopeMultiModalTool 的六个生成工具锁定 model 入参。
     * 字段名与 runtime.mediaModels 的 JSON key 一一对应，同时也是 /api/model/media-models 目录的 field 标识；
     * 全部可空，空 = 不覆写、跟随工具默认模型（存量行为）。
     */
    @Data
    public static class MediaModels {
        /** 文生图（dashscope_text_to_image） */
        private String textToImage;
        /** 图生图/改图（dashscope_image_to_image） */
        private String imageToImage;
        /** 文生视频（dashscope_text_to_video） */
        private String textToVideo;
        /** 图生视频（dashscope_image_to_video） */
        private String imageToVideo;
        /** 首尾帧生视频（dashscope_first_and_last_frame_image_to_video） */
        private String frameToVideo;
        /** 文本转语音（dashscope_text_to_audio） */
        private String textToAudio;
    }

    /** sandbox 命名空间结构（字段缺省由 AgentRegistry 构建时回填，SPEC §16.6/§22.2） */
    @Data
    public static class Sandbox {
        private boolean enabled;
        /** 沙箱承载记录（§22.2）：引用 t_sandbox_config.name，链路由记录 linkType 决定 */
        private String sandboxRecord;
        /** 沙箱链路（存量兼容，§21.4）：auto（跟随全局）/ e2b / agentrun，仅无记录时生效 */
        private String link;
        /** SESSION/USER/AGENT/GLOBAL，缺省 SESSION */
        private String isolationScope;
        /** NONE/LOCAL_SNAPSHOT/NAS，缺省 LOCAL_SNAPSHOT */
        private String persistence;
        /** 覆盖全局默认模板 */
        private String templateName;
        /** 沙箱工作区根（绝对路径） */
        private String workspaceRoot;
        /** 闲置回收阈值（秒）300–21600 */
        private Integer idleTimeoutSeconds;
        private Nas nas;

        @Data
        public static class Nas {
            private String serverAddr;
            private String mountDir;
            private String remotePath;
            private boolean enableTLS;
        }
    }
}
