# QwenPaw 桌面端架构调研

> 调研对象：https://github.com/agentscope-ai/QwenPaw（clone 至 `d:\teamer\QwenPaw`）
> 调研时间：2026-08-29
> 关联 Spec：[SPEC-desktop-local-harness.md](./SPEC-desktop-local-harness.md)（Teapot AI 桌面端设计）

## 1. 概述

**重要发现：QwenPaw 用的是 Tauri 2.x，不是 Electron。** Tauri 用 Rust 做主进程 + 系统原生 WebView（Windows 上是 WebView2），不捆绑 Chromium，安装包和内存占用都小很多。但整体"桌面壳 + 本地后端子进程"的架构思路与 Teapot AI 的 Electron 本地 harness 设计高度同构，有很强的参考价值。

项目 monorepo 结构：

```
QwenPaw/
├── src/                  # Python 后端源码（FastAPI + AgentScope）
├── console/              # 前端 + Tauri 桌面壳
│   ├── src/              # React 前端（Vite + React + Ant Design）
│   ├── src-tauri/        # Rust 主进程（Tauri 2.x）
│   ├── tauri.html        # Tauri 入口 HTML
│   ├── index.html        # 浏览器模式入口 HTML
│   └── vite.config.ts
├── packages/
│   └── qwenpawmail-mcp/  # 邮件 MCP 服务（独立包）
├── plugins/              # 插件目录
├── scripts/pack-tauri/   # 打包脚本
├── pyproject.toml        # Python 项目配置
└── Makefile              # 测试命令
```

Electron/Tauri 相关的全部集中在 `console/src-tauri/`。

## 2. 整体架构

```
┌─ Tauri Rust 主进程 (qwenpaw-desktop) ──────────────────┐
│  ├─ 窗口 + WebView 管理                                 │
│  ├─ 系统托盘 (tray.rs)                                  │
│  ├─ 自动更新 (tauri-plugin-updater, minisign 签名)      │
│  ├─ IPC Commands (invoke_handler)                       │
│  └─ Sidecar 管理 (backend.rs)                           │
│      └─ spawn → qwenpaw-backend (Python FastAPI/uvicorn)│
│          └─ 监听 127.0.0.1:{随机端口}                    │
├─ WebView 渲染进程 ──────────────────────────────────────┤
│  ① bootstrap.tsx: 轮询后端 /api/version (最长 180s)      │
│  ② 就绪后 window.location.replace → 后端托管的 /console │
│  ③ React SPA (Vite + Ant Design) 直接 fetch 本地后端    │
└─────────────────────────────────────────────────────────┘
```

## 3. 主进程入口

**没有 Electron 的 `main` 字段**，入口是 Rust 二进制：

- `console/src-tauri/src/main.rs` — 程序入口
  ```rust
  #![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
  fn main() {
      app_lib::run();
  }
  ```

- `console/src-tauri/src/lib.rs` — 核心 `run()` 函数，等价于 Electron 的 `main.ts`，负责：
  - 注册 Tauri 插件（shell、dialog、updater）
  - 注册所有 IPC command（`invoke_handler`）
  - `setup()` 阶段启动后端 sidecar + 系统托盘
  - 窗口关闭事件拦截（最小化到托盘）
  - `RunEvent::ExitRequested` 中做后端优雅关闭

- `console/src-tauri/Cargo.toml` — Rust 依赖清单
  ```toml
  [package]
  name = "qwenpaw-desktop"
  [[bin]]
  name = "qwenpaw-desktop"
  path = "src/main.rs"
  ```

## 4. 两阶段加载（最值得借鉴的设计）

### 阶段 1 — Bootstrap

Tauri 加载本地打包的 `tauri.html` → `src/tauri/bootstrap.tsx`：

- 显示 loading 页（`BackendLoadingPage`）
- 轮询 `http://127.0.0.1:{port}/api/version`（`useBackendReadyPolling`，最长 180 秒）
- 后端就绪后 `window.location.replace()` 重定向

Tauri 配置（`tauri.conf.json`）：
```json
"devUrl": "http://localhost:5173/tauri.html",
"beforeDevCommand": "vite --mode tauri"
```

