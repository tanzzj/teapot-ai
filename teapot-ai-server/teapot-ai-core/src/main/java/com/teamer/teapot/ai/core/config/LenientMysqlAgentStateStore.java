package com.teamer.teapot.ai.core.config;

import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;

import javax.sql.DataSource;

/**
 * 放宽 sessionId 校验的 MysqlAgentStateStore（补丁，SPEC §22.5）：
 * 官方实现把 sessionId 当文件系统路径校验，拒绝包含 "/" "\" 的 ID；
 * 但 harness 的 SessionSandboxStateStore 以 "sandbox/user/<id>" 等带斜杠的
 * slot 作为 sessionId 存沙箱状态，导致沙箱状态读写抛
 * "AgentStateStore ID cannot contain path separators"，进而打断 AG-UI run。
 * MySQL 的 session_id 只是不透明字符串主键，无路径语义，故仅保留空值与长度校验。
 */
public class LenientMysqlAgentStateStore extends MysqlAgentStateStore {

    public LenientMysqlAgentStateStore(DataSource dataSource, boolean createIfNotExist) {
        super(dataSource, createIfNotExist);
    }

    @Override
    protected void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("AgentStateStore ID cannot be null or empty");
        }
        if (sessionId.length() > 255) {
            throw new IllegalArgumentException("AgentStateStore ID cannot exceed 255 characters");
        }
        // 有意不校验路径分隔符：MySQL 键无文件系统语义（官方限制仅适用于文件型 StateStore）
    }
}
