package com.teamer.teapot.ai.core.service;

/**
 * 请求级 Agent 装配提示（SPEC §25 修订：chat 界面记忆/计划模式开关的参数传递）。
 * AG-UI 链路的整个请求（上下文解析 → agent 装配 → run）在同一线程池线程内完成
 * （AguiMvcController.handleInternal 的 executorService.submit 单 Runnable），
 * 因此 TeapotRuntimeContextResolver 从 forwardedProps 解析的开关可以安全地
 * 通过 ThreadLocal 传递给同线程内的 AgentAssembler 装配。
 * 缺失（未传参）= null，装配回落 Agent 配置。
 */
public final class AgentRuntimeHints {

    /** 本次请求的记忆模式覆盖：TRUE 强制开启 / FALSE 强制关闭 / null 跟随 Agent 配置 */
    private static final ThreadLocal<Boolean> MEMORY_MODE = new ThreadLocal<>();
    /** 本次请求的计划模式覆盖：TRUE 强制开启 / FALSE 强制关闭 / null 跟随 Agent 配置 */
    private static final ThreadLocal<Boolean> PLAN_MODE = new ThreadLocal<>();

    private AgentRuntimeHints() {
    }

    public static void setMemoryMode(Boolean enabled) {
        if (enabled == null) {
            MEMORY_MODE.remove();
        } else {
            MEMORY_MODE.set(enabled);
        }
    }

    public static Boolean getMemoryMode() {
        return MEMORY_MODE.get();
    }

    public static void setPlanMode(Boolean enabled) {
        if (enabled == null) {
            PLAN_MODE.remove();
        } else {
            PLAN_MODE.set(enabled);
        }
    }

    public static Boolean getPlanMode() {
        return PLAN_MODE.get();
    }

    /** 请求结束时清理（线程池线程复用，防脏读） */
    public static void clear() {
        MEMORY_MODE.remove();
        PLAN_MODE.remove();
    }
}