### 阶段 2 — Console

WebView 导航到后端托管的 SPA：`http://127.0.0.1:{port}/console?desktop=1&_={timestamp}`

- 前端构建产物由 `vite build` 生成到 `dist-tauri/`
- 生产环境由 PyInstaller 打包的 `qwenpaw-backend` 直接 serve 静态文件

**关键收益**：同一套前端代码既能跑在桌面（Tauri WebView），也能跑在浏览器（直连后端），**前端零分支**。

### Vite 配置

开发（`vite.config.ts`）：
```ts
server: {
  host: "0.0.0.0", port: 5173,
  proxy: { "/api": { target: "http://localhost:8088" } }
}
build: {
  cssCodeSplit: true,
  rollupOptions: { output: { manualChunks: { /* react-vendor, ui-vendor, charts-vendor 等 */ } } }
}
```

Tauri 专用构建（`vite.bootstrap.config.ts`）：
```ts
build: {
  outDir: "dist-tauri",
  rollupOptions: { input: { index: "../tauri.html" } }
}
```

## 5. 后端 Sidecar 生命周期

### 开发模式启动

`src-tauri/src/backend/command.rs`：
```rust
// 优先用 uv，否则找 venv/python3/python
app.shell()
    .command("uv")
    .args(["run", "python", "-m", "qwenpaw.tauri.entry"])
    .current_dir(repo_root)
    .env("PYTHONPATH", source_path.display().to_string())
```

### 生产模式启动

```rust
// 运行打包好的 qwenpaw-backend 可执行文件
let backend = packaged_backend_executable(app)?;
// 路径: resources/binaries/qwenpaw-backend/qwenpaw-backend.exe
app.shell().command(backend)
    .env("QWENPAW_DESKTOP_PY_RUNTIME", ...)  // 捆绑的 Python 运行时
    .env("QWENPAW_DESKTOP_NODE_RUNTIME", ...) // 捆绑的 Node 运行时
```

### 就绪协议

`backend/events.rs`：后端 stdout 输出 `QWENPAW_BACKEND_READY {"port":54321}`，Rust 解析端口后存入 `BackendState`，前端通过 `invoke("backend_port")` 获取。

### 优雅关闭

`backend.rs`：HTTP POST `http://127.0.0.1:{port}/api/desktop/shutdown` + token 认证，让 uvicorn 自行退出；超时（60s）则 force kill。

## 6. 生产打包（三层产物捆绑）

```json
// tauri.conf.json
"bundle": {
  "targets": ["app", "nsis"],
  "icon": ["icon.icns", "icon.ico"],
  "resources": [
    "binaries/qwenpaw-backend",  // Python FastAPI 后端 (PyInstaller)
    "binaries/python-runtime",   // 独立 Python（插件 pip install 用）
    "binaries/node-runtime"      // 独立 Node（MCP 工具用）
  ],
  "windows": {
    "nsis": {
      "installerHooks": "nsis-hooks.nsh",
      "languages": ["English", "SimpChinese", "Indonesian", "Japanese", "Russian", ...]
    }
  }
}
```

打包脚本在 `scripts/pack-tauri/`：

- `build_pyinstaller.sh` / `build_win_pyinstaller.ps1` — PyInstaller 打包 Python 后端
- `stage_python_runtime.py` — 打包独立 Python 运行时
- `stage_node_runtime.py` — 打包独立 Node 运行时
- `finalize_tauri_bootstrap.mjs` — 最终 Tauri 构建整合

## 7. IPC 暴露给前端的命令

```rust
.invoke_handler(tauri::generate_handler![
    open_devtools,
    backend_download::download_backend_file,
    backend::backend_port,            // 前端查询后端端口
    backend::backend_startup_error,   // 前端查询启动错误
    backend::restart_backend,         // 前端触发重启后端
    external_link::open_external_link,
    updates::check_desktop_update,
    updates::install_desktop_update,
    tray::minimize_to_tray,
    tray::quit_app,
    tray::set_tray_labels,            // 前端传国际化文本更新托盘菜单
    tray::ack_close,
])
```

## 8. 安全策略（CSP）

