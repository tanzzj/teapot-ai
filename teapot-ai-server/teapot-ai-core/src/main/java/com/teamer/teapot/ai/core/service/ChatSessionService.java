package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.dao.AgentMapper;
import com.teamer.teapot.ai.core.dao.ChatSessionMapper;
import com.teamer.teapot.ai.core.model.AgentDO;
import com.teamer.teapot.ai.core.model.ChatSessionDO;
import com.teamer.teapot.ai.core.model.dto.SessionCreateRequest;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 会话索引管理（SPEC §9）：
 * t_chat_session 仅存索引（userId/agentKey/sessionId/title），
 * 消息体以 agentscope_sessions 为唯一事实源，不双写。
 */
@Slf4j
@Service
public class ChatSessionService {

    private final ChatSessionMapper chatSessionMapper;
    private final AgentMapper agentMapper;
    private final MysqlAgentStateStore stateStore;

    public ChatSessionService(ChatSessionMapper chatSessionMapper,
                              AgentMapper agentMapper,
                              MysqlAgentStateStore stateStore) {
        this.chatSessionMapper = chatSessionMapper;
        this.agentMapper = agentMapper;
        this.stateStore = stateStore;
    }

    /** 当前用户在某 Agent 下的会话列表（agentKey 可空 = 全部） */
    public List<ChatSessionDO> list(String agentKey) {
        return chatSessionMapper.selectByUser(requireUserId(), agentKey);
    }

    @Transactional(rollbackFor = Exception.class)
    public ChatSessionDO create(SessionCreateRequest request) {
        AgentDO agent = agentMapper.selectByAgentKey(request.getAgentKey());
        if (agent == null || !Integer.valueOf(1).equals(agent.getStatus())) {
            throw new BizException("Agent 不存在或已停用：" + request.getAgentKey());
        }
        ChatSessionDO session = new ChatSessionDO();
        session.setUserId(requireUserId());
        session.setAgentKey(request.getAgentKey());
        session.setSessionId(UUID.randomUUID().toString());
        session.setTitle(request.getTitle() == null || request.getTitle().isBlank()
                ? agent.getName() : request.getTitle());
        chatSessionMapper.insert(session);
        return session;
    }

    /** 清空会话（SPEC §9）：删 stateStore 中的消息状态 + 删索引记录 */
    @Transactional(rollbackFor = Exception.class)
    public void clear(String sessionId) {
        String userId = requireUserId();
        try {
            stateStore.delete(userId, sessionId);
        } catch (Exception e) {
            // 状态删除失败不阻塞索引清理
            log.warn("stateStore 删除失败 userId={} sessionId={}", userId, sessionId, e);
        }
        chatSessionMapper.delete(userId, sessionId);
    }

    private String requireUserId() {
        String userId = ContextUtil.currentUserId();
        if (userId == null) {
            throw new BizException(Result.CODE_UNAUTHORIZED, "用户未登录");
        }
        return userId;
    }
}
