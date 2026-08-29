package com.teamer.teapot.ai.core.storage;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;

import java.util.List;
import java.util.Map;

/**
 * 带记忆路由的本地叠加文件系统（SPEC §27）：替代 SDK 默认的 ShellAwareOverlay。
 *
 * <p>继承 {@link OverlayFilesystem} 仅为保住三处类型判定（上层为 {@code LocalFilesystemWithShell} 时：
 * 路径归一化、skill shell 策略；实现 {@link AbstractSandboxFilesystem}：shell 工具注册），
 * 文件操作实际全部委托内部 {@link CompositeFilesystem}：记忆路径（MEMORY.md / memory/）→
 * Redis 叠加层，其余路径 → 与官方默认装配等价的本地叠加层。
 *
 * <p>Composite 的默认后端必须是一个平行的等价叠加实例：本类覆写了全部文件方法，
 * 若以 {@code this} 作默认后端会经覆写方法递归回路由层。
 */
public class RedisMemoryLocalFilesystem extends OverlayFilesystem implements AbstractSandboxFilesystem {

    /** shell 能力载体（上层 LocalFilesystemWithShell） */
    private final AbstractSandboxFilesystem shell;
    /** 实际路由：记忆路径 → Redis 叠加，其余 → 平行等价叠加 */
    private final CompositeFilesystem routed;

    public RedisMemoryLocalFilesystem(AbstractSandboxFilesystem upper, AbstractFilesystem lower,
                                      Map<String, AbstractFilesystem> routes) {
        super(upper, lower);
        this.shell = upper;
        this.routed = new CompositeFilesystem(OverlayFilesystem.of(upper, lower), routes);
    }

    @Override
    public String id() {
        return shell.id();
    }

    @Override
    public ExecuteResponse execute(RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        return shell.execute(runtimeContext, command, timeoutSeconds);
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        return routed.ls(runtimeContext, path);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        return routed.read(runtimeContext, filePath, offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        return routed.write(runtimeContext, filePath, content);
    }

    @Override
    public EditResult edit(RuntimeContext runtimeContext, String filePath,
                           String oldString, String newString, boolean replaceAll) {
        return routed.edit(runtimeContext, filePath, oldString, newString, replaceAll);
    }

    @Override
    public GrepResult grep(RuntimeContext runtimeContext, String pattern, String path, String glob) {
        return routed.grep(runtimeContext, pattern, path, glob);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        return routed.glob(runtimeContext, pattern, path);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(RuntimeContext runtimeContext,
                                                List<Map.Entry<String, byte[]>> files) {
        return routed.uploadFiles(runtimeContext, files);
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext runtimeContext, List<String> paths) {
        return routed.downloadFiles(runtimeContext, paths);
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        return routed.delete(runtimeContext, path);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        return routed.move(runtimeContext, fromPath, toPath);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        return routed.exists(runtimeContext, path);
    }
}
