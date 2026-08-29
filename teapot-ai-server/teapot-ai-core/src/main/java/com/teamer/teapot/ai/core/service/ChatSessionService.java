package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.dao.AgentMapper;
import com.teamer.teapot.ai.core.dao.ChatSessionMapper;
import com.teamer.teapot.ai.core.model.AgentDO;
import com.teamer.teapot.ai.core.model.ChatSessionDO;
import com.teamer.teapot.ai.core.model.dto.SessionCreateRequest;
import com.teamer.teapot.ai.core.model.dto.SessionDateCount;
import com.teamer.teapot.ai.core.model.dto.SessionMessageItem;
import com.teamer.teapot.ai.core.model.dto.SessionRenameRequest;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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

    /** AgentStateStore 中 Runtime 写入的状态键（整个 AgentState 单槽存储，Redis/MySQL 后端一致） */
    private static final String AGENT_STATE_KEY = "agent_state";

    private final ChatSessionMapper chatSessionMapper;
    private final AgentMapper agentMapper;
    private final AgentStateStore stateStore;

    public ChatSessionService(ChatSessionMapper chatSessionMapper,
                              AgentMapper agentMapper,
                              AgentStateStore stateStore) {
        this.chatSessionMapper = chatSessionMapper;
        this.agentMapper = agentMapper;
        this.stateStore = stateStore;
    }

    /** 当前用户在某 Agent 下的会话列表（agentKey 可空 = 全部） */
    public List<ChatSessionDO> list(String agentKey) {
        return chatSessionMapper.selectByUser(requireUserId(), agentKey);
    }

    /** 某 Agent 近 280 天的会话按日统计（Profile 热力图，跨用户聚合） */
    public List<SessionDateCount> stats(String agentKey) {
        String since = java.time.LocalDate.now().minusDays(279)
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        List<SessionDateCount> result = new ArrayList<>();
        for (Map<String, Object> row : chatSessionMapper.countByAgentAndDate(agentKey, since)) {
            Object d = row.get("d");
            Object c = row.get("c");
            if (d != null && c instanceof Number n) {
                result.add(new SessionDateCount(String.valueOf(d), n.longValue()));
            }
        }
        return result;
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
     * 双槽合并：AG-UI 异步链路早期写入兜底到 anonymous 槽，后续修复（Authorization 头解析
     * JWT）写入真实用户槽位；同一会话可能两槽各存一段（用户槽从空上下文重启，不含旧消息），
     * 只读单槽必然丢段——两槽都存在时按时间序合并（anonymous 旧段在前，按 id 去重）。
     * 图片/视频不内联 base64（单个可达数十 MB，内联会让响应体膨胀导致弱网下传输被掐断），
     * 而是返回独立取媒体端点引用，前端带鉴权单独拉取。
     */
    public List<SessionMessageItem> messages(String sessionId) {
        List<Msg> context = loadContext(sessionId);
        return SessionMessageConverter.toItems(context,
                (source, seq) -> mediaRef("/api/chat/session/image/", sessionId, source, seq),
                (source, seq) -> mediaRef("/api/chat/session/video/", sessionId, source, seq));
    }

    /** 会话内历史图片二进制：按 base64 图片出现顺序取第 imageIndex 张（仅本人会话） */
    public ImageData image(String sessionId, int imageIndex) {
        List<Msg> context = loadContext(sessionId);
        int seq = 0;
        for (Msg msg : context) {
            if (msg.getRole() != MsgRole.USER) {
                continue;
            }
            String name = msg.getName();
            if (name != null && name.startsWith("__compaction_summary__")) {
                continue;
            }
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ImageBlock imageBlock
                        && imageBlock.getSource() instanceof Base64Source base64Source) {
                    if (seq == imageIndex) {
                        String data = base64Source.getData();
                        if (data == null || data.isEmpty()) {
                            throw new BizException("图片数据为空：" + sessionId + "#" + imageIndex);
                        }
                        byte[] bytes = Base64.getMimeDecoder().decode(data);
                        String mediaType = base64Source.getMediaType();
                        return new ImageData(bytes,
                                mediaType == null || mediaType.isBlank() ? "image/jpeg" : mediaType);
                    }
                    seq++;
                }
            }
        }
        throw new BizException("图片不存在：" + sessionId + "#" + imageIndex);
    }

    /** 会话内历史视频二进制：按 base64 视频出现顺序取第 videoIndex 个（仅本人会话） */
    public ImageData video(String sessionId, int videoIndex) {
        List<Msg> context = loadContext(sessionId);
        int seq = 0;
        for (Msg msg : context) {
            if (msg.getRole() != MsgRole.USER) {
                continue;
            }
            String name = msg.getName();
            if (name != null && name.startsWith("__compaction_summary__")) {
                continue;
            }
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof VideoBlock videoBlock
                        && videoBlock.getSource() instanceof Base64Source base64Source) {
                    if (seq == videoIndex) {
                        String data = base64Source.getData();
                        if (data == null || data.isEmpty()) {
                            throw new BizException("视频数据为空：" + sessionId + "#" + videoIndex);
                        }
                        byte[] bytes = Base64.getMimeDecoder().decode(data);
                        String mediaType = base64Source.getMediaType();
                        return new ImageData(bytes,
                                mediaType == null || mediaType.isBlank() ? "video/mp4" : mediaType);
                    }
                    seq++;
                }
            }
        }
        throw new BizException("视频不存在：" + sessionId + "#" + videoIndex);
    }

    /** 媒体原始字节 + MIME（供 Controller 以二进制响应返回，图片/视频取媒体端点共用） */
    public record ImageData(byte[] data, String mediaType) {
    }

    /** 校验会话归属并加载消息上下文（双槽合并：anonymous 旧段 + 用户槽新段，见 messages 注释） */
    private List<Msg> loadContext(String sessionId) {
        String userId = requireUserId();
        ChatSessionDO session = chatSessionMapper.selectByUserSession(userId, sessionId);
        if (session == null) {
            throw new BizException("会话不存在：" + sessionId);
        }
        return mergeSlotContexts(userId, sessionId, stateStore);
    }

    /**
     * 双槽上下文合并（回放读取专用，不影响 AG-UI 写入链路）：
     * 两槽都存在时 anonymous 旧段在前拼接用户槽新段（消息 id 去重）；仅单槽存在时直接返回。
     */
    static List<Msg> mergeSlotContexts(String userId, String sessionId, AgentStateStore stateStore) {
        Optional<AgentState> anon = stateStore.get("anonymous", sessionId, AGENT_STATE_KEY, AgentState.class);
        Optional<AgentState> user = stateStore.get(userId, sessionId, AGENT_STATE_KEY, AgentState.class);
        if (anon.isEmpty()) {
            return user.map(AgentState::getContext).orElse(List.of());
        }
        if (user.isEmpty()) {
            return anon.map(AgentState::getContext).orElse(List.of());
        }
        List<Msg> merged = new ArrayList<>(anon.get().getContext());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Msg msg : merged) {
            if (msg.getId() != null) {
                seen.add(msg.getId());
            }
        }
        for (Msg msg : user.get().getContext()) {
            if (msg.getId() == null || seen.add(msg.getId())) {
                merged.add(msg);
            }
        }
        return merged;
    }

    /**
     * 媒体源 → 历史回显地址：base64 源返回独立取媒体端点引用（序号按 base64 媒体出现顺序，
     * 与 image()/video() 遍历逻辑一致）；URL 源体积小直接原样返回。
     */
    private static String mediaRef(String endpointPrefix, String sessionId, Source source, int seq) {
        if (source instanceof Base64Source base64Source) {
            if (base64Source.getData() == null || base64Source.getData().isEmpty()) {
                return null;
            }
            return endpointPrefix + sessionId + "/" + seq;
        }
        if (source instanceof URLSource urlSource) {
            return urlSource.getUrl();
        }
        return null;
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
