package com.teamer.teapot.ai.core.tool;

import com.teamer.teapot.ai.core.mcp.McpConfigTools;
import com.teamer.teapot.ai.core.model.AgentFeature;
import io.agentscope.core.tool.Tool;
import io.agentscope.extensions.a2ui.envelope.A2uiConstants;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.tool.ArtifactDeliveryTool;
import io.agentscope.harness.agent.tool.FilesystemTool;
import io.agentscope.harness.agent.tool.MemoryGetTool;
import io.agentscope.harness.agent.tool.MemorySaveTool;
import io.agentscope.harness.agent.tool.MemorySearchTool;
import io.agentscope.harness.agent.tool.PlanModeTools;
import io.agentscope.harness.agent.tool.SessionSearchTool;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import io.agentscope.harness.agent.tool.TaskTool;
import io.agentscope.harness.agent.tool.WaitAsyncResultsTool;
import io.agentscope.harness.agent.tool.WebTools;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可分组工具目录（SPEC §31）：把「这个 Agent 手上可能有哪些工具」列成一张能下发给配置页的清单，
 * 用户据此把工具拖进分组。
 *
 * <p>刻意不去构建一个真 Agent 再读它的 Toolkit：装配会连 MCP（stdio 传输要拉起子进程）、
 * 可能创建沙箱，配置页不该付这个代价，而且新建态的 Agent 还没落库、根本没法装配。
 * 改成反射扫 {@code @Tool} 注解（RUNTIME 保留，注解里的 name 就是注册名，description 就是模型看到的那句）
 * + 少量无注解实现按公开常量补录。类引用是编译期检查的 —— SDK 改类名会编译失败，
 * 不像硬编码工具名字符串那样静默过时。
 *
 * <p>清单只作候选、不作校验：分组里写了未注册的名字，框架按名匹配不上就静默忽略（不报错也不生效），
 * 所以前端另留自由录入，用来覆盖 MCP 工具（工具名由 MCP 服务端自报、本平台不加前缀，只有连上才知道叫什么）。
 *
 * <p>{@link AgentFeature#ungroupableToolNames()} 里的两个工具不出现在清单里：它们必须始终对模型可见，
 * 入组即隐藏，藏了 meta tool 等于分组再也打不开。
 */
public final class ToolCatalog {

    /** 目录来源：harness=框架自带 / platform=本平台扩展 / media=生成类工具 */
    public static final String SOURCE_HARNESS = "harness";
    public static final String SOURCE_PLATFORM = "platform";
    public static final String SOURCE_MEDIA = "media";

    /** 单条目录项；description 与工具向模型自报的那句同源（无注解的除外，见 {@link #EXTRA}） */
    public record Entry(String name, String description, String source) {
    }

    /** 框架自带的注解式工具类（含内部类里的 @Tool 方法，如 WebTools 的两个） */
    private static final List<Class<?>> HARNESS_CLASSES = List.of(
            FilesystemTool.class,
            ShellExecuteTool.class,
            MemorySearchTool.class,
            MemoryGetTool.class,
            MemorySaveTool.class,
            SessionSearchTool.class,
            WebTools.class,
            WaitAsyncResultsTool.class,
            AgentSpawnTool.class,
            TaskTool.class,
            ArtifactDeliveryTool.class);

    /** 本平台自己注册的注解式工具类（按 runtime 开关挂载，见 AgentBuilder#buildToolMiddlewares） */
    private static final List<Class<?>> PLATFORM_CLASSES = List.of(
            OssFileTools.class,
            McpConfigTools.class);

    /** 生成类工具（DashScope 扩展，一个对象上挂六个 @Tool 方法） */
    private static final List<Class<?>> MEDIA_CLASSES = List.of(
            DashScopeMultiModalTool.class);

    /**
     * 无 {@code @Tool} 注解的工具：AgentTool / ToolBase 实现的名字藏在构造期，不实例化读不到，
     * 故取公开常量补录，说明按各自实现的口径概括一句（这类工具默认都不挂载，列出来只为可分组）。
     */
    private static final List<Entry> EXTRA = List.of(
            new Entry(PlanModeTools.PLAN_ENTER,
                    "Enter PLAN mode: a read-only phase for investigating and designing before making changes.",
                    SOURCE_HARNESS),
            new Entry(PlanModeTools.PLAN_WRITE,
                    "Create or overwrite the current plan as a markdown document (complete content, replaces the file).",
                    SOURCE_HARNESS),
            new Entry(PlanModeTools.PLAN_EXIT,
                    "Finish planning and request permission to start executing the plan; pauses for user approval.",
                    SOURCE_HARNESS),
            new Entry(A2uiConstants.TOOL_RENDER,
                    "Submit an A2UI component tree per the component catalog; a successful call delivers the UI to the user.",
                    SOURCE_PLATFORM),
            new Entry(A2uiConstants.TOOL_ASK_USER_QUESTION,
                    "Ask the user one or more structured questions and wait for the answer (rendered as a form or a question card).",
                    SOURCE_PLATFORM));

    private static final List<Entry> ENTRIES = build();

    private ToolCatalog() {
    }

    /** 全量目录（供 /api/agent/tool-catalog 下发前端选择器），按来源再按工具名排序 */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    private static List<Entry> build() {
        Map<String, Entry> byName = new LinkedHashMap<>();
        scan(HARNESS_CLASSES, SOURCE_HARNESS, byName);
        scan(PLATFORM_CLASSES, SOURCE_PLATFORM, byName);
        scan(MEDIA_CLASSES, SOURCE_MEDIA, byName);
        for (Entry entry : EXTRA) {
            byName.putIfAbsent(entry.name(), entry);
        }
        // 必须常显的工具不进清单：界面不给候选，用户也就不会拖出一个保存时必被拒的组
        AgentFeature.ungroupableToolNames().forEach(byName::remove);
        List<Entry> list = new ArrayList<>(byName.values());
        list.sort(Comparator.comparing(Entry::source).thenComparing(Entry::name));
        return List.copyOf(list);
    }

    /** 扫一批类（含其成员内部类）上的 @Tool 方法；同名以先扫到的为准 */
    private static void scan(List<Class<?>> classes, String source, Map<String, Entry> out) {
        for (Class<?> type : classes) {
            collect(type, source, out);
            for (Class<?> inner : type.getDeclaredClasses()) {
                collect(inner, source, out);
            }
        }
    }

    private static void collect(Class<?> type, String source, Map<String, Entry> out) {
        for (Method method : type.getDeclaredMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool == null) {
                continue;
            }
            // 注解 name 留空时框架取方法名，这里跟同一口径
            String name = tool.name().isBlank() ? method.getName() : tool.name();
            out.putIfAbsent(name, new Entry(name, tool.description(), source));
        }
    }
}