```json
"security": {
  "csp": {
    "default-src": "'self'",
    "connect-src": "'self' http://127.0.0.1:* ws://127.0.0.1:* ipc: http://ipc.localhost",
    "script-src": "'self'",
    "style-src": "'self' 'unsafe-inline' https://fonts.googleapis.com",
    "img-src": "'self' asset: http://asset.localhost blob: data: https:"
  }
}
```

## 9. 特殊设计亮点

| 特性 | 实现 | 文件 |
|------|------|------|
| **WebView 崩溃恢复** | Windows WebView2 进程崩溃检测 + 自动重建（最多 3 次） | `src-tauri/src/webview_recovery.rs` |
| **最小化到托盘** | 关闭窗口时拦截，前端选择偏好或弹 prompt，1.5s 无 ack 自动 fallback 隐藏 | `tray.rs` + `src/tauri/CloseWindowPrompt.tsx` |
| **macOS Dock 恢复** | `RunEvent::Reopen` 事件重新显示窗口 | `lib.rs` |
| **DevTools 隐藏入口** | 前端 8 次点击 logo 触发 `open_devtools` command | `lib.rs` |
| **自动更新** | `tauri-plugin-updater`，minisign 签名验证，支持缓存更新包延迟安装 | `src-tauri/src/updates.rs` |
| **外部链接安全** | 白名单协议校验（http/https/mailto/tel），HTML 文件 URI 验证 | `src-tauri/src/external_link.rs` |

未见显式单实例锁、Deep Link、自定义协议实现。

## 10. 对 Teapot AI 桌面端的参考价值

### 架构对比

| 维度 | Teapot AI 设计（SPEC-desktop-local-harness.md） | QwenPaw 实现 |
|------|------------------------------------------------|--------------|
| 桌面壳 | Electron | Tauri 2.x（更轻） |
| 本地后端 | Spring Boot fat jar + jlink JRE | PyInstaller 打包 Python FastAPI |
| 前端加载 | Electron 直接 loadURL 本地 Spring Boot | **两阶段加载**：bootstrap → 重定向到后端托管 SPA |
| 状态存储 | 远程 `RemoteAgentStateStore` | 本地（Python 侧自行管理） |
| 模型 Key | 服务端代理（不出服务器） | 待核实 |
| 就绪协议 | 未定 | **stdout 约定字符串**（简单可靠） |
| 额外 runtime | 无 | 捆绑独立 Python + Node（给插件/MCP 用） |
| 崩溃恢复 | 未设计 | WebView2 进程级崩溃自动重建（3 次） |

### 最值得借鉴的 3 个点

1. **两阶段加载**：Teapot AI 目前设计是 Electron 直接 loadURL 本地 Spring Boot。QwenPaw 的 bootstrap → redirect 模式能让同一份前端代码无缝跑在桌面/浏览器，且 loading 状态独立于主应用，更健壮。
2. **stdout 就绪协议**：`QWENPAW_BACKEND_READY {"port":...}` 比端口轮询更即时，且端口随机化避免了冲突。Teapot AI 的 harness 启动可以照搬。
3. **独立 runtime 捆绑**：如果未来 Teapot AI 也要支持本地 MCP 工具或插件，QwenPaw 的 `python-runtime` + `node-runtime` 捆绑思路是现成参考。

### 值得商榷的点

**Tauri vs Electron 选型**：Tauri 更轻但 Windows 上依赖 WebView2（Win10+ 自带，Win7 需安装），且 Rust 生态与 Java 生态无复用。Teapot AI 团队 Java 栈更熟，Electron + Spring Boot 的维护成本可能更可控。

### 可纳入 Teapot AI Spec 的候选改进

- [ ] 在 `SPEC-desktop-local-harness.md` 中补一节"harness 启动就绪协议"，参考 QwenPaw 的 stdout 约定
- [ ] 评估 teapot-ai-web 是否能走"后端托管 SPA + 桌面/浏览器零分支"路线（需核实 Spring Boot 静态资源与 AG-UI 路由的兼容性）
- [ ] WebView2 崩溃恢复能力是否值得在 Electron 端做等价实现（Electron 主进程崩溃语义不同，需另议）
