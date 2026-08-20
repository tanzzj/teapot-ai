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
 * t_agent.feature 扩展功能配置模型（SPEC §16.6/§22.2）：
 * 通用命名空间容器，消费 sandbox 与 storage；未知顶层命名空间原样保留（不拒不改）。
 */
@Data
public class AgentFeature {

    public static final String NS_SANDBOX = "sandbox";
    /** 图片存储载体（SPEC §22.1）：base64 默认 / oss 记录引用 */
    public static final String NS_STORAGE = "storage";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ISOLATION_SCOPES = Set.of("SESSION", "USER", "AGENT", "GLOBAL");
    private static final Set<String> PERSISTENCE_MODES = Set.of("NONE", "LOCAL_SNAPSHOT", "NAS");
    /** 沙箱链路可选值（SPEC §21.4）：auto=跟随全局配置 */
    private static final Set<String> SANDBOX_LINKS = Set.of("auto", "e2b", "agentrun");
    private static final List<String> NAS_MOUNT_PREFIXES = List.of("/home/", "/mnt/", "/data/");
    /** 闲置超时合法区间（SPEC §16.6） */
    private static final int IDLE_MIN_SECONDS = 300;
    private static final int IDLE_MAX_SECONDS = 21600;

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

    /**
     * 保存时强校验（SPEC §16.6/§22 校验表）：不合法直接抛 BizException 拒绝。
     * 记录存在性由 AgentService 补充校验（模型层不依赖 Service）。
     *
     * @param agentRunConfigured 全局 AgentRun 是否已接入（存量无记录 Agent 的兼容门控）
     */
    public void validate(boolean agentRunConfigured) {
        validateStorage();
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

    /** storage 命名空间结构（SPEC §22.1：图片存储载体按 Agent 选择，非全局） */
    @Data
    public static class Storage {
        /** base64（默认，图片随消息体传输）/ oss（上传为对象，消息体仅存 URL） */
        private String mode;
        /** mode=oss 时引用的 t_storage_config 记录名 */
        private String storageRecord;
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
