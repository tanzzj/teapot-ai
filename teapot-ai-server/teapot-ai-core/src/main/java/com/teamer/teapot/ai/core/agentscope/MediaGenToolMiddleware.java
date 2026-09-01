package com.teamer.teapot.ai.core.agentscope;

import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;

/**
 * 生图/生视频能力中间件（runtime.enableMediaGen 开关，SPEC-media-gen §4.1）：
 * 包装 AgentScope 原生 DashScopeMultiModalTool，并向 system prompt 注入用法与产物转存指引。
 * 媒体产物块回传模型前由 {@link MediaModalGuardMiddleware} 按模型能力位做模态守卫（SPEC-media-gen §4.4）。
 */
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
            - 生成产物会由前端会话界面自动以图片/视频/音频卡片渲染展示，你无法也无需获取产物链接；
              回复时用文字描述产物内容即可，不要粘贴或虚构任何图片/视频/音频链接
              （上下文若出现 “The returned ... can be found at” 形式的链接引用，那是降级后的旁证，不要转述）。
            - 不要在未调用生成工具的情况下虚构生成结果。""";

    private final DashScopeMultiModalTool tools;

    public MediaGenToolMiddleware(DashScopeMultiModalTool tools) {
        this.tools = tools;
    }

    @Override
    public Object providedTools() {
        return tools;
    }

    @Override
    public String toolUsageDescription() {
        return USAGE;
    }
}
