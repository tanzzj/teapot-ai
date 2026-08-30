# Teapot 会话消息级落盘（Checkpoint）规格

> 方向：把 AgentScope 会话状态的落盘时机从「每轮对话结束统一落盘」细化为「每条消息落盘」（checkpoint），解决一轮对话进行中刷新页面 / 进程重启导致本轮消息全部丢失的问题。
> 调研对象：AgentScope Java `agentscope-core` 的持久化链路（`ReActAgent` / `MiddlewareBase` / `GracefulShutdownMiddleware`），全部基于 2.0.1 现有源码验证，零新增依赖。

## 1. 背景与目标

- 现状：`ReActAgent` 仅在三个时机调用私有 `saveStateToSession()` 落盘整份 `AgentState`：
  1. `doCall` 整轮完成后（`doCallInner(msgs).flatMap(result -> saveStateToSession(scope)...)`）；
  2. 调用失败时（`saveStateAfterCallFailure`，保留已累积的安全消息）；
  3. 用户中断时（`handleInterrupt` 先补齐悬空 tool_use 再落盘）。
- 问题：执行期间消息只累积在内存 `AgentState.contextMutable()`。一轮对话进行中（推理/工具调用期间）进程重启，或刷新后请求被取消且落盘未及执行，本轮所有消息（含用户输入）丢失。
- 目标：每条消息产生后即落盘——用户消息、助手消息、每条工具结果各自至少落盘一次；刷新/重启最多丢「正在流式生成中的半条消息」。
- 非目标：增量/追加式存储（AgentStateStore 为单槽全量快照模型，不改其协议）；流式 chunk 级落盘；历史数据迁移。

## 2. 调研结论：框架提供的 checkpoint 积木

### 2.1 官方 checkpoint 范式先例

`io.agentscope.core.shutdown.GracefulShutdownMiddleware` 的 javadoc 明确了框架认可的 checkpoint 点位：

- `onReasoning (doOnComplete) — checkpoint after reasoning`
- `onActing (doOnComplete) — checkpoint after acting`

该中间件仅在进程进入 SHUTTING_DOWN 状态时利用这些点位中断并落盘，但点位本身即「每条消息刚进 context」的时刻，可常态化复用。

### 2.2 公开强制落盘 API

- `ReActAgent.saveAgentState(RuntimeContext ctx)` / `saveAgentState(String userId, String sessionId)` 为 **public**：内部走 `persistAgentStateCas()`，与正常落盘共享 `slotVersions` 版本缓存，CAS 语义一致；未配置 store 或槽位从未加载时为 no-op。
- 中间件收到的 `agent` 参数即内部 `ReActAgent`（`MiddlewareChain.build` 各挂点均传 `ReActAgent.this`），可直接调 `saveAgentState(ctx)`，无需经 `HarnessAgent.getDelegate()`；`RuntimeContext` 暴露 `getSessionId()` / `getUserId()` / `getAgentState()`。
- 并发安全：HarnessAgent 对同一 session 的调用本就串行，中间件触发的 `saveAgentState` 与轮末 `saveStateToSession` 共用版本缓存，无 CAS 冲突。

### 2.3 消息入 context 的时序（决定 checkpoint 点位，已逐行核实）

`doCallInner()` 在进入 `coreAgent()`（推理/行动主循环）前即 `addToContext(msgs)` 追加用户消息。主循环每次迭代：`reasoning(iter)` →（如有工具）`acting(iter)` → `executeIteration(iter+1)`，两者各自重新构建 `MiddlewareChain`（`MiddlewareChain.build(middlewares, ReActAgent.this, rc, ...)`），即 `onReasoning`/`onActing` **每次迭代都会触发**。

关键时序修正：**助手消息与工具结果都在中间件包裹的流完成之后才入 context**，`doOnComplete` 挂不到正确时刻：
- 助手消息：推理流完成后在 `stream.then(...)` 里 `context.buildFinalMessage()` 再 `state.contextMutable().add(finalMsg)`（正常路径在 `runPostReasoningPipeline` 的 `firePostReasoning` 后入栈）——位于 `onReasoning` 中间件流完成之后；
- 工具结果：`onActing` 流完成后，`acting()` 的 `flatMap(results -> ...)` 里逐条 `notifyPostActingHook` 才入栈。

因此 checkpoint 只能挂在**阶段入口**（此时上一阶段产物已全部入 context）：

| checkpoint 点位 | 此刻 context 中已有而可能未落盘的内容 |
|---|---|
| `onReasoning` 入口（首次迭代） | 用户消息 |
| `onReasoning` 入口（第 2+ 次迭代） | 上一迭代的全部工具结果 |
| `onActing` 入口 | 本轮助手消息（含 tool_use） |

语义：**每条消息在产生的下一个阶段入口落盘，最多滞后一个阶段**；轮末既有落盘兜底最后一条回复。任意时刻重启，最多丢「当前正在进行的阶段」的内容（流式半条助手消息 / 正在执行的工具结果）。

