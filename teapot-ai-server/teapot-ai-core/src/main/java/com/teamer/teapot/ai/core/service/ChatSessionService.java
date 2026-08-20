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
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
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
     * 注意：AG-UI 异步链路 ContextUtil ThreadLocal 丢失，写入侧 userId 兜底为
     * "anonymous"（见 TeapotRuntimeContextResolver），故先查 anonymous 槽位，
     * 再兜底当前用户槽位。
     * 图片不内联 base64（单张可达数百 KB，内联会让响应体膨胀导致弱网下传输被掐断），
     * 而是返回独立取图端点引用，前端带鉴权单独拉取。
     */
    public List<SessionMessageItem> messages(String sessionId) {
        List<Msg> context = loadContext(sessionId);
        List<SessionMessageItem> items = new ArrayList<>();
        int imageSeq = 0;
        for (Msg msg : context) {
            MsgRole role = msg.getRole();
            if (role == MsgRole.USER) {
                // 跳过 Runtime 压缩（compaction）注入的摘要消息，不当作用户发言回显
                String name = msg.getName();
                if (name != null && name.startsWith("__compaction_summary__")) {
                    continue;
                }
                // 按内容块拆分：文本与图片各自成条，前端同一用户轮次合并为一张请求卡片
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof TextBlock textBlock) {
                        String text = textBlock.getText();
                        if (text != null && !text.isBlank()) {
                            items.add(new SessionMessageItem("user", "text", text, null, null, null, null, null));
                        }
                    } else if (block instanceof ImageBlock imageBlock) {
                        String imageUrl = imageRef(sessionId, imageBlock.getSource(), imageSeq);
                        if (imageUrl != null) {
                            if (imageBlock.getSource() instanceof Base64Source) {
                                imageSeq++;
                            }
                            items.add(new SessionMessageItem("user", "image", null, null, null, null, null, imageUrl));
                        }
                    }
                }
                continue;
            }
            // assistant/tool 消息按内容块拆成可渲染条目：思考/工具调用/工具结果/文本
            if (role != MsgRole.ASSISTANT && role != MsgRole.TOOL) {
                continue;
            }
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ThinkingBlock thinking) {
                    String thinkingText = thinking.getThinking();
                    if (thinkingText != null && !thinkingText.isBlank()) {
                        items.add(new SessionMessageItem("assistant", "reasoning", thinkingText, null, null, null, null, null));
                    }
                } else if (block instanceof ToolUseBlock toolUse) {
                    items.add(new SessionMessageItem("assistant", "tool_call", null,
                            toolUse.getId(), toolUse.getName(), toolArgs(toolUse), null, null));
                } else if (block instanceof ToolResultBlock toolResult) {
                    items.add(new SessionMessageItem("assistant", "tool_call_output", null,
                            toolResult.getId(), toolResult.getName(), null, toolResultText(toolResult), null));
                } else if (block instanceof TextBlock textBlock) {
                    String text = textBlock.getText();
                    if (text != null && !text.isBlank()) {
                        items.add(new SessionMessageItem("assistant", "text", text, null, null, null, null, null));
                    }
                }
            }
        }
        return items;
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

    /** 图片原始字节 + MIME（供 Controller 以二进制响应返回） */
    public record ImageData(byte[] data, String mediaType) {
    }

    /** 校验会话归属并加载消息上下文（先 anonymous 槽位，后当前用户槽位） */
    private List<Msg> loadContext(String sessionId) {
        String userId = requireUserId();
        ChatSessionDO session = chatSessionMapper.selectByUserSession(userId, sessionId);
        if (session == null) {
            throw new BizException("会话不存在：" + sessionId);
        }
        Optional<AgentState> state = stateStore.get("anonymous", sessionId, AGENT_STATE_KEY, AgentState.class);
        if (state.isEmpty()) {
            state = stateStore.get(userId, sessionId, AGENT_STATE_KEY, AgentState.class);
        }
        return state.map(AgentState::getContext).orElse(List.of());
    }

    /**
     * 图片源 → 历史回显地址：base64 源返回独立取图端点引用（序号按 base64 图片出现顺序，
     * 与 image() 遍历逻辑一致）；URL 源体积小直接原样返回。
     */
    private static String imageRef(String sessionId, Source source, int imageSeq) {
        if (source instanceof Base64Source base64Source) {
            if (base64Source.getData() == null || base64Source.getData().isEmpty()) {
                return null;
            }
            return "/api/chat/session/image/" + sessionId + "/" + imageSeq;
        }
        if (source instanceof URLSource urlSource) {
            return urlSource.getUrl();
        }
        return null;
    }

    /** 工具调用参数：优先原始 JSON 串，否则序列化解析后的入参 */
    private static String toolArgs(ToolUseBlock toolUse) {
        String raw = toolUse.getContent();
        if (raw != null && !raw.isBlank()) {
            return raw;
        }
        Map<String, Object> input = toolUse.getInput();
        if (input == null || input.isEmpty()) {
            return "";
        }
        try {
            return JsonUtils.getJsonCodec().toJson(input);
        } catch (Exception e) {
            return String.valueOf(input);
        }
    }

    /** 工具结果文本：拼接 output 中的文本块 */
    private static String toolResultText(ToolResultBlock toolResult) {
        List<ContentBlock> output = toolResult.getOutput();
        if (output == null || output.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : output) {
            if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                sb.append(textBlock.getText());
            }
        }
        return sb.toString();
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
