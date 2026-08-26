# Teapot AI × QwenAudio 语音接入适配规格（ACP 适配器，方向 B）

> 状态：草案 · 调研基线：qwen-audio-agent v1.11 / Gateway 契约 2.0.0 · AgentScope 2.0.1
> 落点说明：独立文档；后续如需并入 SPEC.md §25 另行处理

## 1. 背景与调研结论

### 1.1 QwenAudio（qwen-audio-agent）是什么

本地单机实时语音运行时（Node.js，Apache-2.0），三层结构：

```
WebUI / TUI / 桌面悬浮球
    ↓ WebSocket / HTTP
Realtime Gateway（本地，唯一核心服务，持有 DashScope Key）
    ↓ spawn_thinking → Work 队列
后端 Agent（经 ACP 协议接入的编码类 Agent）
```

- 语音前端：DashScope Qwen-Audio 3.0 Realtime / Qwen3.5-Omni Realtime（全双工、可打断）；备选本地 speech-to-speech 方案
- 后端 Agent：OpenCode / Qoder / Kimi Code / Claude Code / Pi 等，统一经 **ACP（Agent Client Protocol）** 接入
- 核心体验：语音前台即时应答；需要工具/长任务的请求经 `spawn_thinking` 委派后端，任务完成后结果自然回到语音对话

### 1.2 ACP 协议要点（结合方式的唯一官方通道）

- Zed 主导的开放标准，JSON-RPC 2.0 over **stdio**（NDJSON，每行一条），语言无关
- Gateway 将后端 Agent 作为**用户本机子进程**拉起，读写其 stdin/stdout
- 最小实现面：`initialize`、`session/new`、`session/prompt`、`session/cancel`、`session/update`（流式通知）
- 先例：Pi 非原生 ACP，通过外部 `pi-acp` 适配器接入 —— 任何 Agent 都可用薄适配器包装
- qwen-audio-agent 提供"通用 ACP 入口"，可指向任意 ACP 可执行文件，**无需修改 QwenAudio 源码**

### 1.3 差距分析：为什么不能直接嵌入

| 维度 | QwenAudio | Teapot AI | 结论 |
|---|---|---|---|
| 部署模型 | 本地单用户，官方明确禁止暴露网络 | 多用户 Web 服务（114 服务器） | Gateway 无法服务端共享部署 |
| Agent 协议 | ACP（stdio） | AG-UI（HTTP SSE） | 需适配器翻译 |
| 鉴权 | 无用户体系 | JWT + RBAC | 适配器需承载登录/刷新 |
| 会话持久化 | 本地文件（tasks.json 等） | agentscope_sessions（MySQL） | 会话映射到 teapot sessionId |
| 契约稳定性 | 稳定面仅 health/麦克风仲裁/嵌入入口；tasks、timeline、realtime WS 无承诺 | — | 只依赖 ACP 这一稳定面 |

**结论**：语音层复用只有一条可行路径 —— 把 teapot 包装成 ACP 后端 Agent，作为 qwen-audio-agent 的本地语音入口连接远端 teapot（方向 B）。

## 2. 总体架构

```
用户本机                                        远端（114.116.14.26）
─────────────────────────────────────          ──────────────────────
麦克风 ⇄ DashScope Realtime ⇄ Gateway          teapot-ai-server
                     ↓ ACP stdio（子进程）          ↑
              teapot-acp 适配器 ─── HTTPS + JWT ───┘
              （新增，Node 轻进程）        POST /agui/run/{agentKey}（SSE）
```

**关键决策：适配器是独立本地轻进程，不是 Java 工程内的一层。**
ACP 传输为 stdio，Gateway 只能拉起本机子进程；远端 JVM 无法被 stdio 直连。
也否决"Java 进程以 stdio 模式直启"：Spring Boot 冷启动慢，且与 Gateway owned 子进程生命周期模型不匹配。

**Java 工程一期零侵入**，全部复用既有端点：

| 端点 | 用途 | 现状 |
|---|---|---|
| `POST /api/auth/login`、`POST /api/auth/refresh` | JWT 获取/刷新 | 已就绪（AuthController） |
| `POST /agui/run/{agentKey}`（SSE） | 对话主链路 | 已就绪，Authorization 头用户解析已支持（TeapotRuntimeContextResolver） |
| `GET /api/agent/list` | Agent 选择 | 已就绪 |
| `POST /api/chat/session/create` | 会话建立 | 已就绪 |

## 3. 新增工程：tools/teapot-acp

- 位置：仓库 `tools/teapot-acp/`
- 运行时：Node 22+（qwen-audio-agent 同基线），零运行时依赖（内置 fetch/SSE 解析）
- 入口：bin 名 `teapot-acp`，stdio JSON-RPC
- 配置：`~/.config/teapot-acp/config.env`

```env
TEAPOT_BASE_URL=https://<teapot域名>
TEAPOT_USERNAME=<账号>
TEAPOT_PASSWORD=<密码>
TEAPOT_AGENT_KEY=<默认 agentKey，可被 session 参数覆盖>
```

### 3.1 ACP 方法实现

