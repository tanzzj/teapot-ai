package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.model.vo.MemoryStoreVO;
import com.teamer.teapot.ai.core.storage.RedisMemoryFilesystems;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 记忆内容管理（SPEC §27 记忆管理）：查询与逐条删除落在 Redis 的 Agent 长期记忆
 * （{@code MEMORY.md} 与 {@code memory/} 每日台账）。读写一律复用运行时同款
 * {@link RemoteFilesystem} 链路，命名空间 {@code agents/<agentKey>/users/<uid>}。
 */
@Slf4j
@Service
public class MemoryStoreService {

    private final ObjectProvider<RedisMemoryFilesystems> filesystemsProvider;

    public MemoryStoreService(ObjectProvider<RedisMemoryFilesystems> filesystemsProvider) {
        this.filesystemsProvider = filesystemsProvider;
    }

    private RedisMemoryFilesystems requireFilesystems() {
        RedisMemoryFilesystems filesystems = filesystemsProvider.getIfAvailable();
        if (filesystems == null) {
            throw new BizException("Redis 记忆存储未启用（teapot.ai.agentscope.redis.memory-store=true 时可用）");
        }
        return filesystems;
    }

    /**
     * 列出该 Agent 全部命名空间的记忆文件（含正文）。先经索引键扫描出持有记忆的
     * uid 列表，再逐个命名空间 {@code ls("/")} 枚举文件并整读内容。
     */
    public List<MemoryStoreVO.UserGroup> listItems(String agentKey) {
        RedisMemoryFilesystems filesystems = requireFilesystems();
        RuntimeContext rc = RuntimeContext.empty();
        List<MemoryStoreVO.UserGroup> groups = new ArrayList<>();
        for (String uid : filesystems.listMemoryUids(agentKey)) {
            RemoteFilesystem fs = filesystems.filesystemFor(agentKey, uid);
            LsResult ls = fs.ls(rc, "/");
            if (!ls.isSuccess() || ls.entries() == null) {
                continue;
            }
            List<MemoryStoreVO.FileItem> files = new ArrayList<>();
            for (FileInfo info : ls.entries()) {
                if (info.isDirectory()) {
                    continue;
                }
                String content = null;
                ReadResult read = fs.read(rc, info.path(), 0, -1);
                if (read.isSuccess() && read.fileData() != null) {
                    content = read.fileData().content();
                }
                files.add(new MemoryStoreVO.FileItem(info.path(), info.size(), info.modifiedAt(), content));
            }
            files.sort((a, b) -> a.path().compareTo(b.path()));
            if (!files.isEmpty()) {
                groups.add(new MemoryStoreVO.UserGroup(uid, files));
            }
        }
        return groups;
    }

    /** 逐条删除：删除指定命名空间（uid）下的单个记忆文件 */
    public void deleteItem(String agentKey, String uid, String path) {
        RedisMemoryFilesystems filesystems = requireFilesystems();
        if (uid == null || uid.isBlank()) {
            throw new BizException("uid 必填");
        }
        if (path == null || path.isBlank()) {
            throw new BizException("path 必填");
        }
        RemoteFilesystem fs = filesystems.filesystemFor(agentKey, uid);
        WriteResult result = fs.delete(RuntimeContext.empty(), path);
        if (!result.isSuccess()) {
            throw new BizException("记忆删除失败：" + result.error());
        }
        log.info("记忆已删除 agentKey={} uid={} path={}", agentKey, uid, path);
    }
}
