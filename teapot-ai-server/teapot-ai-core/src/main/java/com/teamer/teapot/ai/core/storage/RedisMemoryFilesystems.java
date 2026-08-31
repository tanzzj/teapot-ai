package com.teamer.teapot.ai.core.storage;

import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.redis.store.RedisStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 记忆文件系统路由工厂（SPEC §27）：把 Agent 长期记忆（{@code MEMORY.md} 与 {@code memory/}
 * 每日台账，含 {@code memory/.consolidation_state} watermark）路由到 Redis ——
 * 记忆路径的读写一律直达 {@link RemoteFilesystem}（Redis 为唯一来源，<b>不再回落本地磁盘</b>）；
 * 其余工作区路径保持 SDK 默认本地叠加（含 shell）。
 *
 * <p>命名空间 {@code agents/<agentKey>/users/<uid>}：对齐官方 USER 隔离，
 * uid 缺失时回落 sessionId（与 {@link IsolationScope#USER} 语义一致）。
 *
 * <p>存量本地记忆（路由上线前落在 {@code workspace/<agentKey>/<uid>/} 的文件）经
 * {@link #migrateLegacyMemory(Path)} 一次性导入 Redis（create-if-absent，幂等）。
 *
 * <p>沙箱 Agent 不适用：2.0.1 沙箱模式下文件系统固定为沙箱实现，无路由挂载点，记忆留在沙箱。
 */
public class RedisMemoryFilesystems {

    private static final Logger log = LoggerFactory.getLogger(RedisMemoryFilesystems.class);

    private final RedisStore store;
    private final JedisPooled jedis;
    private final String keyPrefix;

    public RedisMemoryFilesystems(TeapotAiProperties properties, JedisPooled jedis) {
        this.jedis = jedis;
        this.keyPrefix = properties.getAgentscope().getRedis().getMemoryKeyPrefix();
        this.store = new RedisStore(jedis, keyPrefix);
    }

    /**
     * 管理视图（SPEC §27 记忆管理）：扫描 {@code <prefix>idx:*} 得到该 Agent 下所有持有记忆的
     * 命名空间 uid（含 userId / sessionId 回落值 / _default）。
     *
     * <p>SDK {@link RedisStore} 用 NUL（{@code \0}）连接命名空间段，实际 key 形如
     * {@code <prefix>idx:agents\0<agentKey>\0users\0<uid>}，因此不能用冒号 glob 匹配，
     * 需先 scan 全部 idx 键再在 Java 侧按 NUL 拆分过滤。
     */
    public List<String> listMemoryUids(String agentKey) {
        String scanPattern = keyPrefix + "idx:*";
        String nsPrefix = "agents\0" + agentKey + "\0users\0";
        String idxMarker = "idx:";
        List<String> uids = new java.util.ArrayList<>();
        String cursor = "0";
        redis.clients.jedis.params.ScanParams params = new redis.clients.jedis.params.ScanParams().match(scanPattern).count(200);
        do {
            var result = jedis.scan(cursor, params);
            for (String key : result.getResult()) {
                int idxPos = key.indexOf(idxMarker);
                if (idxPos < 0) continue;
                String ns = key.substring(idxPos + idxMarker.length());
                if (!ns.startsWith(nsPrefix)) continue;
                String uid = ns.substring(nsPrefix.length());
                if (!uid.isEmpty()) {
                    uids.add(uid);
                }
            }
            cursor = result.getCursor();
        } while (!"0".equals(cursor));
        java.util.Collections.sort(uids);
        return uids;
    }

    /** 管理视图：构造指定命名空间（agentKey + uid）的记忆文件系统，供查询/删除复用运行时同款链路 */
    public RemoteFilesystem filesystemFor(String agentKey, String uid) {
        return new RemoteFilesystem(store, ns -> List.of("agents", agentKey, "users", uid));
    }

    /**
     * 非沙箱 Agent 的本地 + 记忆路由文件系统：完整复刻 SDK 默认本地装配
     * （{@link LocalFilesystemSpec}：LocalFilesystemWithShell 上层 + project 下层、
     * USER 命名空间隔离），再经 {@code CompositeFilesystem} 把记忆路径路由到 Redis。
     * shell 执行、路径归一化、skill shell 策略均保持不变。
     *
     * <p>{@code "MEMORY.md"}（精确文件路由）与 {@code "memory/"}（目录路由）经 Composite
     * 归一后都落到同一个 {@link RemoteFilesystem}（同一命名空间，路径分别为 {@code /MEMORY.md}
     * 与 {@code /<name>}），因此两条路由共用一个实例。
     */
    public AbstractFilesystem localShellOverlay(String agentKey, Path workspace) {
        OverlayFilesystem defaultOverlay = (OverlayFilesystem) new LocalFilesystemSpec()
                .toFilesystem(workspace, IsolationScope.USER.toNamespaceFactory());
        RemoteFilesystem redisFs = new RemoteFilesystem(store, namespace(agentKey));
        Map<String, AbstractFilesystem> routes = Map.of(
                "memory/", redisFs,
                "MEMORY.md", redisFs);
        return new RedisMemoryLocalFilesystem(
                (AbstractSandboxFilesystem) defaultOverlay.getUpper(), defaultOverlay.getLower(), routes);
    }

    /**
     * 一次性存量迁移（SPEC §27）：扫描 {@code workspaceRoot/<agentKey>/<uid>/} 布局，把
     * {@code MEMORY.md} 与 {@code memory/*} 用运行时同款 {@link RemoteFilesystem#write}
     * （create-if-absent）导入 Redis 命名空间 {@code agents/<agentKey>/users/<uid>}。
     * 已存在的键跳过，因此幂等可重跑；磁盘文件保留不动（转为只读归档）。
     *
     * @return {@code [migrated, skipped]} 计数
     */
    public int[] migrateLegacyMemory(Path workspaceRoot) {
        int[] counter = new int[2]; // [migrated, skipped]
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            log.warn("存量记忆迁移跳过：workspace 根不存在 {}", workspaceRoot);
            return counter;
        }
        try (var agentDirs = Files.list(workspaceRoot)) {
            for (Path agentDir : agentDirs.filter(Files::isDirectory).toList()) {
                String agentKey = agentDir.getFileName().toString();
                migrateAgent(agentKey, agentDir, counter);
            }
        } catch (IOException e) {
            log.error("存量记忆迁移失败", e);
        }
        log.info("存量记忆迁移完成 workspaceRoot={} migrated={} skipped={}",
                workspaceRoot, counter[0], counter[1]);
        return counter;
    }

    private void migrateAgent(String agentKey, Path agentDir, int[] counter) throws IOException {
        try (var uidDirs = Files.list(agentDir)) {
            for (Path uidDir : uidDirs.filter(Files::isDirectory).toList()) {
                String uid = uidDir.getFileName().toString();
                migrateUid(agentKey, uid, uidDir, counter);
            }
        }
    }

    private void migrateUid(String agentKey, String uid, Path uidDir, int[] counter) throws IOException {
        RemoteFilesystem fs = new RemoteFilesystem(store, ns -> List.of("agents", agentKey, "users", uid));
        RuntimeContext rc = RuntimeContext.empty();
        // 1) MEMORY.md → /MEMORY.md
        Path memoryMd = uidDir.resolve("MEMORY.md");
        if (Files.isRegularFile(memoryMd)) {
            migrateOne(fs, rc, "/MEMORY.md", memoryMd, counter);
        }
        // 2) memory/* → /<name>（含 .consolidation_state watermark；Composite 会剥离 memory/ 前缀）
        Path memoryDir = uidDir.resolve("memory");
        if (Files.isDirectory(memoryDir)) {
            try (var files = Files.list(memoryDir)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    migrateOne(fs, rc, "/" + f.getFileName().toString(), f, counter);
                }
            }
        }
    }

    private void migrateOne(RemoteFilesystem fs, RuntimeContext rc, String key, Path file, int[] counter)
            throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        WriteResult result = fs.write(rc, key, content);
        if (result.isSuccess()) {
            counter[0]++;
            log.debug("存量记忆已迁移 key={} file={}", key, file);
        } else {
            counter[1]++; // 已存在（create-if-absent）→ 跳过
        }
    }

    private NamespaceFactory namespace(String agentKey) {
        return rc -> List.of("agents", agentKey, "users", resolveUid(rc));
    }

    /** userId 优先；缺失回落 sessionId；都无则 _default（与 IsolationScope.USER 降级语义一致） */
    private static String resolveUid(RuntimeContext rc) {
        if (rc != null) {
            String uid = rc.getUserId();
            if (uid != null && !uid.isBlank()) {
                return uid;
            }
            String sid = rc.getSessionId();
            if (sid != null && !sid.isBlank()) {
                return sid;
            }
        }
        return "_default";
    }
}
