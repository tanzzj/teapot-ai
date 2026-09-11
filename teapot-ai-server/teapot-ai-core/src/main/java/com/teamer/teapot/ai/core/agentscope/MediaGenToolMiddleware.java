package com.teamer.teapot.ai.core.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 生图/生视频能力中间件（runtime.enableMediaGen 开关，SPEC-media-gen §4.1）：
 * 包装 AgentScope 原生 DashScopeMultiModalTool，并向 system prompt 注入用法与产物转存指引。
 * 媒体产物块回传模型前由 {@link MediaModalGuardMiddleware} 按模型能力位做模态守卫（SPEC-media-gen §4.4）。
 *
 * <p>模型锁定（SPEC-media-gen §4.8）：runtime.mediaModels 非空时双保险生效——
 * prompt 里告知模型该能力已固定为哪个模型（{@link MediaModelCatalog#lockedUsage}），
 * acting 入口再把 model 入参强行覆写（不信任模型听不听话，参照
 * {@link DangerousCommandGuardMiddleware} 的同样手法）。
 */
@Slf4j
public class MediaGenToolMiddleware implements ToolProvidedMiddleware {

    private static final String USAGE = """
            ## 生图/生视频能力
            你具备基于 DashScope 的媒体生成工具：
            - dashscope_text_to_image：文生图（默认模型 wanx-v1，可指定其他文生图模型）。
            - dashscope_image_to_image：图生图（参考图 + 文字提示改写）。
            - dashscope_text_to_video：文生视频（默认模型 wan2.6-t2v，耗时可达分钟级，生成期间请耐心等待）。
            - dashscope_image_to_video：图生视频（默认模型 wan2.6-i2v-flash，image_url 传首帧图）。
            - dashscope_first_and_last_frame_image_to_video：首尾帧生视频（默认模型 wan2.2-kf2v-flash）。
            - dashscope_text_to_audio：文本转语音（默认模型 qwen3-tts-flash，可指定 voice / language）。
              language 只能取 chinese / english / german / italian / portuguese / spanish /
              japanese / korean / french / russian / auto（百炼报错实测值，首字母大写也可），
              不支持粤语等方言：要转方言/拿不准语种时省略 language 或直接用 auto，不要自造参数值。
            使用约定：
            - 以用户附件为输入的任务（按原图上色/改图/图生视频）必须用 *-image_to_* 工具，
              image_url 取自用户消息末尾的「[附件地址]」清单（那是附件的可访问 URL，
              与你能看到的图片内容是同一张）；沙箱工作区里没有对应文件是正常的，
              不能据此判定附件不可用。清单缺失时先向用户索要可访问链接，
              不要静默改用文生图重绘。
            - 生成产物会由前端会话界面自动以图片/视频/音频卡片渲染展示，你无法也无需获取产物链接；
              回复时用文字描述产物内容即可，不要粘贴或虚构任何图片/视频/音频链接
              （上下文若出现 “The returned ... can be found at” 形式的链接引用，那是降级后的旁证，不要转述）。
            - 不要在未调用生成工具的情况下虚构生成结果。""";

    private final DashScopeMultiModalTool tools;
    /** 工具名 → 指定生成模型；空 = 不锁定（跟随工具默认） */
    private final Map<String, String> lockedModels;

    public MediaGenToolMiddleware(DashScopeMultiModalTool tools, Map<String, String> lockedModels) {
        this.tools = tools;
        this.lockedModels = lockedModels == null ? Map.of() : Map.copyOf(lockedModels);
    }

    @Override
    public Object providedTools() {
        return tools;
    }

    @Override
    public String toolUsageDescription() {
        String locked = MediaModelCatalog.lockedUsage(lockedModels);
        return locked == null ? USAGE : USAGE + "\n" + locked;
    }

    /**
     * 模型锁定覆写：命中已配置的生成工具时，把 model 入参换成指定值（模型留空/乱填也拦得住）。
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (lockedModels.isEmpty() || input.toolCalls() == null) {
            return next.apply(input);
        }
        List<ToolUseBlock> calls = input.toolCalls();
        List<ToolUseBlock> rewritten = null;
        for (int i = 0; i < calls.size(); i++) {
            ToolUseBlock call = calls.get(i);
            String wanted = lockedModels.get(call.getName());
            if (wanted == null) {
                continue;
            }
            Object current = call.getInput() == null ? null : call.getInput().get("model");
            if (wanted.equals(current)) {
                continue;
            }
            log.info("生成模型已按 Agent 配置覆写 tool={} 传入={} 指定={} sessionId={}",
                    call.getName(), current, wanted, ctx == null ? "-" : ctx.getSessionId());
            if (rewritten == null) {
                rewritten = new ArrayList<>(calls);
            }
            rewritten.set(i, withModel(call, wanted));
        }
        return next.apply(rewritten == null ? input : new ActingInput(rewritten));
    }

    /** 保留 id/name 与其余入参，仅替换 model */
    private static ToolUseBlock withModel(ToolUseBlock call, String model) {
        Map<String, Object> input = new LinkedHashMap<>(
                call.getInput() == null ? Map.of() : call.getInput());
        input.put("model", model);
        return ToolUseBlock.builder()
                .id(call.getId())
                .name(call.getName())
                .input(input)
                .build();
    }
}
