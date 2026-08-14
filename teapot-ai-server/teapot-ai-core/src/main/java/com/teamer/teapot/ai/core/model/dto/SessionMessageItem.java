package com.teamer.teapot.ai.core.model.dto;

/**
 * 会话历史消息条目（从 agentscope_sessions 的 agent_state 提取）：
 * 仅返回 role 与纯文本，供前端聊天模板恢复历史画面。
 */
public record SessionMessageItem(String role, String text) {
}
