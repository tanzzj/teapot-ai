# Teapot Voice Desktop（QwenAudio × Electron 客户端）规格

> 方向：单一 Electron 桌面客户端 = teapot 登录态宿主 + QwenAudio 语音运行时编排。
> 与 `SPEC-qwen-audio-acp.md`（适配器）配套；适配器已实现并冒烟通过，本规格只新增"壳"。

## 1. 背景与动机

- QwenAudio 路线的先天债：本地单用户、无登录态，只能借机器凭证/账号密码（明文、撞风控、停用即瘫）
- 纯 Electron 自研语音路线的先天成本：实时语音管线（VAD/打断/低延迟）要自己写
- 本方向两者取长：语音体验复用 QwenAudio，登录态由客户端持有用户真实 JWT——**服务端零改动**，
  上一版"渠道化机器凭证"服务端方案在本方向下不再需要

## 2. 形态

用户感知：下载 → 登录自己的 teapot 账号 → 直接和 Agent 实时语音对话；会话在 Web 端同步可见可续聊。

## 3. 总体架构

```
┌─ Teapot Voice Desktop (Electron) ─────────────────┐
│                                                    │
│  主进程                                            │
│  ├─ 登录/会话管理（登录页 → JWT，存 safeStorage）  │
│  ├─ Token Service（127.0.0.1 随机端口，只回令牌）  │
│  ├─ 子进程编排                                     │
│  │   ├─ qwen-audio Gateway（本地，仅语音运行时）   │
│  │   └─ teapot-acp（Gateway 的 ACP 后端，已实现）  │
│  └─ 窗口：加载 QwenAudio Web UI → 本地 Gateway     │
│                                                    │
└──────────────┬─────────────────────────────────────┘
               │ HTTPS（用户 JWT）
        teapot-ai-server（零改动）
```

## 4. 登录态传递（核心设计）

1. 用户在客户端登录 → 主进程持有 access/refresh token（Electron `safeStorage` 加密，不落明文配置）
2. 主进程起 Token Service：`http://127.0.0.1:<随机端口>/token`，仅返回当前有效令牌
   （绑定随机端口 + 一次性 nonce，防本机其他进程探测）
3. `teapot-acp` 小改：新增"令牌注入模式"——不配用户名密码时，401/启动时向
   `TEAPOT_TOKEN_SERVICE`（环境变量，由主进程注入）取令牌；取不到才回退现有登录流程
4. 令牌续期由客户端负责（refresh），适配器永远拿到有效 JWT——语音会话归属用户本人

## 5. 组件清单与改动面

| 组件 | 状态 | 改动 |
|---|---|---|
| `teapot-ai-server` | — | 零改动 |
| `tools/teapot-acp` | 已实现 | +令牌注入模式（约 50 行） |
| qwen-audio-agent Gateway | 第三方 | 原样捆绑，ACP 后端指向 teapot-acp，版本锁死 |
| QwenAudio Web UI | 第三方 | 一期原样加载；二期换自有 UI |
| Electron 壳 | 新建 | 登录、Token Service、进程编排、窗口 |

## 6. 里程碑

- **M0（0.5 人日，先验证再立项）**：手工跑通「Gateway + teapot-acp + 其 Web UI」三件套（无 Electron），
  确认 Gateway 可独立运行、UI 为纯 Web 可被 BrowserWindow 加载、ACP 后端配置点可用
- **M1（3 人日）**：Electron 壳 + 登录 + Token Service + 进程编排 + 嵌入 QwenAudio UI，语音到 teapot 端到端
- **M2（3~5 人日）**：自有对话/会话管理 UI 替换 QwenAudio UI（调 Gateway 本地接口），统一产品观感
- **M3**：自动更新、打包签名、多平台、唤醒词等增值

## 7. 风险

| 风险 | 缓解 |
|---|---|
| QwenAudio 稳定契约面薄（tasks/timeline/realtime WS 无承诺） | M0 先验；升级走锁版本 + 回归冒烟 |
| UI 与其 Gateway 耦合方式未知（是否纯 Web） | M0 核心验证项；不成立则 M1 降级为"并列双窗口"形态 |
| 捆绑第三方运行时的体积与依赖 | Gateway 为 Node，可接受；依赖清单在 M0 冻结 |
| Token Service 本机攻击面 | 随机端口 + 仅回令牌 + 可行时校验来源进程 |

## 8. 与既有结论的关系

- 「ACP 渠道化机器凭证」服务端方案：本方向下作废（身份 = 用户真实 JWT）
- `tools/teapot-acp`：完整复用，仅加令牌注入模式
- Web 端 MediaRecorder 语音附件上传：不受影响，各自独立

## 附录 A：M0 手工启动步骤（已在本机落地）

前置：Node ≥ 22.22.2（本机 `C:\Users\tanzj\nodejs\node-v22.23.2-win-x64`）+ DashScope API Key（默认实时语音前端需要）。

1. 安装：`npm install -g qwen-audio-agent`
2. 生成配置模板：`qwenaudio config` → `~/.config/qwaudio/config.env`（Windows 即 `%USERPROFILE%\.config\qwaudio\config.env`）
3. 配置对接 teapot（通用 ACP 入口，Gateway 直接托管该子进程）：
   ```dotenv
   DASHSCOPE_API_KEY=<key>
   AGENT_PROTOCOL=acp
   ACP_COMMAND=node            # PATH 已是 Node 22 后可直接用 node，否则写 node.exe 全路径
   ACP_ARGS=["d:/teamer/teapot-ai/tools/teapot-acp/index.mjs"]
   ACP_LABEL=Teapot AI
   ```
   适配器自身配置照旧在 `~/.config/teapot-acp/config.env`（TEAPOT_BASE_URL/USERNAME/PASSWORD/AGENT_KEY）。
4. 只读自检：`qwenaudio setup`（检查后端可执行文件、ACP 集成与适配器就绪，不安装不登录）
5. 启动：终端 1 `qwenaudio`（Gateway）；终端 2 `qwenaudio webui`（浏览器界面，默认 `http://127.0.0.1:3101`），或 `qwenaudio tui`

注意：升级 qwen-audio-agent 后若 Gateway 在后台运行，需 `qwenaudio gateway restart` 生效。

