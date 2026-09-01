package com.teamer.teapot.ai.core.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * 媒体模态守卫（SPEC-media-gen §4.4）：把模型能力位（t_model_entry.capabilities）未声明的
 * 媒体块在「发给模型的请求视图」里降级为文本引用。
 * <p>
 * 背景：框架格式化器只判断消息含不含媒体块（{@code hasMediaContent}），不看模型支持哪些模态，
 * 于是 {@code dashscope_text_to_audio} 产出的音频块一旦写入会话历史，之后每轮重放都会被平台拒绝
 * （&lt;400&gt; InternalError.Algo.InvalidParameter: An incorrect modal `audio` was entered），
 * 单条消息即可把整个会话永久钉死。
 * <p>
 * 本守卫只改请求视图、不动持久化历史：媒体块仍留在会话状态里，前端产物卡片与历史回放照常渲染，
 * 模型侧则看到与框架 {@code AbstractBaseFormatter} 同措辞的文本引用。
 * 能力位覆盖的模态原样透传（支持 image/video 输入的模型仍可看图看片）；
 * DataBlock 模态语义不明确，不在降级范围内。
 */
@Slf4j
public class MediaModalGuardMiddleware implements MiddlewareBase {

    private static final String MODALITY_IMAGE = "image";
    private static final String MODALITY_AUDIO = "audio";
    private static final String MODALITY_VIDEO = "video";

    /** 模型可输入的媒体模态（小写）；空集 = 纯文本模型，全部媒体块降级为文本引用 */
    private final Set<String> supportedModalities;

    public MediaModalGuardMiddleware(Set<String> supportedModalities) {
        this.supportedModalities = supportedModalities == null
                ? Set.of() : Set.copyOf(supportedModalities);
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        List<Msg> messages = input.messages();
        List<String> downgraded = new ArrayList<>();
        List<Msg> rewrittenMessages = null;
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            Msg rewritten = downgradeMessage(msg, downgraded);
            if (rewritten != msg) {
                if (rewrittenMessages == null) {
                    rewrittenMessages = new ArrayList<>(messages);
                }
                rewrittenMessages.set(i, rewritten);
            }
        }
        if (rewrittenMessages != null) {
            log.warn("媒体模态已降级 sessionId={} model={} 能力位={} 降级块={}",
                    ctx == null ? "-" : ctx.getSessionId(),
                    input.model() == null ? "-" : input.model().getModelName(),
                    supportedModalities, downgraded);
            return next.apply(new ModelCallInput(rewrittenMessages, input.tools(),
                    input.options(), input.model()));
        }
        return next.apply(input);
    }

    /** 无块需要降级时原样返回同一实例（调用方以「实例是否变化」判断是否有改写） */
    private Msg downgradeMessage(Msg msg, List<String> sink) {
        List<ContentBlock> content = msg.getContent();
        List<ContentBlock> rewritten = null;
        for (int i = 0; i < content.size(); i++) {
            ContentBlock replacement = downgradeBlock(content.get(i), sink);
            if (replacement != null) {
                if (rewritten == null) {
                    rewritten = new ArrayList<>(content);
                }
                rewritten.set(i, replacement);
            }
        }
        return rewritten == null ? msg : msg.withContent(rewritten);
    }

    /** 需要替换时返回替换后的块，否则返回 null；工具结果递归处理其 output（保留 id/name/state） */
    private ContentBlock downgradeBlock(ContentBlock block, List<String> sink) {
        String modality = modalityOf(block);
        if (modality != null) {
            if (supportedModalities.contains(modality)) {
                return null;
            }
            sink.add(modality);
            return TextBlock.builder().text(textReference(block, modality)).build();
        }
        if (block instanceof ToolResultBlock result) {
            List<ContentBlock> output = result.getOutput();
            List<ContentBlock> rewritten = null;
            for (int i = 0; i < output.size(); i++) {
                ContentBlock replacement = downgradeBlock(output.get(i), sink);
                if (replacement != null) {
                    if (rewritten == null) {
                        rewritten = new ArrayList<>(output);
                    }
                    rewritten.set(i, replacement);
                }
            }
            if (rewritten == null) {
                return null;
            }
            return new ToolResultBlock(result.getId(), result.getName(), rewritten,
                    result.getMetadata(), result.getState());
        }
        return null;
    }

    /** 媒体块对应的模态标识；非媒体块返回 null */
    private static String modalityOf(ContentBlock block) {
        if (block instanceof ImageBlock) {
            return MODALITY_IMAGE;
        }
        if (block instanceof AudioBlock) {
            return MODALITY_AUDIO;
        }
        if (block instanceof VideoBlock) {
            return MODALITY_VIDEO;
        }
        return null;
    }

    /**
     * 媒体块的文本引用措辞对齐框架 AbstractBaseFormatter#convertMediaBlockToTextReference
     * （纯文本模型下的既有降级形态，行为已被验证）；内联 base64 不写临时文件，仅告知已展示。
     */
    private static String textReference(ContentBlock block, String modality) {
        Source source = block instanceof ImageBlock ib
                ? ib.getSource()
                : block instanceof AudioBlock ab
                        ? ab.getSource()
                        : ((VideoBlock) block).getSource();
        if (source instanceof URLSource urlSource) {
            return String.format(Locale.ROOT, "The returned %s can be found at: %s",
                    modality, urlSource.getUrl());
        }
        if (source instanceof Base64Source base64Source) {
            return String.format(Locale.ROOT,
                    "The returned %s (%s) is inline data and has already been rendered for the user",
                    modality, base64Source.getMediaType());
        }
        return String.format(Locale.ROOT, "[%s - unsupported source type]", modality);
    }
}
