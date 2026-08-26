# Teapot AI Desktop —— 本地 Harness 客户端技术规格（Spec）

> 定位：SPEC.md 的伴生规格（对应 §25 主题，独立成文）。
> 状态：设计稿，待评审。日期：2026-08-22。

## 1. 背景与目标

Teapot AI 现状：Agent 循环（AgentScope HarnessAgent）跑在服务端，工具执行落服务端/云沙箱（E2B、AgentRun），服务端永远够不到用户本机。

目标形态：**Electron 桌面客户端内嵌同一套 teapot-core 装配能力（embedded harness），agent 循环与工具执行落在用户本机（本地 shell / 本地文件），服务端收敛为四个角色**：

1. **认证**：沿用 `/api/auth/login`、`/api/auth/refresh`，登录只为换取用户上下文（JWT → userId）
2. **配置中心**：下发 Agent 定义 / 模型入口 / Skill（客户端只读快照）
3. **模型代理**：本地推理请求经服务端中继到厂商，**API Key 永不出服务器**（本规格核心决策）
4. **历史/审计汇聚**：本地会话状态经远程 `AgentStateStore` 实时落服务端库，Web 管理端照常可见（复用 SPEC §24.9 回放视图）

非目标：
- 不做离线模式（模型代理必须在线；离线 = 不可用，明示即可）
- 不在本地保留任何厂商 API Key（含加密下发形式，见 §8 决策记录）
- 服务端现有 Web / Channel 链路零行为变更

## 2. 总体架构

```
Electron 客户端（用户机器）                          teapot server（远程）
┌──────────────────────────────────┐      ┌──────────────────────────────┐
│ renderer: teapot-ai-web 原样复用    │      │ /api/auth/*        认证（复用）  │
│   （API base → 本机 harness）       │      │ /api/desktop/config  配置快照    │
│ main: JVM 生命周期 / 托盘 / 安装器   │      │ /api/desktop/skills  Skill 分发 │
│ 本地 harness（teapot-ai-desktop）   │◄────►│ /api/desktop/state/* 远程状态存储 │
│   ├ AgentAssembler（复用）          │ JWT  │ /api/proxy/v1/chat/completions │
│   ├ HarnessAgent（本机执行工具）      │      │     模型代理（持 Key、审计、限流）  │
│   ├ StateStore: RemoteAgentStateStore│      └──────────────────────────────┘
│   └ ToolApprovalMiddleware ──► Electron 确认弹窗
└──────────────────────────────────┘
```

关键认知：**变的只是运行位置与配置来源，装配规则一份逻辑**——延续 SPEC §24.2 "Web/Channel 共享 AgentAssembler" 的既有手法，增加第三条链路（desktop）。

## 3. 服务端改造

### 3.1 模型代理（核心，独立可先交付）

**端点**：`POST /api/proxy/v1/chat/completions`（OpenAI 兼容），鉴权 `Authorization: Bearer <JWT>`。

| 设计点 | 决策 |
|---|---|
| 路由 | 请求体 `model` 字段直接用 teapot 的 `provider:model` 标识（与 `t_model_entry` 一致），服务端按 `ModelRegistry` 现有解析逻辑选厂商与 Key |
| 流式 | `stream: true` 透传厂商 SSE；`false` 返回标准 OpenAI 结构 |
| Key 管理 | 厂商 Key 仍只在服务器环境变量（`DASHSCOPE_API_KEY` / `OPENAI_API_KEY`，现状不变）；`t_model_entry.base_url` 继续生效 |
| 入口过滤 | 仅放行 `t_model_entry` 启用入口；停用/未知 model 返回 404 |
| 审计 | 每次调用落审计：userId、model、stream、首字节延迟、厂商错误码；日志按既有脱敏规范（MaskingMessageConverter） |
| 限流 | 按 userId 令牌桶（阈值进 `t_sys_config`，默认 30 req/min），超限 429 |
| 模型列表 | `GET /api/proxy/v1/models` 返回启用入口（供本地客户端探测可用性） |

客户端侧因此只需一个 `OpenAIChatModel(baseUrl=代理地址, apiKey=JWT)`——teapot 已有的 OpenAI 兼容扩展（`io.agentscope.extensions.model.openai.OpenAIChatModel`）直接复用。

### 3.2 配置快照

`GET /api/desktop/config`（登录即可，非 admin），响应：

