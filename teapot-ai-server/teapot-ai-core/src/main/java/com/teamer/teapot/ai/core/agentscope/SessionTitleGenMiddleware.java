package com.teamer.teapot.ai.core.agentscope;

import com.teamer.teapot.ai.core.dao.ChatSessionMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

/**
 * 会话标题异步生成中间件：
 * 在 onAgent 阶段提取首条用户消息，异步调 LLM 生成简短标题（≤20 字），
 * 写 DB（ChatSessionMapper.touch）并经 AgentEventEmitter 推 CUSTOM 事件到前端。
 * 仅当会话标题仍为 Agent 默认名称时触发（懒创建后首次对话）。
 * 全程不阻塞主对话流。
 */
@Slf4j
public class SessionTitleGenMiddleware implements MiddlewareBase {

    private static final int TITLE_MAX_LENGTH = 20;
    private static final int MAX_TOKENS = 50;
    private static final String CUSTOM_EVENT_NAME = "session_title";
    private static final Executor EXECUTOR = ForkJoinPool.commonPool();
    /** 仅会话创建在此时间窗口内视为「新会话」，超过则跳过标题生成 */
    private static final int NEW_SESSION_WINDOW_MINUTES = 2;

    private final String agentKey;
    private final String agentDefaultName;
    private final ChatSessionMapper chatSessionMapper;
    private final Model model;

    public SessionTitleGenMiddleware(String agentKey, String agentDefaultName,
                                     ChatSessionMapper chatSessionMapper, Model model) {
        this.agentKey = agentKey;
        this.agentDefaultName = agentDefaultName;
        this.chatSessionMapper = chatSessionMapper;
        this.model = model;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        // 前置条件：有用户消息、有 userId/sessionId
        String userId = ctx.getUserId();
        String sessionId = ctx.getSessionId();
        String firstUserText = extractFirstUserText(input);

        final boolean shouldGenerate;
        if (firstUserText != null && !firstUserText.isBlank()
                && userId != null && !userId.isBlank()
                && sessionId != null && !sessionId.isBlank()) {
            var session = chatSessionMapper.selectByUserSession(userId, sessionId);
            shouldGenerate = isNewSession(session) && isTitleDefault(session);
        } else {
            shouldGenerate = false;
        }

        // 用 deferContextual 拿到 Reactor Context → 取 AgentEventEmitter
        return Flux.deferContextual(ctxView -> {
            if (shouldGenerate) {
                AgentEventEmitter.fromContext(ctxView).ifPresent(emitter ->
                    CompletableFuture.runAsync(() ->
                        generateAndEmitTitle(firstUserText, userId, sessionId, emitter), EXECUTOR)
                );
            }
            return next.apply(input);
        });
    }

    /** 调 LLM 生成标题 → 写 DB → emit CustomEvent */
    private void generateAndEmitTitle(String userText, String userId, String sessionId,
                                      AgentEventEmitter emitter) {
        try {
            String title = callLlmForTitle(userText);
            if (title == null || title.isBlank()) return;
            title = title.trim();
            if (title.length() > TITLE_MAX_LENGTH) {
                title = title.substring(0, TITLE_MAX_LENGTH);
            }
            // 写 DB
            chatSessionMapper.touch(userId, sessionId, title);
            // 推前端
            emitter.emit(new CustomEvent(CUSTOM_EVENT_NAME, Map.of("title", title)));
            log.info("会话标题已生成 agentKey={} sessionId={} title={}", agentKey, sessionId, title);
        } catch (Exception e) {
            log.warn("会话标题生成失败（不中断对话）sessionId={} err={}", sessionId, e.toString());
        }
    }

    /** 调 Model 生成标题（非流式收集，maxTokens=50 轻量调用） */
    private String callLlmForTitle(String userText) {
        String truncated = userText.length() > 500 ? userText.substring(0, 500) : userText;
        List<Msg> messages = List.of(
            new SystemMessage("你是一个标题生成助手。根据用户的问题，生成一个简短的对话标题（不超过15个字）。"
                + "只输出标题文本，不要加引号、标点或其他内容。"),
            new UserMessage("用户问题：" + truncated)
        );
        GenerateOptions options = GenerateOptions.builder().maxTokens(MAX_TOKENS).build();
        StringBuilder sb = new StringBuilder();
        model.stream(messages, null, options)
            .toStream()
            .forEach(resp -> {
                if (resp.getContent() != null) {
                    for (ContentBlock block : resp.getContent()) {
                        if (block instanceof TextBlock tb) {
                            sb.append(tb.getText());
                        }
                    }
                }
            });
        return sb.toString().trim();
    }

    /**
     * 判断会话是否为「新创建」：createdAt 在窗口期内。
     * 老会话（用户创建后离开、隔很久才发首条消息）直接跳过，避免无谓 LLM 调用。
     */
    private static boolean isNewSession(com.teamer.teapot.ai.core.model.ChatSessionDO session) {
        if (session == null || session.getCreatedAt() == null) return false;
        return session.getCreatedAt().isAfter(
                LocalDateTime.now().minusMinutes(NEW_SESSION_WINDOW_MINUTES));
    }

    /** 标题仍为 Agent 默认名称（懒创建时设的初始值），说明还没被改过 */
    private boolean isTitleDefault(com.teamer.teapot.ai.core.model.ChatSessionDO session) {
        if (session == null) return false;
        String title = session.getTitle();
        return title == null || title.equals(agentDefaultName);
    }

    /** 提取本轮首条用户消息文本 */
    private static String extractFirstUserText(AgentInput input) {
        if (input == null || input.msgs() == null) return null;
        for (Msg msg : input.msgs()) {
            if (msg.getRole() != MsgRole.USER) continue;
            String text = msg.getTextContent();
            if (text != null && !text.isBlank()) return text.strip();
        }
        return null;
    }
}