**轮末既有 `saveStateToSession` 保留不动（叠加而非替换）**：
- 机制上无关闭配置：轮末落盘是 `ReActAgent.doCall` 内嵌的私有收尾逻辑，框架不提供开关；唯一使其失效的方式是不挂 `stateStore`，而 teapot-ai 历史回放（`ChatSessionService`）依赖 `stateStore` 读取，不可拆。
- 语义上不可省略：轮末落盘先执行 `syncToolkitToState()`（把 toolkit activeGroups 同步进 state）再写库，而公开 `saveAgentState()` 不含该同步——去掉轮末落盘，工具组/技能组状态将永不持久化；且轮末还兜底最终态（压缩、权限上下文等末位变更）。
- 共存的 CAS 版本交互（已确认，无害但有噪音）：两后端均 `supportsVersioning()=true`。checkpoint 走公开 `saveAgentState`，期望版本取 `slotVersions` 缓存且每次写后回填，checkpoint 之间不冲突；但轮末 `saveStateToSession` 用的 `scope.loadedVersion` 是调用开始时的快照，仅自身写入会更新，无私有入口可同步——故本轮有过 checkpoint 后，轮末 `saveIfVersion(expected=旧版本)` 必然失败一次，进入默认 `ConflictPolicy.OVERWRITE`：无条件覆写，**数据正确**（回写同一份内存 `AgentState`，含 `syncToolkitToState`），代价是每轮一次 `CAS conflict — OVERWRITE applied` warn、一次失败 CAS 往返、`stateConflictCount` 指标失真。此伪冲突无法从外部避免（框架未暴露版本同步入口），监控上不得把 `stateConflictCount` 增长当真实并发异常；teapot 用进程内 turn gate，维持默认 OVERWRITE（FAIL 仅适用分布式 gate 场景）。

## 3. 落地设计（teapot-ai）

### 3.1 后端

- 新建 `teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/agentscope/PerMessageCheckpointMiddleware.java`，实现 `io.agentscope.core.middleware.MiddlewareBase`：
  - `onReasoning` 与 `onActing` 均在 `next.apply(input)` **之前** `checkpoint(agent, ctx)`（阶段入口落盘，见 §2.3 点位表）；不用 `doOnComplete`（已证实早于消息入 context）；
  - `checkpoint()`：中间件收到的 `agent` 参数是**内部 `ReActAgent`**（`MiddlewareChain.build` 各处传 `ReActAgent.this`，非 HarnessAgent），故 `agent instanceof ReActAgent ra` 时直接 `ra.saveAgentState(ctx)`；整体 `try/catch` 吞异常仅 `log.warn`——落盘失败绝不打断对话流（与 `saveStateAfterCallFailure` 的容错哲学一致）。
- 装配：[AgentAssembler.assemble()](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/service/AgentAssembler.java) 在 `builder.build()` 前 `builder.middleware(checkpointMiddleware)`（与其他中间件同位置）。Web 链路（AgentRegistry 每轮重建）与 channel 链路（ChannelHub 长驻）经同一装配点自动同时生效，无需 `extraMiddlewares` 传参。

### 3.2 开关

- [TeapotAiProperties](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/config/TeapotAiProperties.java) 的 `agentscope` 命名空间下新增 `checkpoint-per-message`（boolean，默认 `true`）。
- 按全局配置而非按 Agent 的 `AgentFeature.Runtime` 开关：消息持久化是平台级可靠性策略，不是 Agent 能力；关闭仅用于压测对比。

### 3.3 读取链路

- 无需变更：`ChatSessionService.mergeSlotContexts()` 读取的仍是 `agent_state` 单槽，checkpoint 与轮末落盘写同一槽位，历史回放天然一致。
- 双槽（anonymous/用户）兜底逻辑不受影响：checkpoint 使用本次调用的 `RuntimeContext` 槽位，写谁读谁。

## 4. 测试计划与边界假设

### 测试计划

1. 编译与装配：`checkpoint-per-message` 开/关两态下 `AgentAssembler` 构建通过；关闭时中间件缺席，行为回落现状。
2. 落盘点位验证（日志级）：一轮含工具调用的对话，观察到阶段入口落盘 ≥3 次（首推理入口落用户消息 / acting 入口落助手消息 / 次轮推理入口落工具结果），轮末另有一次。
3. 中断恢复串联：
   - 对话进行中重启服务（`systemctl restart teapot-ai`），重新进入会话，已产生的用户消息与已完成阶段的助手/工具消息可见；
   - 刷新页面后重开会话，历史不丢。
4. 回归：普通多轮对话、计划模式、channel 链路（Discord）消息历史读取无异常；`getStateConflictCount()` 预期每轮增长约 1（checkpoint 引发的伪冲突，走 OVERWRITE 收敛），重点验证无 `ConcurrentSessionModificationException`、日志中 OVERWRITE warn 后无后续异常。

### 边界假设

- 全量快照写放大：每次落盘序列化整份 `AgentState`（单槽 `agent_state`），长会话为 O(n) 每消息；当前会话规模下 Redis/MySQL 均可承受。不做增量协议改造，不做流式 chunk 落盘。
- 「正在进行的阶段」内容仍可能丢失：消息落盘滞后一个阶段（§2.3），流式中的半条助手消息、执行中的工具结果不保；推理中断走既有 `handleInterrupt` 落盘；直接杀进程属于残余风险。
- 刷新（SSE 断连）现状已有部分覆盖：teapot 走 MVC 链路（`spring-boot-starter-web`），AG-UI starter 的 `agentscope.agui.interrupt-on-disconnect` 默认 `true` 且 teapot 未覆盖——断连触发 `emitter.onError/onTimeout` → `reActAgent.interrupt(ctx)` → `handleInterrupt` 落盘。但 Tomcat 感知断连依赖下一次写入，长工具执行（分钟级生视频）期间中断标记只能在下一个 `checkInterrupted` 点位生效；且若配置改为 `false`，agent 继续跑完但事件流已取消，轮末 `flatMap` 的落盘随取消信号丢失。checkpoint 方案不依赖该行为，两场景都覆盖。
- 落盘为同步调用（`persistAgentStateCas`），在阶段入口 `next.apply(input)` 之前同步执行；单条写库/写 Redis 延迟毫秒级，对首字延迟影响可忽略（每阶段入口一次，非每 chunk）。如后续观测到压力，可改为 `subscribeOn(boundedElastic)` 异步（接受极端时序下的乱序风险），一期不做。
- 不迁移历史会话：仅对新产生消息生效，旧数据不受影响也无需回填。
