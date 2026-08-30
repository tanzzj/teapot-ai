package com.teamer.teapot.ai.core.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.ToolDangerousPathConstants;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 危险命令守卫（「阻止危险命令」模式专属）：
 * 2.0.1 的 shell 工具（execute）不支持内容级规则匹配（ToolBase.matchRule 默认仅认 null 规则），
 * 官方「BYPASS + DENY 规则」配方对其无效，故由本中间件在 acting 阶段入口拦截：
 * 命中危险命令清单（对齐框架 ToolDangerousPathConstants.DANGEROUS_COMMANDS）的 execute 调用
 * 被替换为无害的提示命令，其余工具调用不受影响。
 */
@Slf4j
public class DangerousCommandGuardMiddleware implements MiddlewareBase {

    /** shell 工具注册名（harness ShellExecuteTool.NAME） */
    private static final String SHELL_TOOL = "execute";

    /** 命中后的替换命令：固定无害提示，不回显原始命令（避免注入回显） */
    private static final String BLOCKED_COMMAND =
            "echo \"[teapot-permission] 危险命令已被拦截（当前为 阻止危险命令 模式），请更换更安全的操作\"";

    /** 危险命令模式（词边界保护，避免 add/ddos 之类误伤），来源对齐框架 DANGEROUS_COMMANDS */
    private static final List<Pattern> DANGEROUS_PATTERNS = buildPatterns();

    private static List<Pattern> buildPatterns() {
        List<Pattern> patterns = new ArrayList<>();
        for (String dangerous : ToolDangerousPathConstants.DANGEROUS_COMMANDS) {
            patterns.add(Pattern.compile(
                    "(?<![\\w./-])" + Pattern.quote(dangerous) + "(?![\\w-])",
                    Pattern.CASE_INSENSITIVE));
        }
        return patterns;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> calls = input.toolCalls();
        List<ToolUseBlock> rewritten = null;
        for (int i = 0; i < calls.size(); i++) {
            ToolUseBlock call = calls.get(i);
            if (!SHELL_TOOL.equals(call.getName())) {
                continue;
            }
            Object command = call.getInput() == null ? null : call.getInput().get("command");
            String matched = matchDangerous(command instanceof String s ? s : null);
            if (matched == null) {
                continue;
            }
            log.warn("危险命令已拦截 sessionId={} pattern={}",
                    ctx == null ? "-" : ctx.getSessionId(), matched);
            if (rewritten == null) {
                rewritten = new ArrayList<>(calls);
            }
            rewritten.set(i, neutralize(call));
        }
        return next.apply(rewritten == null ? input : new ActingInput(rewritten));
    }

    /** 命中返回匹配的危险模式，否则返回 null */
    private static String matchDangerous(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String lower = command.toLowerCase(Locale.ROOT);
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(lower).find()) {
                return pattern.pattern();
            }
        }
        return null;
    }

    /** 保留 id/name，仅把 command 参数替换为无害提示命令 */
    private static ToolUseBlock neutralize(ToolUseBlock call) {
        Map<String, Object> safeInput = new LinkedHashMap<>(
                call.getInput() == null ? Map.of() : call.getInput());
        safeInput.put("command", BLOCKED_COMMAND);
        safeInput.remove("working_directory");
        return ToolUseBlock.builder()
                .id(call.getId())
                .name(call.getName())
                .input(safeInput)
                .build();
    }
}
