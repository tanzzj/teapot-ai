package com.teamer.teapot.ai.core.model.vo;

import java.util.List;

/**
 * Redis 记忆内容管理视图（SPEC §27 记忆管理）：按命名空间 uid 分组的记忆文件清单。
 */
public final class MemoryStoreVO {

    private MemoryStoreVO() {
    }

    /** 单个命名空间（agents/&lt;agentKey&gt;/users/&lt;uid&gt;）下的记忆文件分组 */
    public record UserGroup(String uid, List<FileItem> files) {
    }

    /** 单条记忆文件（MEMORY.md / memory 每日台账等） */
    public record FileItem(String path, long size, String modifiedAt, String content) {
    }
}
