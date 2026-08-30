package com.teamer.teapot.ai.core.service;

import com.teamer.teapot.ai.core.model.dto.SessionMessageItem;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.util.JsonUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AgentState.context → 可渲染消息条目的共享转换（SPEC §24.9：
 * 用户端会话历史与 admin 全量会话历史回放共用同一套 block→Item 规则）。
 * 图片/视频源解析由调用方按链路提供（用户端返回取媒体端点引用，admin 渠道会话可跳过）。
 */
public final class SessionMessageConverter {

    /** Msg.timestamp 格式（agentscope-core 固定），解析为 epoch millis 供前端展示消息时间 */
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 媒体源 → 回显地址；返回 null 表示该媒体不回显（seq 为 base64 媒体出现序号） */
    @FunctionalInterface
    public interface MediaRefResolver {
        String resolve(Source source, int base64Seq);
    }

    private SessionMessageConverter() {
    }

    public static List<SessionMessageItem> toItems(List<Msg> context,
                                                   MediaRefResolver imageRefResolver,
                                                   MediaRefResolver videoRefResolver) {
        List<SessionMessageItem> items = new ArrayList<>();
        int imageSeq = 0;
        int videoSeq = 0;
        for (Msg msg : context) {
            MsgRole role = msg.getRole();
            Long ts = parseTimestamp(msg);
            if (role == MsgRole.USER) {
                // 跳过 Runtime 压缩（compaction）注入的摘要消息，不当作用户发言回显
                String name = msg.getName();
                if (name != null && name.startsWith("__compaction_summary__")) {
                    continue;
                }
                // 按内容块拆分：文本与图片/视频各自成条，前端同一用户轮次合并为一张请求卡片
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof TextBlock textBlock) {
                        String text = textBlock.getText();
                        if (text != null && !text.isBlank()) {
                            items.add(new SessionMessageItem("user", "text", text, null, null, null, null, null, null, ts));
                        }
                    } else if (block instanceof ImageBlock imageBlock) {
                        String imageUrl = imageRefResolver.resolve(imageBlock.getSource(), imageSeq);
                        if (imageUrl != null) {
                            if (imageBlock.getSource() instanceof io.agentscope.core.message.Base64Source) {
                                imageSeq++;
                            }
                            items.add(new SessionMessageItem("user", "image", null, null, null, null, null, imageUrl, null, ts));
                        }
                    } else if (block instanceof VideoBlock videoBlock) {
                        String videoUrl = videoRefResolver.resolve(videoBlock.getSource(), videoSeq);
                        if (videoUrl != null) {
                            if (videoBlock.getSource() instanceof io.agentscope.core.message.Base64Source) {
                                videoSeq++;
                            }
                            items.add(new SessionMessageItem("user", "video", null, null, null, null, null, null, videoUrl, ts));
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
                        items.add(new SessionMessageItem("assistant", "reasoning", thinkingText, null, null, null, null, null, null, ts));
                    }
                } else if (block instanceof ToolUseBlock toolUse) {
                    items.add(new SessionMessageItem("assistant", "tool_call", null,
                            toolUse.getId(), toolUse.getName(), toolArgs(toolUse), null, null, null, ts));
                } else if (block instanceof ToolResultBlock toolResult) {
                    items.add(new SessionMessageItem("assistant", "tool_call_output", null,
                            toolResult.getId(), toolResult.getName(), null, toolResultText(toolResult), null, null, ts));
                } else if (block instanceof TextBlock textBlock) {
                    String text = textBlock.getText();
                    if (text != null && !text.isBlank()) {
                        items.add(new SessionMessageItem("assistant", "text", text, null, null, null, null, null, null, ts));
                    }
                }
            }
        }
        return items;
    }

    /** Msg.timestamp → epoch millis（服务端本地时区）；旧数据无时间戳或格式异常时返回 null */
    private static Long parseTimestamp(Msg msg) {
        String ts = msg.getTimestamp();
        if (ts == null || ts.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(ts, TS_FORMATTER)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception e) {
            return null;
        }
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

    /** 工具结果文本：与实时流（AguiStreamContext.serialize）同形态——
     *  文本块原样拼接，非文本块（image/video/audio 等媒体）逐块序列化 JSON 换行拼接，
     *  供前端 customToolRenderConfig 媒体卡片在历史回放时解析渲染 */
    private static String toolResultText(ToolResultBlock toolResult) {
        List<ContentBlock> output = toolResult.getOutput();
        if (output == null || output.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : output) {
            if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                sb.append(textBlock.getText());
            } else {
                try {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(JsonUtils.getJsonCodec().toJson(block));
                } catch (Exception e) {
                    // 不可序列化块跳过
                }
            }
        }
        return sb.toString();
    }
}