```json
{
  "version": "<所有相关表 max(updated_at) 派生的指纹>",
  "agents": [{
    "agentKey": "digit-tim",
    "name": "...", "description": "...", "avatar": "...",
    "sysPrompt": "...", "modelId": "dashscope:qwen-plus",
    "compactionTrigger": 30, "compactionKeep": 10,
    "feature": { "runtime": { "enableShell": true, "allowedTools": [...] } },
    "boundSkills": ["skill-a", "skill-b"]
  }],
  "models": [{ "modelId": "dashscope:qwen-plus", "capabilities": "image" }]
}
```

- 支持 `If-None-Match`，未变化返回 304
- **不含**任何 `sandbox`/`storage`/`channel` 凭证类配置（本地链路不用云沙箱；feature 中的 `sandbox` 命名空间客户端忽略）
- 仅下发 `status=1` 的 Agent；客户端缓存于本地，离线启动时用旧快照（对话功能除外）

### 3.3 Skill 分发

`GET /api/desktop/skills?agentKey={k}`（登录即可）：

- 返回该 Agent 生效技能集（三来源合并 + 绑定过滤后，与服务端 `assemble()` 语义一致）的 **zip 流**，含 `ETag`
- 单个 zip 上限 50MB，超限的超大技能资源不支持本地链路（首版限制，文案提示）
- 客户端解压至本地技能缓存目录（§4.4）

### 3.4 会话状态存储（远程 AgentStateStore，替代回传协议）

官方文档明确“自行实现 `AgentStateStore` 接口”是生产环境标准扩展点（`RedisAgentStateStore` 即此模式）。桌面链路直接实现远程版，状态在每轮 `call` 结束时就落服务端库，**不再需要独立的回传协议**：

**服务端端点组** `/api/desktop/state/*`（JWT，仅能读写本人数据，薄壳包装现有 `MysqlAgentStateStore`）：

| 端点 | 对应接口方法 |
|---|---|
| `PUT /state/{user}/{session}/{key}`（列表变体同址） | `save` / `save(List)` |
| `GET /state/{user}/{session}/{key}?class=` | `get` / `getList` |
| `HEAD /state/{user}/{session}` | `exists` |
| `DELETE /state/{user}/{session}` | `delete` |
| `GET /state/{user}/sessions` | `listSessionIds` |

- 数据仍落 `agentscope_sessions`（sessionId 带 `dsk-*` 前缀域，与 Web/渠道隔离——延续 §24 “会话域隔离”原则）；回放复用现有反序列化，天然可读；§24.9 union 增加 `source=desktop`
- **调用频率已字节码核实**：状态读写发生在 call 边界（`loadOrCreateAgentStateForSlot` 每轮开始 1 次 + `saveStateToSession` 每轮结束 1 次 + 压缩时额外 1 次），非每条消息——远程实现每轮额外 2~3 次 RTT，相对模型推理秒级耗时可接受；同一 (userId, sessionId) 由 SDK 自动串行化，无并发写冲突（桌面单用户单进程）
- **幂等与一致性**：服务端即唯一事实源，客户端无本地副本，不存在同步/去抖/补传问题；`updatedAt` 竞争问题随之消失

### 3.5 认证

`/api/auth/login`、`/refresh` 原样复用；`LoginRequest` 增加可选 `clientType`（`web|desktop`）进审计，不改校验逻辑。

## 4. 本地 Harness（新模块 `teapot-ai-desktop`）

### 4.1 产物形态

- 新 Maven 模块 `teapot-ai-desktop`：Spring Boot fat jar（依赖 `teapot-ai-core`），以 `desktop` profile 启动
- 监听 `127.0.0.1` 随机空闲端口（启动时探测，写入 `%用户目录%/.teapot-desktop/port`），renderer 读该文件
- 本地暴露与服务端**同构**的对话端点：`/agui/run/{agentKey}`（`X-Agent-Id` 路由）+ `/api/chat/session/*`——teapot-ai-web 零改动，仅切 API base
- JVM 由 jlink 裁剪打包随安装包分发（目标 ≤ 60MB）；Electron main 进程负责拉起/健康检查/退出回收

### 4.2 配置来源改造（唯一动 core 的重构点）

抽接口替换 `AgentAssembler` 对 Mapper 的直接依赖：

```java
public interface AgentConfigProvider {
    AgentDO selectByAgentKey(String agentKey);
    List<String> selectBoundSkills(String agentKey);
}
```

- 服务端 profile：`MysqlAgentConfigProvider`（包装现有 `AgentMapper`/`AgentSkillMapper`，行为不变）
- desktop profile：`RemoteConfigProvider`（读 §3.2 快照缓存，启动拉取 + 定时/手动刷新）
- `AgentAssembler.assemble()` 其余逻辑（模型解析、compaction、runtime、中间件）**一行不改**

### 4.3 状态存储（远程 AgentStateStore）

