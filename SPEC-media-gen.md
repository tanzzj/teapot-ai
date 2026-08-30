# Teapot 生图/生视频能力落地规格（一期：DashScope）

> 方向：为 teapot-ai 平台的 Agent 增加生图/生视频能力，按 Agent 开关挂载，复用 AgentScope Java 原生工具。
> 调研对象：QwenPaw creator 插件（成熟实现参照）与 AgentScope Java `agentscope-extensions-model-dashscope`（关键落地依据）。

## 1. 背景与目标

- teapot-ai 现有 Agent 能力面（计划模式 / Shell / OSS 文件 / MCP 配置查询）均通过「工具提供型中间件」按开关挂载，缺少媒体生成这一高频诉求。
- 目标：Agent 可应对话请求生成图片与视频，产物以长期可访问的直链回到会话中可视化呈现。
- 一期范围：仅接 DashScope 一家后端；能力按 Agent 级开关启停；不引入新依赖、不改数据库结构。
- 非目标（一期不做）：多 provider 抽象、任务持久化与断点续跑、产物强制转存、独立产物卡片组件。

## 2. 调研结论一：QwenPaw 实现机制

QwenPaw creator 插件（`D:\teamer\QwenPaw\plugins\apps\qwenpaw-creator\backend\`）是当前最完整的参照实现：

### 2.1 Provider 策略分层

- 生图：[models/image/__init__.py](file:///d:/teamer/QwenPaw/plugins/apps/qwenpaw-creator/backend/models/image/__init__.py) 注册 6 家后端（OPENAI / DASHSCOPE / GEMINI / ARK / BFL / IDEOGRAM），`get_image_backend()` 按「工具配置 > 环境变量 > 持久化配置 > 默认」选择。
- 生视频：`video_backends/` 下 kling / minimax / veo / vidu 四个协议模块，加上 `video_model.py` 内联的 wan / happyhorse / seedance2，能力矩阵共覆盖 7 个家族；每家统一暴露 `build_submit_request()` / `extract_task_id()` / `check_status()`。

### 2.2 DashScope 异步任务协议

- 生图：[dashscope_provider.py](file:///d:/teamer/QwenPaw/plugins/apps/qwenpaw-creator/backend/models/image/dashscope_provider.py) 以 `X-DashScope-Async: enable`（+ `X-DashScope-OssResourceResolve: enable`）提交，取 `output.task_id` 后每 5 秒轮询 `{api_root}/tasks/{task_id}`，终态 `SUCCEEDED / FAILED / CANCELED / UNKNOWN`；账号不支持异步（403）时降级同步。
- 生视频：[video_model.py](file:///d:/teamer/QwenPaw/plugins/apps/qwenpaw-creator/backend/models/video_model.py) 的 `submit_video_task()`（模式 t2v / i2v / r2v / video_edit，带 429 退避重试）与 `check_task_status()`，同样是「提交取 task_id → 轮询」协议。

### 2.3 工程化配套

- 能力矩阵前置校验：`models/video_capabilities.py` 按模型家族维护参考素材上限、分辨率、时长窗口等契约，未知模型 fail-closed，提交前拦截避免无效计费。
- 参考素材传输：`models/media_transport.py` 通过百炼 `getPolicy` 接口把参考图/视频上传到 DashScope 临时存储（`oss://` 链接，48h TTL、≤1GB），部分后端走 Base64 内联（30MB 上限）。
- 可恢复任务监督者：`services/media_files/r2v_execution.py` 的 `FileR2VExecutionService`，持久化任务状态 + 租约心跳（`poll_lease_*`），阶段推进 `ADMITTED → SUBMIT_CLAIMED → POLLING → PROVIDER_SUCCEEDED → PUBLISHED`，进程重启不丢任务、且无 provider task_id 的租约绝不重复提交。

### 2.4 可借鉴点

- 产物转存自家存储，把临时直链换成长期直链（QwenPaw 有 Creator OSS 兜底）。
- 能力按开关挂载、能力矩阵前置校验的思路（一期仅借鉴开关挂载）。
- 租约式可恢复监督者暂不借鉴：teapot 一期走同步阻塞调用，复杂度不匹配。

## 3. 调研结论二：AgentScope Java 原生支持（关键依据）

