package com.teamer.teapot.ai.core.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolSuspendException;

import java.util.List;

/**
 * ask_user_question 工具（AG-UI 人机交互）：
 * Agent 缺少关键信息时向用户提出多选一问题；工具抛出 {@link ToolSuspendException} 挂起运行，
 * 框架把挂起转成 AG-UI RUN_FINISHED outcome.interrupts（reason=tool_call），
 * 前端渲染选项卡片，用户选择后经 resume 回传，答案作为本工具调用的结果恢复执行。
 *
 * <p>仅注册在 Web（AG-UI）链路：渠道消息无法渲染选项卡片，挂起将无人应答。
 */
public class AskUserQuestionTool {

    @Tool(
            name = "ask_user_question",
            readOnly = true,
            description =
                    "向用户提出一个多选一问题并等待回答。仅在继续任务所需的关键信息缺失、"
                            + "且无法从上下文推断时使用（如方向确认、偏好选择）。"
                            + "用户会在界面上看到问题与选项卡片，其选择将作为本工具的返回结果；"
                            + "不要自行替用户猜测答案。")
    public String askUserQuestion(
            @ToolParam(name = "question", description = "向用户提出的问题，一句话表述清楚")
                    String question,
            @ToolParam(name = "options", description = "2 到 4 个候选答案，互斥且覆盖主要选择")
                    List<String> options) {
        if (question == null || question.isBlank()) {
            return "参数错误：question 不能为空";
        }
        if (options == null
                || options.size() < 2
                || options.size() > 4
                || options.stream().anyMatch(o -> o == null || o.isBlank())) {
            return "参数错误：options 必须提供 2 到 4 个非空候选答案";
        }
        // 挂起执行：等待用户在前端选择，答案经 AG-UI resume 以 ToolResultBlock 回传后继续
        throw new ToolSuspendException("等待用户回答：" + question);
    }
}
