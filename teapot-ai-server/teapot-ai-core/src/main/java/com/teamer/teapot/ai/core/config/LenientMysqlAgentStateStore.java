package com.teamer.teapot.ai.core.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 放宽 sessionId 校验的 MysqlAgentStateStore（补丁，SPEC §22.5）：
 * 官方实现把 sessionId 当文件系统路径校验，拒绝包含 "/" "\" 的 ID；
 * 但 harness 的 SessionSandboxStateStore 以 "sandbox/user/<id>" 等带斜杠的
 * slot 作为 sessionId 存沙箱状态，导致沙箱状态读写抛
 * "AgentStateStore ID cannot contain path separators"，进而打断 AG-UI run。
 * MySQL 的 session_id 只是不透明字符串主键，无路径语义，故仅保留空值与长度校验。
 *
 * <p>另外覆盖 saveIfVersion：官方实现在 UNVERSIONED 分支调用
 * getVersioned(..., State.class) 获取版本号，但 State 是接口，Jackson 无法反序列化，
 * 导致 "Cannot construct instance of State" 异常。改为直接查询 version 列。
 */
public class LenientMysqlAgentStateStore extends MysqlAgentStateStore {

    private static final int SINGLE_STATE_INDEX = 0;

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

    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        if (expectedVersion == AgentStateStore.UNVERSIONED) {
            save(userId, sessionId, key, value);
            return queryVersion(userId, sessionId, key);
        }
        return super.saveIfVersion(userId, sessionId, key, value, expectedVersion);
    }

    /**
     * 直接查询 version 列，避免反序列化 state_data（State 是接口，Jackson 无法实例化）。
     */
    private long queryVersion(String userId, String sessionId, String key) {
        // slotId 格式与父类一致：normalizeUser(userId) + ":" + sessionId
        String userSegment = (userId == null || userId.isBlank()) ? "__anon__" : userId;
        String slotId = userSegment + ":" + sessionId;
        String sql = "SELECT version FROM agentscope_sessions"
                + " WHERE session_id = ? AND state_key = ? AND item_index = ?";
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, slotId);
            stmt.setString(2, key);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            return AgentStateStore.UNVERSIONED;
        }
    }
}