- `agentscope-extensions-model-dashscope` 自带 [DashScopeMultiModalTool](file:///d:/teamer/agentscope-java/agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/main/java/io/agentscope/extensions/model/dashscope/tool/DashScopeMultiModalTool.java)，构造函数仅收 `apiKey`，共 8 个 `@Tool`：

| 工具 | 默认模型 |
|---|---|
| `dashscope_text_to_image` | wanx-v1 |
| `dashscope_text_to_video` | wan2.6-t2v |
| `dashscope_image_to_video` | wan2.6-i2v-flash |
| `dashscope_first_and_last_frame_image_to_video` | wan2.2-kf2v-flash |
| `dashscope_image_to_text` | qwen3-vl-plus |
| `dashscope_video_to_text` | qwen3.5-plus |
| `dashscope_text_to_audio` | qwen3-tts-flash |
| `dashscope_audio_to_text` | paraformer-realtime-v2 |

- 工具内部委托 `dashscope-sdk-java` 的同步阻塞调用（如 `VideoSynthesis.call`，阻塞直至出片或失败），包在 `Mono.fromCallable` 里；异步「提交 + 轮询」由 SDK 封装，上层无需自管。
- 已验证：2.0.1 发布包 `agentscope-extensions-model-dashscope-2.0.1.jar` 内含 `DashScopeMultiModalTool.class`；`teapot-ai-server/teapot-ai-core/pom.xml` 已依赖该模块（BOM 统一 `agentscope.version=2.0.1`），`com.alibaba:dashscope-sdk-java`（2.22.9）为其 compile 级传递依赖——**零新增依赖**。

## 4. 落地设计（teapot-ai）

### 4.1 后端

- 新建 `MediaGenToolMiddleware`：与 [OssToolMiddleware](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/agentscope/OssToolMiddleware.java) / `McpConfigToolMiddleware` 同款，实现 [ToolProvidedMiddleware](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/agentscope/ToolProvidedMiddleware.java)：
  - `providedTools()` 返回 `new DashScopeMultiModalTool(apiKey)`（一期仅暴露生图/生视频相关的 4 个工具即可，其余由模型自选或全量暴露，实现时定）；
  - `toolUsageDescription()` 注入用法说明：模型选择建议、以及「生成后调用 `upload_file` 转存为长期直链，再把直链以 Markdown 输出」的产物处理指引。
- 开关：[AgentFeature.Runtime](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/model/AgentFeature.java) 增加 `Boolean enableMediaGen`（null/关 = 不挂载），随现有 `t_agent.feature` JSON 持久化（`sql/V4__agent_feature.sql`），无新增表/列。
- 装配：[AgentAssembler.buildToolMiddlewares()](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/service/AgentAssembler.java)（234–246 行）加第三个分支：`rt.getEnableMediaGen()==TRUE` 且 `DASHSCOPE_API_KEY` 非空时挂载；注册沿用现有模式——`builder.middleware(...)` 在 `build()` 前（保证 system prompt 注入），`build()` 后 `agent.getToolkit().registerTool(middleware.providedTools())`。
- 密钥：复用现有 `DASHSCOPE_API_KEY`（`@Value` 环境变量，`ModelRegistry` / `ModelService` 已在用），不落库；缺省时该能力不挂载并在装配日志提示。

### 4.2 产物持久化

- 不自建转存管线：prompt 用法说明引导 Agent 在生成后调用现有 [OssFileTools](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/agentscope/OssFileTools.java) 的 `download_file`（拉取临时产物）+ `upload_file`（public-read、`max-age=31536000`、UUID 不可猜测 key），得到长期直链。
- 边界：`upload_file` 上限 20MB，覆盖图片与短小视频；超限产物保留 DashScope 临时链接（有效期以百炼侧为准），由 prompt 说明告知用户时效。

### 4.3 前端

- [AgentDetail.tsx](file:///d:/teamer/teapot-ai/teapot-ai-web/src/pages/AgentDetail.tsx)：runtime 区新增 `['runtime','enableMediaGen']` Switch（对齐现有 计划模式 / Shell / OSS 文件 / MCP 配置查询 四项），保存映射同步扩展。
- [AgentConfigPanel.tsx](file:///d:/teamer/teapot-ai/teapot-ai-web/src/chat/AgentConfigPanel.tsx)：只读展示加一个「生图/生视频」标签；`types.ts` 的 `Runtime` 类型加 `enableMediaGen?: boolean`。
- 产物呈现（修订）：`DashScopeMultiModalTool` 工具结果仅含 ImageBlock/VideoBlock（无文本 URL），模型拿不到链接、默认 ToolCall 面板也只当代码展示——原「Markdown 直链」方案不成立。改为 spark design 官方扩展点：[MediaGenCard](file:///d:/teamer/teapot-ai/teapot-ai-web/src/chat/MediaGenCard.tsx) 经 `customToolRenderConfig` 挂载到 5 个 `dashscope_*` 工具，解析 `tool_call_output.data.output` 中的媒体块 JSON，用 `ImageGenerator`（生成中骨架屏 + 出图预览）/ `DefaultCards.Videos` / `DefaultCards.Audios` 渲染；后端 [SessionMessageConverter.toolResultText](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/service/SessionMessageConverter.java) 同步改为与实时流（`AguiStreamContext.serialize`）同形态序列化，历史回放可渲染。

## 5. 测试计划与边界假设

### 测试计划

1. 编译与装配：`enableMediaGen` 开/关两态下 `AgentAssembler` 构建通过，开关关闭时工具列表不含 `dashscope_*`。
2. 缺省行为：`DASHSCOPE_API_KEY` 为空时开关打开也不挂载，日志可见提示，不影响其他中间件。
3. 串联验证：生图（文生图 → `upload_file` 转存 → 会话内可见图片）；图生视频（以生成图为参考 → 出片 → 转存或直接临时链接可见）。
4. 前端回归：开关保存回显、`AgentConfigPanel` 标签展示、未开能力 Agent 无工具泄漏。

### 边界假设

- 视频生成为分钟级同步阻塞（`VideoSynthesis.call` 阻塞至完成），期间该轮工具调用挂起；一期接受，不做异步任务表与进度推送。
- 一期不做多 provider：不复制 QwenPaw 的能力矩阵与协议分层；默认模型以 `DashScopeMultiModalTool` 内置为准，模型参数可由对话指定。
- 不做强制转存：转存依赖 prompt 引导，失败时回退临时直链，不引入重试管线。
- 产物时效与内容安全以百炼平台侧策略为准；`text_to_audio` / `audio_to_text` 顺带可用，但不在本规格验收范围。
