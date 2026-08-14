package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.dao.AgentMapper;
import com.teamer.teapot.ai.core.dao.ChatSessionMapper;
import com.teamer.teapot.ai.core.model.AgentDO;
import com.teamer.teapot.ai.core.model.ChatSessionDO;
import com.teamer.teapot.ai.core.model.dto.SessionCreateRequest;
import com.teamer.teapot.ai.core.model.dto.SessionMessageItem;
import com.teamer.teapot.ai.core.model.dto.SessionRenameRequest;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话索引管理（SPEC §9）：
 * t_chat_session 仅存索引（userId/agentKey/sessionId/title），
 * 消息体以 agentscope_sessions 为唯一事实源，不双写。
 */
@Slf4j
@Service
public class ChatSessionService {

    /** agentscope_sessions 中 Runtime 写入的状态键（整个 AgentState 单槽存储） */
    private static final String AGENT_STATE_KEY = "agent_state";

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

    /** 会话改名（懒创建会话后以首条消息回填标题），仅允许改本人会话 */
    @Transactional(rollbackFor = Exception.class)
    public void rename(SessionRenameRequest request) {
        String userId = requireUserId();
        ChatSessionDO session = chatSessionMapper.selectByUserSession(userId, request.getSessionId());
        if (session == null) {
            throw new BizException("会话不存在：" + request.getSessionId());
        }
        String title = request.getTitle().trim();
        if (title.isEmpty()) {
            return;
        }
        if (title.length() > 50) {
            title = title.substring(0, 50);
        }
        chatSessionMapper.touch(userId, request.getSessionId(), title);
    }

    /**
     * 读取会话消息历史：消息体由 Runtime 经 StateStore 持久化在 agentscope_sessions
     * （AgentState.context）。仅本人会话可读。
     * 注意：AG-UI 异步链路 ContextUtil ThreadLocal 丢失，写入侧 userId 兜底为
     * "anonymous"（见 TeapotRuntimeContextResolver），故先查 anonymous 槽位，
     * 再兜底当前用户槽位。
     */
    public List<SessionMessageItem> messages(String sessionId) {
        String userId = requireUserId();
        ChatSessionDO session = chatSessionMapper.selectByUserSession(userId, sessionId);
        if (session == null) {
            throw new BizException("会话不存在：" + sessionId);
        }
        Optional<AgentState> state = stateStore.get("anonymous", sessionId, AGENT_STATE_KEY, AgentState.class);
        if (state.isEmpty()) {
            state = stateStore.get(userId, sessionId, AGENT_STATE_KEY, AgentState.class);
        }
        List<Msg> context = state.map(AgentState::getContext).orElse(null);
        if (context == null || context.isEmpty()) {
            return List.of();
        }
        List<SessionMessageItem> items = new ArrayList<>();
        for (Msg msg : context) {
            MsgRole role = msg.getRole();
            // 历史画面仅回放用户/助手文本，跳过 system/tool 消息
            if (role != MsgRole.USER && role != MsgRole.ASSISTANT) {
                continue;
            }
            String text = msg.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            items.add(new SessionMessageItem(role == MsgRole.USER ? "user" : "assistant", text));
        }
        return items;
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