- **desktop profile**：自实现 `RemoteAgentStateStore implements AgentStateStore`（约 150 行，HTTP 薄壳调 §3.4 端点组），经 `AgentAssembler` 的 `stateStore` 装配点挂入——官方文档确认“自行实现 `AgentStateStore`”为生产标准扩展点（`RedisAgentStateStore` 同模式）
- **序列化**：两端同一 AgentScope SDK，`State` 的 Jackson 序列化/反序列化复用服务端现有配置（`JacksonJsonCodec` + sandbox 扩展 module）；版本错配由 R6 最低版本校验兜住
- **失败语义**：任一状态读写失败 → 该轮对话直接报错（“处理失败”明示），与模型代理断网行为一致——§1 非目标已声明无离线模式，无需降级到本地存储；401 先走 §4.5 refresh 后重试一次

### 4.4 Skill

- 新增 `DirectorySkillRepository implements AgentSkillRepository`：读 §3.3 解压后的本地目录（`{cache}/{skillName}/SKILL.md`）
- `skillRepositories` 在 desktop profile 只挂这一个来源；`SkillFilter` 绑定过滤逻辑复用

### 4.5 模型

- `ProxyModelRegistry`（desktop profile 实现）：任意 `modelId` → `OpenAIChatModel(baseUrl=代理, model=modelId, apiKey=当前JWT)`；thinking 变体通过 GenerateOptions 透传
- JWT 过期处理：401 时触发 refresh（`/api/auth/refresh`）后重试一次；refresh 也失败 → 通知 renderer 重新登录
- 沙箱装配分支在 desktop profile 恒不启用（`applySandbox` 不进入）；`disableShellTool` 由 `feature.runtime.enableShell` 决定，与服务端同一语义——但**执行落本机**（见 §5 权限流）

### 4.6 用户上下文

登录后的 JWT 解析出 userId，注入 `RuntimeContext`（与 Web 链路同一机制）；状态读写与代理调用共用同一 JWT。

## 5. 本地工具权限确认流（一等公民）

本机 shell = 高危，首版即内置，不允许后补：

1. 新增 `ToolApprovalMiddleware extends MiddlewareBase`，desktop profile 经 `AgentAssembler` 的 `extraMiddlewares` 挂入（与 `ChannelSessionIndexMiddleware` 同一扩展点，core 无需再改）
2. 拦截策略（首版从严）：
   - **所有工具调用**弹确认（工具名 + 参数摘要）；`feature.runtime.allowedTools` 白名单继续生效
   - 提供"本次会话信任该工具"选项，粒度 = 会话 × 工具名
3. 确认交互：harness → Electron main（本地 HTTP 回调）→ 原生确认对话框 → 放行/拒绝；拒绝时向模型返回结构化拒绝结果（"用户取消了该操作"），不中断会话
4. 全部放行/拒绝记录进本地审计日志文件（`~/.teapot-desktop/audit.log`）；审计摘要上传为二期项（需扩展状态保存载体或独立端点，首版不阻塞）

## 6. Electron 壳

| 项 | 决策 |
|---|---|
| renderer | `teapot-ai-web` 构建产物直装；登录页直连**远程** `/api/auth/login`，拿到 JWT 后写入本地配置文件（仅本机可读权限），harness 与 renderer 共用 |
| main 进程 | 拉起 JVM 子进程、端口发现、托盘、崩溃重启（最多 3 次）、退出时优雅停 harness |
| 打包 | electron-builder；内置 jlink JRE；自动更新仅更新 Electron 壳与 renderer，harness jar 同步替换（版本号与 teapot-ai-server 发布对齐） |
| 设置页 | 仅三项：服务器地址、工作目录（agent workspace 根）、开机自启 |

## 7. 数据与协议汇总（新增端点清单）

| 端点 | 方法 | 鉴权 | 用途 |
|---|---|---|---|
| `/api/proxy/v1/chat/completions` | POST | JWT | 模型代理（核心） |
| `/api/proxy/v1/models` | GET | JWT | 可用模型探测 |
| `/api/desktop/config` | GET | JWT | Agent/模型配置快照 |
| `/api/desktop/skills` | GET | JWT | 技能 zip 分发 |
| `/api/desktop/state/*` | PUT/GET/HEAD/DELETE | JWT | 远程 AgentStateStore（状态实时落服务端库） |
| （复用）`agentscope_sessions` | — | — | 状态事实源，`dsk-*` 会话域，§24.9 回放直读 |

## 8. 安全设计

