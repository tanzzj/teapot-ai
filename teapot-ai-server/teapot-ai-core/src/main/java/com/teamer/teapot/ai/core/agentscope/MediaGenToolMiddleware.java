package com.teamer.teapot.ai.core.agentscope;

import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;

/**
 * 生图/生视频能力中间件（runtime.enableMediaGen 开关，SPEC-media-gen §4.1）：
 * 包装 AgentScope 原生 DashScopeMultiModalTool，并向 system prompt 注入用法与产物转存指引。
 */
public class MediaGenToolMiddleware implements ToolProvidedMiddleware {

    private static final String USAGE = """
            ## 生图/生视频能力
            你具备基于 DashScope 的媒体生成工具：
            - dashscope_text_to_image：文生图（默认模型 wanx-v1，可指定其他文生图模型）。
            - dashscope_text_to_video：文生视频（默认模型 wan2.6-t2v，耗时可达分钟级，生成期间请耐心等待）。
            - dashscope_image_to_video：图生视频（默认模型 wan2.6-i2v-flash，image_url 传首帧图）。
            - dashscope_first_and_last_frame_image_to_video：首尾帧生视频（默认模型 wan2.2-kf2v-flash）。
            使用约定：
            - 生成产物会由前端会话界面自动以图片/视频卡片渲染展示，你无法也无需获取产物链接；
              回复时用文字描述产物内容即可，不要粘贴或虚构任何图片/视频链接。
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
