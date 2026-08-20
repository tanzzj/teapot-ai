package com.teamer.teapot.ai.core.model.dto;

/**
 * 会话历史消息条目（从 agentscope_sessions 的 agent_state 提取）：
 * 每条对应一个可渲染块，前端按顺序拼装为模板卡片——
 * - user + type=text / type=image：用户文本与图片（imageUrl 为 data URL 或 http URL）
 * - assistant + type=text：文本消息
 * - type=reasoning：深度思考（ThinkingBlock）
 * - type=tool_call / tool_call_output：工具调用与结果（ToolUseBlock / ToolResultBlock）
 */
public record SessionMessageItem(
        String role,
        String type,
        String text,
        String toolCallId,
        String toolName,
        String arguments,
        String output,
        String imageUrl) {
}
