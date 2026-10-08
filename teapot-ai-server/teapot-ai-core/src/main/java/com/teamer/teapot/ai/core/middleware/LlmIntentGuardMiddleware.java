package com.teamer.teapot.ai.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 大模型意图守卫（feature.guard mode=llm）：与 JevIntentGuardMiddleware 同款两钩子语义，
 * 判定器换成一个 LLM——onAgent 用守卫规则 + 最新用户请求做一次轻量判定（只认 ACCEPT/REJECT），
 * 被拒请求在 onModelCall 换成拒答模型，主模型完全看不到该请求，拒答文案走标准管线
 * （流式输出、写入历史、终止本轮）。判定失败/输出不可解析时放行（failOpen，不锁死会话）。
 */
@Slf4j
public class LlmIntentGuardMiddleware implements MiddlewareBase {

    public static final String DEFAULT_DENY_MESSAGE =
            "抱歉，该请求超出了本 Agent 允许处理的意图范围，请换个问题试试。";

    private static final int JUDGE_MAX_TOKENS = 10;
    /** 送判的用户请求截断长度，防止超长输入抬高判定延迟 */
    private static final int USER_TEXT_LIMIT = 1000;

    private final Model judgeModel;
    private final String judgePrompt;
    private final String denyMessage;

    public LlmIntentGuardMiddleware(Model judgeModel, List<String> guardRules, String denyMessage) {
        this.judgeModel = judgeModel;
        this.denyMessage = denyMessage == null || denyMessage.isBlank()
                ? DEFAULT_DENY_MESSAGE : denyMessage;
        StringBuilder prompt = new StringBuilder(
                "你是一个意图守卫。判断用户的最新请求是否被下面的守卫规则允许。"
                        + "规则中「接受的意图」是白名单，「拒绝的意图」是黑名单："
                        + "请求命中任一拒绝意图即不允许；若存在接受的意图而请求未命中任何一条，"
                        + "也不允许。只输出 ACCEPT（允许）或 REJECT（不允许），不要输出其他内容。"
                        + "守卫规则：\n");
        for (String rule : guardRules) {
            prompt.append("- ").append(rule).append('\n');
        }
        this.judgePrompt = prompt.toString();
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        String userText = latestUserText(input.msgs());
        if (userText.isBlank()) {
            ctx.put(Decision.class, Decision.pass());
            return next.apply(input);
        }
        return judge(userText)
                .onErrorResume(error -> {
                    log.warn("意图守卫 LLM 判定失败，放行（failOpen）", error);
                    return Mono.just(Decision.pass());
                })
                .flatMapMany(decision -> {
                    ctx.put(Decision.class, decision);
                    if (decision.denied()) {
                        log.info("意图守卫（LLM 判定）拒绝了用户请求");
                    }
                    return next.apply(input);
                });
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        Decision decision = ctx.get(Decision.class);
        if (decision == null || !decision.denied()) {
            return next.apply(input);
        }
        return next.apply(new ModelCallInput(
                input.messages(), input.tools(), input.options(), new RefusalModel(denyMessage)));
    }

    /** 调判定模型收集完整输出并解析；无 ACCEPT/REJECT 视为不可解析 → 放行 */
    private Mono<Decision> judge(String userText) {
        String truncated = userText.length() > USER_TEXT_LIMIT
                ? userText.substring(0, USER_TEXT_LIMIT) : userText;
        List<Msg> messages = List.of(
                new SystemMessage(judgePrompt),
                new UserMessage("用户请求：" + truncated));
        GenerateOptions options = GenerateOptions.builder().maxTokens(JUDGE_MAX_TOKENS).build();
        return judgeModel.stream(messages, null, options)
                .reduce(new StringBuilder(), (sb, resp) -> {
                    if (resp.getContent() != null) {
                        for (ContentBlock block : resp.getContent()) {
                            if (block instanceof TextBlock tb && tb.getText() != null) {
                                sb.append(tb.getText());
                            }
                        }
                    }
                    return sb;
                })
                .map(sb -> parseVerdict(sb.toString()));
    }

    private static Decision parseVerdict(String output) {
        String upper = output.toUpperCase();
        // 先认 REJECT：部分模型会输出 "NOT ACCEPT" 之类的措辞
        if (upper.contains("REJECT")) {
            return new Decision(true);
        }
        if (upper.contains("ACCEPT")) {
            return Decision.pass();
        }
        log.warn("意图守卫 LLM 判定输出不可解析，放行（failOpen）output={}", output.trim());
        return Decision.pass();
    }

    /** 最新一条有文本内容的用户消息 */
    private static String latestUserText(List<Msg> msgs) {
        if (msgs == null) {
            return "";
        }
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg msg = msgs.get(i);
            if (msg.getRole() != MsgRole.USER) {
                continue;
            }
            String text = msg.getTextContent();
            if (text != null && !text.isBlank()) {
                return text.strip();
            }
        }
        return "";
    }

    /** 单次调用的守卫判定结果 */
    public record Decision(boolean denied) {
        static Decision pass() {
            return new Decision(false);
        }
    }

    /** 固定拒答流的模型替身：主模型被完全绕开 */
    private static final class RefusalModel implements Model {

        private final String reply;

        private RefusalModel(String reply) {
            this.reply = reply;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                    .id("llm-intent-guard-" + UUID.randomUUID())
                    .content(Collections.singletonList(
                            TextBlock.builder().text(reply).build()))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "llm-intent-guard";
        }
    }
}
