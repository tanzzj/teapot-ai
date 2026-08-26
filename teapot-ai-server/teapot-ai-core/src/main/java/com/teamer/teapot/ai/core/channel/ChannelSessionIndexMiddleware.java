package com.teamer.teapot.ai.core.channel;

import com.teamer.teapot.ai.core.dao.ChannelSessionMapper;
import com.teamer.teapot.ai.core.model.ChannelSessionDO;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * Channel 会话索引中间件（SPEC §24.9）：
 * 仅装配到 channel 链路 Agent（ChannelHub build 时追加，Web 链路不受影响）；
 * 每次调用从 RuntimeContext 取 userId/sessionId upsert t_channel_session 索引行，
 * title 仅首次写入（首条用户文本截断 50 字，DB 侧 COALESCE 保留已有标题）。
 * 索引失败不阻断对话（消息体以 agentscope_sessions 为事实源，索引仅视图用）。
 */
@Slf4j
public class ChannelSessionIndexMiddleware implements MiddlewareBase {

    private static final int TITLE_MAX_LENGTH = 50;

    private final String agentKey;
    private final String channelType;
    private final ChannelSessionMapper channelSessionMapper;

    public ChannelSessionIndexMiddleware(String agentKey, String channelType,
                                         ChannelSessionMapper channelSessionMapper) {
        this.agentKey = agentKey;
        this.channelType = channelType;
        this.channelSessionMapper = channelSessionMapper;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        try {
            index(context, input);
        } catch (Exception e) {
            log.warn("channel 会话索引写入失败 agentKey={} userId={} sessionId={}",
                    agentKey, context.getUserId(), context.getSessionId(), e);
        }
        return next.apply(input);
    }

    private void index(RuntimeContext context, AgentInput input) {
        String userId = context.getUserId();
        String sessionId = context.getSessionId();
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        ChannelSessionDO record = new ChannelSessionDO();
        record.setAgentKey(agentKey);
        record.setUserId(userId);
        record.setSessionId(sessionId);
        record.setChannelType(channelType);
        record.setTitle(firstUserText(input));
        channelSessionMapper.upsert(record);
    }

    /** 本轮入参中首条用户文本（截断 50 字）；无文本返回 null（DB 侧保留已有标题） */
    private static String firstUserText(AgentInput input) {
        if (input == null || input.msgs() == null) {
            return null;
        }
        for (Msg msg : input.msgs()) {
            if (msg.getRole() != MsgRole.USER) {
                continue;
            }
            String text = msg.getTextContent();
            if (text != null && !text.isBlank()) {
                text = text.strip();
                return text.length() <= TITLE_MAX_LENGTH ? text : text.substring(0, TITLE_MAX_LENGTH);
            }
        }
        return null;
    }
}