| 方法 | 行为 |
|---|---|
| `initialize` | 返回协议版本与能力：不要求客户端 fs/terminal；声明支持文本 prompt（图片内容块二期） |
| `session/new` | 建 ACP sessionId ↔ teapot sessionId 映射（调 `/api/chat/session/create`），持久化映射到配置目录 |
| `session/prompt` | 提取 content 文本 → 构造 AG-UI RunAgentInput（threadId = teapot sessionId，仅携带本轮消息，多轮上下文由后端 StateStore 保证，与 Web 端 createAguiFetch 同策略）→ 发起 SSE → 流式转发为 `session/update` 通知 → 结束返回 stopReason |
| `session/cancel` | abort 进行中的 SSE 连接，立即返回 |

### 3.2 AG-UI 事件 → ACP session/update 映射表

参照 `teapot-ai-web/src/chat/aguiBridge.ts` 的既有事件语义：

| AG-UI SSE 事件 | ACP session/update |
|---|---|
| TEXT_MESSAGE_CONTENT | agent_message_chunk（流式文本） |
| REASONING_MESSAGE_CONTENT | agent_thought_chunk |
| TOOL_CALL_START / TOOL_CALL_ARGS | tool_call（名称 + 参数） |
| TOOL_CALL_END / 工具结果 | tool_call_update（状态/结果） |
| RUN_FINISHED | prompt 响应 `stopReason: end_turn` |
| RUN_ERROR | prompt 响应 `stopReason: refused` + 错误文本 |
| 其他/心跳 | 忽略 |

## 4. 鉴权状态机

1. 首次请求前 `login` 取 accessToken + refreshToken，缓存本地（文件权限 600）
2. 每次出站请求带 `Authorization: Bearer`；收到 401 → `refresh` → 原请求重试一次
3. refresh 仍失败 → 重新 login；再失败 → 以 ACP 错误响应回报本轮（语音侧播报登录失败）
4. 二期可选（唯一 Java 侧扩展点）：个人 API Key 机器鉴权，替代配置文件中存账号密码

## 5. 会话模型

- qwen-audio-agent 为每 owner 维持一个**固定协调器 Session**（`qwen-audio-agent:<owner>:backend`），跨语音对话复用
- 适配器将其映射为**同一个固定 teapot sessionId**：语音多轮上下文持久化在 agentscope_sessions，teapot Web 端打开同一会话可见、可续聊
- 适配器进程重启后按持久化映射恢复，不新建会话

## 6. 接入与部署

| 侧 | 动作 |
|---|---|
| qwen-audio-agent | 通用 ACP 入口配置后端命令为 `node <path>/teapot-acp`（或 npm link 后直接用可执行名）；不改其源码 |
| 用户本机 | Node 22+；已装 qwen-audio-agent 并配置 `DASHSCOPE_API_KEY`（语音前端模型用） |
| teapot 服务端 | 无变更；SSE 长连接链路与现网 Web 端相同，已经受验证 |

## 7. 验收标准

1. `qwenaudio setup` 检测到 teapot 后端就绪（ACP 握手通过）
2. TUI/WebUI 语音提问 → 远端 teapot Agent 流式回答并语音播报；工具调用过程有进度呈现
3. 语音会话历史在 teapot Web 端同一会话可见、可续聊
4. 语音打断/取消时运行中请求被 abort（服务端 SSE 连接断开）
5. accessToken 过期场景自动刷新，对话不中断
6. 后端任务排队期间语音前台仍可继续对话（QwenAudio 原生能力，验收确认不被适配器阻塞）

## 8. 风险与开放项

| 风险 | 缓解 |
|---|---|
| qwen-audio-agent 通用 ACP 入口对第三方后端的实际行为未实测 | T0 先做最小握手 + 单轮 prompt 打通验证，再展开全量映射 |
| ACP protocolVersion 协商 | 以 `initialize` 回显客户端声明版本为准，能力按最小面声明 |
| 工具权限请求（session/request_permission） | 一期按 always-allow 处理：teapot 工具在云端沙箱执行，无本机副作用 |
| 远端 SSE 长连接稳定性 | 与现网 Web 端同链路（nginx 已验证），异常时适配器以 RUN_ERROR 语义收尾 |
| 图片/多模态语音输入 | 二期：ACP image content block → AG-UI image parts（复用 §19 链路） |

## 9. 工作量估算

| 任务 | 估算 |
|---|---|
| T1 适配器骨架 + initialize/session/new + 鉴权状态机 | 1 人日 |
| T2 session/prompt SSE 转发 + 事件映射 + cancel | 1.5 人日 |
| T3 qwen-audio-agent 联调（通用 ACP 入口、语音打断、任务回流） | 1 人日 |
| **合计** | **约 3.5 人日** |

## 10. 已否决选项（留档）

1. **Gateway 服务器共享部署**：官方明确禁止网络化，且单 owner 单配置，多用户不可行
2. **teapot web 直嵌 QwenAudio WebUI**：其 WebUI 只连同源本地 Gateway，无法指向多用户服务
3. **Java 工程内加 ACP 层 / JVM stdio 直启**：ACP stdio 要求本机子进程；Spring Boot 冷启动与 Gateway 进程所有权模型不匹配。ACP 适配必须是本地轻进程
4. **平台原生语音（浏览器直连 DashScope Realtime）**：可行但属另一条路线（多用户语音），不在本 spec 范围，可另立章节
