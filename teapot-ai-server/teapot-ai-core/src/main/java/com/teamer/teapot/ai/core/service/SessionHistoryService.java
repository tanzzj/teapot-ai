package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.dao.ChannelSessionMapper;
import com.teamer.teapot.ai.core.dao.ChatSessionMapper;
import com.teamer.teapot.ai.core.model.ChannelSessionDO;
import com.teamer.teapot.ai.core.model.ChatSessionDO;
import com.teamer.teapot.ai.core.model.dto.SessionHistoryItem;
import com.teamer.teapot.ai.core.model.dto.SessionMessageItem;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import com.teamer.teapot.ai.rbac.model.TeapotUser;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Agent 全量会话历史（SPEC §24.9，admin 视图）：
 * t_chat_session（Web）与 t_channel_session（渠道）两个索引在查看层 union，
 * 消息回放以 agentscope_sessions 为事实源（stateStore 按 (userId, sessionId) 取状态）。
 * 全部接口仅 admin 可调（Service 层 requireAdmin 兜底，RBAC 通配匹配无法细分）。
 */
@Slf4j
@Service
public class SessionHistoryService {

    /** 单索引拉取上限（admin 视图分页在内存完成，量级可控） */
    private static final int INDEX_FETCH_LIMIT = 500;

    private final ChatSessionMapper chatSessionMapper;
    private final ChannelSessionMapper channelSessionMapper;
    private final MysqlAgentStateStore stateStore;

    public SessionHistoryService(ChatSessionMapper chatSessionMapper,
                                 ChannelSessionMapper channelSessionMapper,
                                 MysqlAgentStateStore stateStore) {
        this.chatSessionMapper = chatSessionMapper;
        this.channelSessionMapper = channelSessionMapper;
        this.stateStore = stateStore;
    }

    /**
     * union 两索引：返回 user/title/source/lastActiveAt，按活跃时间倒序，内存分页。
     * keyword 命中 userId/title/sessionId（忽略大小写）。
     */
    public List<SessionHistoryItem> list(String agentKey, int page, int size, String keyword) {
        requireAdmin();
        List<SessionHistoryItem> merged = new ArrayList<>();
        for (ChatSessionDO row : chatSessionMapper.selectByAgent(agentKey, INDEX_FETCH_LIMIT)) {
            merged.add(new SessionHistoryItem("web", row.getUserId(), row.getSessionId(),
                    row.getTitle(), iso(row.getUpdatedAt())));
        }
        for (ChannelSessionDO row : channelSessionMapper.selectByAgent(agentKey, INDEX_FETCH_LIMIT)) {
            merged.add(new SessionHistoryItem(row.getChannelType(), row.getUserId(), row.getSessionId(),
                    row.getTitle(), iso(row.getLastActiveAt())));
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase(Locale.ROOT);
            merged = merged.stream().filter(item -> contains(item.getUserId(), kw)
                    || contains(item.getTitle(), kw) || contains(item.getSessionId(), kw)).toList();
            merged = new ArrayList<>(merged);
        }
        merged.sort(Comparator.comparing(SessionHistoryItem::getLastActiveAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        int from = Math.max(page - 1, 0) * size;
        if (from >= merged.size()) {
            return List.of();
        }
        return merged.subList(from, Math.min(from + size, merged.size()));
    }

    /**
     * 会话全文回放：双槽合并读取（同 ChatSessionService：anonymous 旧段 + 用户槽新段），
     * 复用 SessionMessageConverter 的 block→Item 规则（与用户端一致）。
     * admin 专用，不校验会话归属。
     * 图片/视频引用仅 Web 会话生成取媒体端点（渠道 v1 纯文本，base64 媒体跳过）。
     */
    public List<SessionMessageItem> messages(String userId, String sessionId, String source) {
        requireAdmin();
        List<Msg> context = ChatSessionService.mergeSlotContexts(userId, sessionId, stateStore);
        boolean web = "web".equalsIgnoreCase(source);
        return SessionMessageConverter.toItems(context,
                webMediaResolver(sessionId, "/api/chat/session/image/", web),
                webMediaResolver(sessionId, "/api/chat/session/video/", web));
    }

    /**
     * 回放媒体源解析（图片/视频仅端点前缀不同）：非 Web 会话仅 URL 源可直回；
     * Web 会话 base64 源生成取媒体端点引用（序号按各自媒体出现顺序，与取媒体端点遍历一致）。
     */
    private static SessionMessageConverter.MediaRefResolver webMediaResolver(
            String sessionId, String endpointPrefix, boolean web) {
        return (mediaSource, seq) -> {
            if (!web) {
                // 渠道会话 v1 无图片/视频传输；万一存在仅 URL 源可直回
                return mediaSource instanceof io.agentscope.core.message.URLSource urlSource
                        ? urlSource.getUrl() : null;
            }
            if (mediaSource instanceof io.agentscope.core.message.Base64Source base64Source
                    && (base64Source.getData() == null || base64Source.getData().isEmpty())) {
                return null;
            }
            if (mediaSource instanceof io.agentscope.core.message.URLSource urlSource) {
                return urlSource.getUrl();
            }
            return endpointPrefix + sessionId + "/" + seq;
        };
    }

    /**
     * 删除单条历史会话（admin）：stateStore 状态（含 anonymous 兜底槽位）+ 对应索引表行。
     * 状态删除失败不阻塞索引清理（与 ChatSessionService.clear 一致）。
     */
    public void delete(String userId, String sessionId, String source) {
        requireAdmin();
        try {
            stateStore.delete(userId, sessionId);
        } catch (Exception e) {
            log.warn("stateStore 删除失败 userId={} sessionId={}", userId, sessionId, e);
        }
        try {
            stateStore.delete("anonymous", sessionId);
        } catch (Exception e) {
            log.warn("stateStore 删除(anonymous)失败 sessionId={}", sessionId, e);
        }
        if ("web".equalsIgnoreCase(source)) {
            chatSessionMapper.delete(userId, sessionId);
        } else {
            channelSessionMapper.delete(userId, sessionId);
        }
    }

    private static boolean contains(String value, String kw) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(kw);
    }

    private static String iso(LocalDateTime time) {
        return time == null ? null : time.toString();
    }

    /** admin 兜底校验：RBAC 通配（/api/agent/*）无法细分到本接口，Service 层强制 */
    private static void requireAdmin() {
        TeapotUser user = ContextUtil.getUserFromContext();
        if (user == null) {
            throw new BizException(Result.CODE_UNAUTHORIZED, "用户未登录");
        }
        if (user.getRoleList() == null || !user.getRoleList().contains("admin")) {
            throw new BizException(Result.CODE_FORBIDDEN, "仅 admin 可查看 Agent 全量会话历史");
        }
    }
}