| 威胁 | 对策 |
|---|---|
| 厂商 Key 泄露 | **代理模式**：Key 只在服务器环境变量，客户端从未接触。已否决"加密下发 + 本地解密"：解密钥必在本机，且本地模式给 agent 开了 shell，注入攻击可读凭据，加密仅延缓泄露不阻止泄露 |
| JWT 被盗 | 短期 accessToken + refresh 轮换；代理端点限流；审计异常用量告警 |
| 本机工具滥用 / prompt 注入 | §5 强制确认流；会话级信任最小化；审计日志 |
| 配置快照被篡改 | 快照经 HTTPS + JWT；本地快照仅影响本人会话，不具服务端写权限 |
| 状态读写越权 | 服务端强制 `user = JWT 主体`，忽略路径中的他人 user 段 |
| 状态体过大 | 请求体上限 10MB，超限 413；客户端先触发 compaction 压缩历史再重试 |

## 9. 验收标准

1. 服务端：`/api/proxy/v1/chat/completions` 对启用模型流式/非流式均可达；停用模型 404；无 JWT 401；限流 429；审计落库
2. 本地：desktop harness 用快照装配出与服务端**行为一致**的 `digit-tim`（同 sysPrompt / 模型 / 技能），文本对话走模型代理成功
3. 工具：本机执行 `shell` 前必弹确认；拒绝后模型收到取消信息且会话继续
4. 实时可见：本地每轮对话结束后，管理端 §24.9 视图立即以 `source=desktop` 可见、可回放（无同步延迟）
5. 安全：客户端文件系统/内存/流量中不存在任何厂商 API Key（可抽查安装包与运行期内存快照）
6. 回归：服务端 Web / Channel 链路行为零变化（现有冒烟脚本全绿）

## 10. 风险项

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | ~~SDK 无现成文件型 `AgentStateStore`~~ | 已消除 | 改为远程自实现（约 150 行），官方文档确认自实现为生产标准扩展点 |
| R2 | `MiddlewareBase` 能否在工具执行处"暂停等待外部确认"未验证 | 高 | **T0 spike**：字节码级确认拦截点与异步挂起能力；不可行则退化为 ToolsConfig 白名单 + 逐工具包装 |
| R3 | 代理层引入一跳延迟（流式首字节 +50~200ms） | 中 | 代理仅做转发不做缓冲；服务端与厂商同 Region |
| R4 | 快照语义漂移（服务端改表/装配规则，客户端旧版读不懂） | 中 | 快照带 `version` 与客户端最低版本字段；不兼容时强制提示升级 |
| R5 | JVM 内存占用（默认堆）在桌面场景过大 | 中 | 启动参数限定 `-Xmx512m`；JRE 裁剪 |
| R6 | 自动更新期间 harness 与 server 版本错配 | 中 | 壳与 jar 同包发布、同版本号；服务端对桌面端强制最低版本校验 |

## 11. 实施计划与工作量

| 阶段 | 内容 | 估算 |
|---|---|---|
| T0 | 仅剩 1 个 spike（R2 中间件拦截点验证；R1 已消除） | 0.5 人日 |
| M1 | 模型代理 + 审计 + 限流（无客户端依赖，可独立上线） | 2 人日 |
| M2 | `AgentConfigProvider` 重构 + 配置快照 + `teapot-ai-desktop` MVP（本地文本对话跑通） | 4 人日 |
| M3 | Electron 壳 + 权限确认流 + Skill 分发 + 远程状态存储端点 + 管理端回放接入 | 5 人日 |
| M4 | 打包分发（jlink/electron-builder）、更新通道、验收压测 | 2 人日 |

合计约 **14 人日**。M1 结束即有独立价值（任何本地工具都可走代理用平台模型额度）。

## 12. 决策记录

| 决策 | 结论 | 否决的替代 |
|---|---|---|
| 模型 Key 位置 | 服务端代理，Key 不出服务器 | 加密下发 + 本地解密（防不住同用户权限攻击者，且本地 shell 能力放大注入风险） |
| 会话状态存储 | 远程 `RemoteAgentStateStore`（状态实时落服务端库，无回传协议） | 本地 `JsonFileAgentStateStore` + 会话结束回传（多一套同步协议与补传队列，且离线容错在无离线模式的约束下是伪需求） |
| 装配复用方式 | 抽 `AgentConfigProvider`，`AgentAssembler` 主干不动 | 客户端复制一份装配逻辑（三处漂移源头） |
| 对话端点 | 本地同构 `/agui/run/{agentKey}` | 前端为桌面单独写一套（teapot-ai-web 复用价值归零） |
| 权限确认 | 首版全工具拦截，会话级信任 | 白名单静默放行（注入攻击面不可接受） |
