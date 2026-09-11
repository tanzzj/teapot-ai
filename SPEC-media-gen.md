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
  > **已取代**：实测证明模型拿不到产物链接、无法自主完成转存，改为平台侧装饰器代转，见 §4.9。
- 边界：`upload_file` 上限 20MB，覆盖图片与短小视频；超限产物保留 DashScope 临时链接（有效期以百炼侧为准），由 prompt 说明告知用户时效。

### 4.3 前端

- [AgentDetail.tsx](file:///d:/teamer/teapot-ai/teapot-ai-web/src/pages/AgentDetail.tsx)：runtime 区新增 `['runtime','enableMediaGen']` Switch（对齐现有 计划模式 / Shell / OSS 文件 / MCP 配置查询 四项），保存映射同步扩展。
- [AgentConfigPanel.tsx](file:///d:/teamer/teapot-ai/teapot-ai-web/src/chat/AgentConfigPanel.tsx)：只读展示加一个「生图/生视频」标签；`types.ts` 的 `Runtime` 类型加 `enableMediaGen?: boolean`。
- 产物呈现（修订）：`DashScopeMultiModalTool` 工具结果仅含 ImageBlock/VideoBlock（无文本 URL），模型拿不到链接、默认 ToolCall 面板也只当代码展示——原「Markdown 直链」方案不成立。改为 spark design 官方扩展点：[MediaGenCard](file:///d:/teamer/teapot-ai/teapot-ai-web/src/chat/MediaGenCard.tsx) 经 `customToolRenderConfig` 挂载到全部产出媒体块的 6 个 `dashscope_*` 工具（`text_to_image` / `image_to_image` / `text_to_video` / `image_to_video` / `first_and_last_frame_image_to_video` / `text_to_audio`；`image_to_image` 是 fork 后补的第 6 个，曾漏挂导致图生图产物只渲染成 JSON 文本），解析 `tool_call_output.data.output` 中的媒体块 JSON，用 `ImageGenerator`（生成中骨架屏 + 出图预览）/ `DefaultCards.Videos` / `DefaultCards.Audios` 渲染；后端 [SessionMessageConverter.toolResultText](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/service/SessionMessageConverter.java) 同步改为与实时流（`AguiStreamContext.serialize`）同形态序列化，历史回放可渲染。

### 4.4 媒体模态守卫（修订：音频块把会话钉死故障）

- 现象（session `256f2a4b-53b7-4006-a49c-f401035622b3`，agent `digit-tim` / `dashscope:qwen3.8-max`）：语音合成那一轮的音频卡片正常呈现，但此后每轮对话都在推理入口报 `<400> InternalError.Algo.InvalidParameter: An incorrect modal \`audio\` was entered…`，会话永久不可用。
- 成因：`dashscope_text_to_audio` 以 `AudioBlock` 作为工具结果并随会话历史持久化；[DashScopeChatFormatter.doFormat](file:///d:/teamer/agentscope-java/agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/main/java/io/agentscope/extensions/model/dashscope/formatter/DashScopeChatFormatter.java) 只看「消息含不含媒体块」就输出多模态 content part（`{"audio": url}`），不看模型能力位——而 `qwen3.8-max` 的 `capabilities=image,video`，不接受音频输入（`ModelRegistry` 的 `audio` 能力位仅用于前端 gating）。首轮看似成功，是因为音频块当轮才产生、尚未进入历史；音频能被听到则来自前端 `MediaGenCard` 直接渲染工具结果，该轮的收尾 LLM 调用同样已失败。
- 落地：新增 [MediaModalGuardMiddleware](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/agentscope/MediaModalGuardMiddleware.java)，在 `onModelCall` 阶段按模型能力位（`ModelRegistry.capabilities(modelId)`）改写**请求视图**：能力位未声明的 Image/Audio/Video 块（含 `ToolResultBlock.output` 内嵌块，保留 tool_call id/name/state）降级为与框架 `AbstractBaseFormatter#convertToolResultToString` 同措辞的文本引用。持久化历史不动，前端产物卡片与历史回放照常渲染，被污染的旧会话可自愈。
- 装配：[AgentAssembler](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/service/AgentAssembler.java) 无条件挂载（不依赖 `enableMediaGen`），用户上传的媒体块同样受护；纯文本模型（能力位为空）会降级全部媒体块，等价于框架对纯文本模型的既有行为。
- 边界：`DataBlock` 模态语义不明，不降级；需要真正「听懂」音频的 Agent 仍须换具备音频输入能力的 omni 模型（能力位补 `audio`）。

### 4.5 用户附件地址注入（修订：图生图退化为文生图）

- 现象（session `7acef945-70aa-49b7-9433-bafe17f9e283`，agent `digit-tim` / `dashscope:qwen3.8-max`，能力位 `image,video`）：用户上传线稿并要求「按原稿上色」，Agent 调了 `dashscope_text_to_image` 用文字重建构图，而不是 `dashscope_image_to_image`；追问后又翻遍沙箱找附件地址，一无所获。
- 成因（三层）：
  1. 前端 OSS 链路把附件作为 `ImageBlock(URLSource)` 发出（[aguiBridge.ts](file:///d:/teamer/teapot-ai/teapot-ai-web/src/chat/aguiBridge.ts)），模型能力位覆盖 image 时守卫原样透传，`DashScopeMessageConverter` 转成 `{"image": url}` —— 模型拿到的是**像素**，那串 URL 文本从头到尾不在模型可见内容里；
  2. 附件不落沙箱（`find`/`list_files` 全空），沙箱侧 `events/*.jsonl` 记录的 USER 消息也只有纯文本，模型没有任何可引用的地址；
  3. MEMORY.md 里已固化「上传图不进沙箱 → 文生图重绘」这条经验，后续会话第一轮即短路，不再尝试。
- 落地：新增 [UserAttachmentRefMiddleware](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/agentscope/UserAttachmentRefMiddleware.java)，在 `onModelCall` 请求视图里为含 `URLSource` 媒体块的 USER 消息追加一段确定性文本清单（`[附件地址] 1. image: https://…`），使图生图/图生视频工具具备可传入参；[MediaGenToolMiddleware](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/agentscope/MediaGenToolMiddleware.java) 的用法说明同步补一条约定（取清单地址、缺地址先索要链接、不得静默改文生图）。
- 装配：[AgentAssembler](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/service/AgentAssembler.java) 紧接守卫之后挂载（守卫在外，改写后的输入才轮到它，已降级为文本引用的块不会重复注入）；不依赖 `enableMediaGen`，与守卫同为无条件。
- 边界：仅处理 USER 消息，工具产物的媒体块不注入（避免产物链接回流给模型转述）；内联 base64 附件无地址可给，不注入。
- 存量记忆订正：已按要求清洗 `digit-tim` 的「上传图不进沙箱 → 只能文生图重绘」经验，详见 §4.7。规则级断言已清零，仅保留当天台账的事实经过与订正说明作为溯源。

### 4.6 `dashscope_image_to_image` 工具侧缺陷（任何入参都 400）

同一会话里用户把 OSS 直链以文本发回来后，Agent 换了十几种参数组合共 19 次调用，全部 `400 InvalidParameter`，按模型分三类（均与附件地址无关）：

| 传入 model | 百炼报错 | 真正原因 |
|---|---|---|
| `qwen-image`（工具默认）/ 不传 | `messages parameter length invalid` | `qwen-image` 是文生图模型，不接受 image 输入；图像编辑接口要求 `input.messages` **有且只有一条** |
| `qwen-image-edit` | `Input should be 'user': input.messages.0.role` | 工具固定拼了一条 system 消息占住 `messages[0]`（[DashScopeMultiModalTool.java#L305-L327](file:///d:/teamer/agentscope-java/agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/main/java/io/agentscope/extensions/model/dashscope/tool/DashScopeMultiModalTool.java)） |
| `wan2.5-i2i-preview` / `wanx2.1-imageedit` | `url error, please check url` | wan 系列图像编辑走异步任务接口（`image2image/image-synthesis`，入参 `img_url`），不是 `multimodal-generation` 的 messages 协议 |

已落地的修正（[DashScopeMultiModalTool.java](file:///d:/teamer/agentscope-java/agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/main/java/io/agentscope/extensions/model/dashscope/tool/DashScopeMultiModalTool.java)，对齐官方《Qwen-Image-Edit 图像编辑 API》）：

- `input.messages` 只发**单条 user 消息**（`[{image},{text}]`），删掉固定占住 `messages[0]` 的 system 消息——这是三类 400 里最致命的一处；
- 默认模型改为编辑模型 `qwen-image-edit`（新增常量 `DEFAULT_IMAGE_EDIT_MODEL`），纯文生图基座与 wan 系在 `rejectsImageInput()` 里**入口即拒**并返回可读的中文错误指引，不再把无效请求打到百炼换回一句 `url error`；
- `toEditableImageRef()` 取代 `urlToProtocolUrl()`：沙箱内存在的本地路径转 base64 data URL，其余（公网 URL / `oss://`）原样透传，彻底不再产生接口不接受的 `file://`；
- `size` 经 `resolveOutputSize()` 按模型能力位（`supportsOutputSize()`：`qwen-image-edit-plus`/`-max`/`qwen-image-2.0` 系列）选择性传递，不支持的模型丢弃并 `log.warn`，而不是被基座拒收；
- 工具描述与 `@ToolParam` 全量重写：明确「沙箱内路径读不到，必须公网 URL；没有输入图就改用 `dashscope_text_to_image`」，从提示层面断掉「编造一个沙箱路径」的路径。

该修正位于框架侧 `agentscope-extensions-model-dashscope`，需 `mvn install` 进 `.m2` 后由 teapot 后端重新打包生效（已随 v51 发布）。

### 4.7 沙箱 Agent 的记忆落点与清洗取证

清洗 `digit-tim` 记忆时踩到的定位问题，记录以免下次重复排查：

- **沙箱型 Agent 的记忆不在 Redis 叠加层**。非沙箱 Agent 走 `MemoryStoreService` + Redis 键 `teapot:memory:item:agents\0<agentKey>\0users\0<uid>\0<path>`；`digit-tim` 的 `t_agent.feature` 是 e2b 沙箱 + `isolationScope: AGENT` + `persistence: LOCAL_SNAPSHOT`，`MEMORY.md`/`memory/*.md` 活在沙箱工作区里，随会话轮次持久化到服务端快照 tar：`/main/apps/teapot-ai/workspace/sandbox-snapshots/<agentKey>/<sandboxId>.tar`。
  > **已失效（同日 v52 修订）**：上述「记忆留在沙箱、管理接口读不到」已于同日下午被解除——沙箱 Agent 的 `MEMORY.md`/`memory/` 已统一路由到 Redis（命名空间末段 `_agent`），管理接口可直接读与删。成因分析仍成立，新的权威描述见 [SPEC.md §27](file:///d:/teamer/teapot-ai/SPEC.md) （§27 修订与 v52 冒烟）。
- 因此 Redis `KEYS`/`--scan` 查不到、管理接口 `GET /api/agent/<key>/memory-items` 也只返回 admin 命名空间的零星文件 —— 都属正常，别据此判定「该 Agent 没有记忆」。
- `redis-cli` 打印含 `\0` 的键名会在 NUL 处截断，后续 `grep`/`EXISTS`/`TYPE` 全部误判，必须走 Java 侧接口或直接解析快照。
- 快照在**每轮 run 结束后**自动刷新（`ls -l --time-style=+%H:%M:%S` 对比 `date` 可确认新鲜度），所以复核方法是 `tar -xf` 到临时目录后 `grep -rnE`；`*.bak-*` 需排除（清洗会自动留备份，如 `MEMORY.md.bak-20260902`）。
- Agent 自报「特征串零命中」不可信：第一轮清洗只删了四条精确措辞，同义写法（`attached images never enter the sandbox`、`Path 2 (Current fallback)`）仍在。必须按行删除 + 广谱扫描措辞族，并以快照复核为准。

### 4.8 生成模型按能力位指定（AgentConfig 可选生图/生视频/语音型号）

- 需求：`enableMediaGen` 打开后，生图/生视频/语音各自可指定型号；不指定即跟随工具默认模型（存量行为不变）。
- 配置：[AgentFeature.Runtime](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/model/AgentFeature.java) 新增 `MediaModels mediaModels`（六个可空 String：`textToImage` / `imageToImage` / `textToVideo` / `imageToVideo` / `frameToVideo` / `textToAudio`），仍走 `t_agent.feature` JSON，无新增表/列。
- 目录来源（[MediaModelCatalog](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/agentscope/MediaModelCatalog.java)）：**不是**从百炼模型列表接口推导——compatible-mode `/models` 里根本没有 `wanx-v1` / `wan2.6-t2v` / `kf2v` 这类媒体模型；且六个工具分散在四个 endpoint（`text2image`、`multimodal-generation`、`video-generation`、`image2video`），跨 endpoint 传对在售型号也会报 `url error`。候选清单逐项实测得到（提交任务后立即 DELETE 取消，不出图不计费）：

| 能力位 | 工具 | 默认模型 | 实测可选 |
|---|---|---|---|
| 文生图 | `dashscope_text_to_image` | `wanx-v1` | `wanx-v1`、`qwen-image`、`qwen-image-plus`、`wanx2.0-t2i-turbo`、`wan2.2-t2i-flash`、`wan2.2-t2i-plus`、`wan2.5-t2i-preview` |
| 图生图/改图 | `dashscope_image_to_image` | `qwen-image-edit` | `qwen-image-edit`、`-plus`、`-max`、`qwen-image-2.0`、`qwen-image-2.0-pro`（与文生图不同 endpoint，`wanx*` / 纯生成系 `qwen-image*` 拿不到图，被工具侧 `rejectsImageInput()` 本地拦截） |
| 文生视频 | `dashscope_text_to_video` | `wan2.6-t2v` | `wan2.6-t2v`、`wan2.7-t2v`、`wan2.5-t2v-preview` |
| 图生视频 | `dashscope_image_to_video` | `wan2.6-i2v-flash` | `wan2.6-i2v-flash`、`wan2.6-i2v`、`wan2.5-i2v-preview` |
| 首尾帧生视频 | `dashscope_first_and_last_frame_image_to_video` | `wan2.2-kf2v-flash` | `wan2.2-kf2v-flash`、`wanx2.1-kf2v-plus` |
| 语音合成 | `dashscope_text_to_audio` | `qwen3-tts-flash` | `qwen3-tts-flash`、`qwen3-tts-instruct-flash`、`qwen-tts-2025-05-22` |

- 生效方式（**双保险**，[MediaGenToolMiddleware](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/agentscope/MediaGenToolMiddleware.java)）：
  1. `toolUsageDescription()` 追加「已指定的生成模型（必须使用）」段——六个工具的 `model` 入参都是 `required=false`，模型完全可以不传，只靠覆写会让模型按默认模型的效果去描述产物；
  2. `onActing` 按 `工具名 → 指定模型` 强制改写 `model` 入参（`withModel` 保留 tool_call id/name 与其余入参），不依赖模型自觉；改写时 `log.info("生成模型已按 Agent 配置覆写 …")`。
- 清单只作候选，**不作风控**：前端 `AutoComplete` 允许自由输入清单外的在售型号，后端不校验枚举，真传错了由百炼报错兜底；prompt 段会给清单外型号标注「（清单外的自定义模型）」。
- 接口：`GET /api/model/media-models`（[ModelController](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/controller/ModelController.java)）下发 `field/label/tool/defaultModel/models`。RBAC 中 developer 已含 `/api/model/*`，无需改权限配置。
- 前端：[AgentDetail.tsx](file:///d:/teamer/teapot-ai/teapot-ai-web/src/pages/AgentDetail.tsx) 开关展开六个 `Form.Item name={['runtime','mediaModels',field]}`，目录加载失败降级为 warning Alert（仍可手填）；保存时空串/空对象不写入、关开关则整块删除，避免写入冗余键；[AgentConfigPanel.tsx](file:///d:/teamer/teapot-ai/teapot-ai-web/src/chat/AgentConfigPanel.tsx) 只读展示已锁定的能力位。
- ⚠ **上线顺序：后端必须先发**。`AgentFeature` 用裸 `ObjectMapper.convertValue` 解析 feature JSON，未声明属性会抛异常——老后端遇到带 `mediaModels` 的记录会直接装配失败。

### 4.9 生成产物按 Agent 存储载体转存（取代 §4.2）

- 需求：生成产物不只留百炼临时链接（约 24h 失效），要按该 Agent 的 `feature.storage`（SPEC §22.1）转存为长期直链，隔天回看不裂图。
- 落点：新增 [MediaArtifactPersistTool](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/agentscope/MediaArtifactPersistTool.java)（`extends ToolBase`）作为装饰器，由 [AgentAssembler.applyMediaArtifactPersist](file:///d:/teamer/teapot-ai/teapot-ai-server/teapot-ai-core/src/main/java/com/teamer/teapot/ai/core/service/AgentAssembler.java) 在 `enableMediaGen` 开启、**且工具注册完成之后**，按名取回已注册工具再同名覆盖（`ToolRegistry` 内部是 `map.put`，同名覆盖合法）。`callAsync` → `delegate.callAsync(...).publishOn(boundedElastic).map(this::persistArtifacts)`。
- 为什么不做在中间件层：`MiddlewareBase` 只有 onAgent/onReasoning/onActing/onModelCall/onSystemPrompt 五个钩子，**没有工具结果后处理钩子**；`onActing` 拿到的是事件流，工具结果按 delta 分片在其中，改写脆弱。
- 为什么不用子类覆写：`Toolkit.registerTool(Object)` 只扫 `getDeclaredMethods()`（不扫父类），继承 `DashScopeMultiModalTool` 想包一层是注册不出来的。
- 行为边界：
  - 仅 `storageRouter.effectiveStrategy(agentKey) == "oss"` 才下载并替换 URL；**base64 载体保持临时链接**——`store()` 在 base64 下返回 data URL，几十 MB 产物内联进消息体会直接撑爆上下文；
  - 下载走 JDK `HttpClient`（followRedirects、connect 15s、read 120s），体积上限 30MB（对齐 `ChatVideoController`），超限/非 2xx/任何异常一律保留原链并 `log.warn` 一次——生成结果不该被转存失败拖掉；
  - `mediaTypeOf()` 以响应 `Content-Type` 为准，缺失时按块类型兜底；rebuild 时保留 `VideoBlock` 的 `fps`/`maxFrames`/`pixels` 等原有属性；
  - 日志 `trim()` 去 query，避免把签名参数打进日志。
- 代价：转存是同步追加在工具返回之前的——图片约 +1–3s，视频按体积线性增加；只在 oss 载体下发生。

## 5. 测试计划与边界假设

### 测试计划

1. 编译与装配：`enableMediaGen` 开/关两态下 `AgentAssembler` 构建通过，开关关闭时工具列表不含 `dashscope_*`。
2. 缺省行为：`DASHSCOPE_API_KEY` 为空时开关打开也不挂载，日志可见提示，不影响其他中间件。
3. 串联验证：生图（文生图 → `upload_file` 转存 → 会话内可见图片）；图生视频（以生成图为参考 → 出片 → 转存或直接临时链接可见）。
4. 前端回归：开关保存回显、`AgentConfigPanel` 标签展示、未开能力 Agent 无工具泄漏。
5. 模态守卫：能力位不含 `audio` 的 Agent，历史含音频块时后续轮次能正常回复，日志可见「媒体模态已降级」；音频产物卡片仍可在历史回放中渲染。
6. **附件地址注入 + 图生图**（✅ 已验证，v51 / session `i2ifix-1788322103`）：以真实 OSS 图片直链发起「按原图上色」，日志依次出现 `用户附件地址已注入 sessionId=… 附件=[1. image: https://teamer.oss…jpg]` → `POST_REASONING | tool_call name=dashscope_image_to_image` → `POST_ACTING … state=SUCCESS`，SSE 全流零 `InvalidParameter`，产出真实产物直链 `https://dashscope-7c2c.oss-cn-shanghai.aliyuncs.com/…-1.png`。模型在记忆尚未清洗完成的情况下就已走真图生图，说明 §4.5 机制本身足以纠偏。
7. **模型锁定生效**：给 Agent 指定 `textToImage=wan2.2-t2i-flash` 后发一轮生图，日志可见 `生成模型已按 Agent 配置覆写`，system prompt 含「已指定的生成模型」段；前端保存后回显正确，清空并保存不残留空键。
8. **产物转存**：`feature.storage` 配 OSS 记录的 Agent 生图后，历史里的 `ImageBlock` URL 为 `*.aliyuncs.com`（自建桶域名）而非 `dashscope-*.oss-cn-shanghai.aliyuncs.com`；未配 OSS（base64 载体）时保持临时链接且日志无转存记录。

### 边界假设

- 视频生成为分钟级同步阻塞（`VideoSynthesis.call` 阻塞至完成），期间该轮工具调用挂起；一期接受，不做异步任务表与进度推送。
- 一期不做多 provider：不复制 QwenPaw 的能力矩阵与协议分层；默认模型以 `DashScopeMultiModalTool` 内置为准，模型参数可由对话指定，也可由 Agent 配置固定（§4.8，配置优先级高于对话）。
- 转存不做重试队列：单次同步转存，失败即回退临时直链并留 warn，不引入重试管线与异步任务表（§4.9）。
- 产物时效与内容安全以百炼平台侧策略为准；`text_to_audio` / `audio_to_text` 顺带可用，但不在本规格验收范围（实际已暴露给模型，其模态风险由 §4.4 守卫兜底）。
