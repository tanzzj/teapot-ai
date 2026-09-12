package com.teamer.teapot.ai.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 用户附件地址注入（SPEC-media-gen §4.5）：让用户上传的图片/视频在「发给模型的请求视图」里
 * 额外带一份 URL 文本清单。
 * <p>
 * 背景：模型能力位覆盖 image/video 时，附件以媒体块原样透传（见
 * {@link MediaModalGuardMiddleware}），模型看到的是解码后的像素而不是那串地址；
 * 而 {@code dashscope_image_to_image} / {@code dashscope_image_to_video} 这类工具的入参
 * 必须是字符串形态的 URL。附件既不落沙箱、URL 又以文本可见，模型只能退化成文生图重绘
 * （session 7acef945-70aa-49b7-9433-bafe17f9e283 实测）。
 * <p>
 * 与守卫同源：只改请求视图、不动持久化历史，前端与会话回放仍按媒体块渲染；追加内容是
 * 确定性文本（同一消息每轮得到同一份清单），不打断模型侧前缀缓存。守卫已降级为文本引用的
 * 块不再是媒体块，两个中间件叠加不会出现重复地址。
 */
@Slf4j
public class UserAttachmentRefMiddleware implements MiddlewareBase {

    private static final String MODALITY_IMAGE = "image";
    private static final String MODALITY_AUDIO = "audio";
    private static final String MODALITY_VIDEO = "video";

    private static final String HEADER = """
            [附件地址] 本条消息的媒体附件以下列可访问 URL 传入。需要以原图/原视频为输入时\
            （图生图、图生视频、首尾帧生视频等），直接把对应 URL 作为 image_url 传给工具，\
            不要因为沙箱工作区里没有这个文件就退化成文生图重绘；URL 仅作工具入参，\
            不要在回复里转述给用户。""";

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        List<Msg> messages = input.messages();
        List<Msg> rewrittenMessages = null;
        List<String> injected = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            Msg rewritten = appendRefs(msg, injected);
            if (rewritten != msg) {
                if (rewrittenMessages == null) {
                    rewrittenMessages = new ArrayList<>(messages);
                }
                rewrittenMessages.set(i, rewritten);
            }
        }
        if (rewrittenMessages != null) {
            log.info("用户附件地址已注入 sessionId={} 附件={}",
                    ctx == null ? "-" : ctx.getSessionId(), injected);
            return next.apply(new ModelCallInput(rewrittenMessages, input.tools(),
                    input.options(), input.model()));
        }
        return next.apply(input);
    }

    /** 无 URL 源附件时原样返回同一实例（调用方以「实例是否变化」判断是否改写） */
    private Msg appendRefs(Msg msg, List<String> sink) {
        if (msg.getRole() != MsgRole.USER) {
            return msg;
        }
        List<ContentBlock> content = msg.getContent();
        List<String> refs = new ArrayList<>();
        int seq = 0;
        for (ContentBlock block : content) {
            String modality = modalityOf(block);
            if (modality == null) {
                continue;
            }
            seq++;
            String url = urlOf(block);
            if (url != null) {
                refs.add(seq + ". " + modality + ": " + url);
            }
        }
        if (refs.isEmpty()) {
            return msg;
        }
        sink.addAll(refs);
        List<ContentBlock> rewritten = new ArrayList<>(content);
        rewritten.add(TextBlock.builder()
                .text(HEADER + "\n" + String.join("\n", refs)).build());
        return msg.withContent(rewritten);
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

    /** 媒体块的公网可访问地址；内联 base64 等无地址来源返回 null */
    private static String urlOf(ContentBlock block) {
        Source source = block instanceof ImageBlock ib
                ? ib.getSource()
                : block instanceof AudioBlock ab
                        ? ab.getSource()
                        : ((VideoBlock) block).getSource();
        if (source instanceof URLSource urlSource
                && urlSource.getUrl() != null && !urlSource.getUrl().isBlank()) {
            return urlSource.getUrl();
        }
        return null;
    }
}
