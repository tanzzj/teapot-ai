# Teapot AI 沙箱接入交接文档

**日期**: 2026-08-13（2026-08-14 更新：根因已定位并修复）  
**负责人**: AI Assistant  
**状态**: 🟢 已修复（SDK 解析层 bug，已通过同包补丁类绕过；待部署验证）

---

## 一、项目背景

### 1.1 项目概述

Teapot AI 平台需要接入沙箱能力，用于 Agent 执行 shell 命令和文件操作。采用阿里云 AgentRun 提供的 E2B 兼容端点作为沙箱后端。

### 1.2 技术栈

- **后端**: Java 21 + Spring Boot 3.5.16 + AgentScope Java SDK 2.0.1
- **前端**: React + TypeScript + Vite
- **沙箱 SDK**: `io.agentscope:agentscope-extensions-sandbox-e2b:2.0.1`
- **沙箱后端**: 阿里云 AgentRun E2B 兼容端点
  - API: `https://api.cn-beijing.e2b.fc.aliyuncs.com`
  - Domain: `cn-beijing.e2b.fc.aliyuncs.com`
  - Template: `code-interpreter-v1`

---

## 二、已完成工作

### 2.1 E2B 双链路配置化改造

**目标**: 支持 E2B 和 AgentRun MCP 两条沙箱链路，可配置切换。

**修改文件**:
- `TeapotAiProperties.java` - 新增 `Sandbox` 配置类（link/e2b/agentrun 子配置）
- `application.yml` - 双链路配置块
- `AgentRegistry.java` - `resolveSandboxLink()` 路由逻辑
- `ConfigController.java` - 回显/写入双链路凭证
- `AgentService.java` - `anyConfigured()` 门控

**配置结构**:
```yaml
sandbox:
  link: e2b                          # 首选链路
  e2b:
    enabled: true
    codec: JSON                      # 阿里云仅支持 JSON
    default-workspace-root: /home/user
    default-idle-timeout-seconds: 1800
  agent-run:
    enabled: true
    default-workspace-root: /home/agentscope/workspace
```

### 2.2 前端双链路凭证支持

**修改文件**:
- `types.ts` - `SandboxOptions` 扩展 e2b 字段
- `AgentDetail.tsx` - 双分组凭证表单（E2B + AgentRun）

**功能**:
- 三行状态区（接入状态/首选链路/E2B 状态/AgentRun 状态）
- 双分组表单（E2B 兼容链路 + AgentRun MCP 链路）
- 单按钮保存，留空字段不修改

### 2.3 凭证验证

- 解密服务器 `t_sys_config` 中加密凭证，与用户提供的 E2B API Key 一致
- CLI 验证通过：`e2b template list` / `e2b sandbox spawn` 均可用
- 注入 4 个 E2B env 到服务器 `app.env`（E2B_API_KEY/API_URL/DOMAIN/TEMPLATE）

---

## 三、遇到的问题

### 3.1 WorkspaceStartException（核心阻塞问题）

**错误信息**:
```
INTERNAL_ERROR
io.agentscope.harness.agent.sandbox.SandboxException$WorkspaceStartException: 
Failed to start workspace at: /home/user/workspace
Caused by: SandboxException$ExecException: Command exited with code -2147483648
  at E2bEnvdProcessClient.runShell(E2bEnvdProcessClient.java:103)
  at E2bSandbox.doSetupWorkspace(E2bSandbox.java:146)
```

**发生时机**: Agent 对话时沙箱初始化阶段

**尝试的修复方案**:

#### 方案 1: WorkspaceSpec 显式设置
- **假设**: harness 默认 root=/workspace，E2B 模板 user 无权创建
- **修改**: `AgentRegistry.applyE2b()` 中显式构造 `WorkspaceSpec` 并 `setRoot(workspaceRoot)`
- **结果**:  失败，路径改为 `/home/user/workspace` 后仍报同样错误

#### 方案 2: workspaceRoot 改为已存在目录
- **假设**: `doSetupWorkspace` 执行 `mkdir -p <root>` 时 cwd 也设为 `<root>`，目录不存在导致进程无法启动
- **修改**: `application.yml` 中 `default-workspace-root` 从 `/home/user/workspace` 改为 `/home/user`
- **结果**: ❌ 失败，用户反馈"一样是不行"

#### 方案 3: HTTP/2 协议问题排查
- **假设**: OkHttp 默认使用 HTTP/2，阿里云网关 HTTP/2 流式响应有 bug
- **验证**: 
  - 服务器 curl 测试（无 --http2 支持）
  - 本地最小复现 `Repro.java`（HTTP/1.1 vs 默认 OkHttp）
- **结果**: ❌ 两种模式都报同样错误，排除 HTTP/2 问题

#### 方案 4: envd 响应流解析问题
- **假设**: SDK 的 `drainStartStream` 解析 connect+json 响应时提前退出
- **验证**: 
  - 反编译 `E2bEnvdProcessClient` 字节码
  - 服务器 curl 探测 envd 响应（成功收到完整帧序列）
  - 本地 Repro 复现（同样失败）
- **结果**: ✅ **方向正确，最终确认为根因（见 3.3）**

### 3.3 根因确认（2026-08-14）：SDK JSON 解析丢弃 exitCode=0

**排查证据链**（`deploy/e2b-repro` 工程，模式 full/spy/shadowparse）：

1. **配置无错**：对照 AgentScope 2.0 官方文档，codec=JSON（官方 #1844 修复项）、apiBaseUrl/domain/template、workspaceRoot=/home/user（恰为 SDK 默认值）均正确。
2. **升级无解**：2.0.2 与 2.0.1 的 `E2bEnvdProcessClient` 字节码完全一致。
3. **服务端无错**：spy 模式（OkHttp network interceptor）dump 出 SDK 收到的响应字节完全正确：HTTP 200，完整帧序列 start → end(`exitCode=0`) → flag=2 尾帧；同一沙箱上原生 OkHttp 手工请求也成功。
4. **解析失败复现**：shadowparse 模式用反射调用 SDK 自己的 `parseStartResponseFrame`/`drainStartStream`，喂入 spy 捕获的字节——每帧都 PARSE_OK 但 `getAllFields()` 为空，`drainStartStream` 返回 INT_MIN。
5. **最小隔离实验**（`OneofTest.java` + protobuf-java 4.29.3）：
   ```
   b.setField(exit_code, 0) → getAllFields() = []     ← 设置被静默丢弃！
   b.setField(exit_code, 7) → getAllFields() = [exit_code]
   ```

**根因**：proto3 标量字段无 presence 语义，protobuf-java 的 `FieldSet` 会静默丢弃通过 `DynamicMessage.Builder.setField` 设置的默认值（sint32 的 0）。SDK 的 `parseJsonStartResponse` 把 JSON `exitCode=0` setField 后被丢弃 → EndEvent 为空 → `getAllFields().isEmpty()` 判定成立 → end 事件不挂到响应消息 → `drainStartStream` 中 `hasField(end)==false` → exitCode 保持 INT_MIN。

**影响范围**：任何成功（exit 0）的命令必然失败；非 0 退出码反而能正确解析。PROTO codec 同样受影响（proto3 线格式本身不序列化默认值 0）。

### 3.4 修复方案：同包补丁类（已实施）

**原理**：把补丁后的 `E2bEnvdProcessClient`（同包同名）放进应用自身 classes。Spring Boot fat jar 中 `BOOT-INF/classes` 的类加载优先于 `BOOT-INF/lib/*.jar`，无需 fork SDK。

**文件**：`teapot-ai-server/teapot-ai-start/src/main/java/io/agentscope/extensions/sandbox/e2b/E2bEnvdProcessClient.java`
（CFR 反编译 2.0.1 原版后打两处补丁）

**补丁点**：
1. `parseJsonStartResponse`：end 事件额外复制 `exited`/`status`（非默认值可保持 presence），确保 exitCode=0 时 EndEvent 不被丢弃。
2. `drainStartStream`：end 事件存在但 `exit_code` 字段缺失时（proto3 默认值 0 被省略/丢弃），视为退出码 0。

**验证**：
- Repro full 模式：`START_OK`、`EXEC ok=true exit=0`、命令输出正确 ✅
- `mvn package` 后 `app.jar` 中补丁类位于 `BOOT-INF/classes/io/agentscope/extensions/sandbox/e2b/` ✅

**后续**：
- 部署 app.jar 到服务器做端到端验证（Agent 对话触发沙箱）。
- 建议向 agentscope-java 上游提 issue（附 shadowparse/OneofTest 证据），官方修复后可删除补丁类。
- SDK 升级到 2.0.2+ 时**必须核对**该 bug 是否已修，未修则继续保留补丁类。

### 3.5 其他发现的问题

#### 3.5.1 connect+proto 编码不支持
- **问题**: SDK 默认 codec=PROTO，阿里云 envd 返回 HTTP 400
- **修复**: `E2bFilesystemSpec.codec(E2bCodec.JSON)` ✅ 已解决

#### 3.5.2 沙箱状态持久化失败
- **错误**: `AgentStateStore ID cannot contain path separators`
- **原因**: `SessionSandboxStateStore` 组装的 ID 含 `/`，被 `MysqlAgentStateStore` 拒绝
- **影响**: 每次对话都新建沙箱（约 2 秒延迟），功能不受影响
- **状态**: ⚠️ 已知问题，未修复

#### 3.5.3 网关 X-Access-Token 要求
- **发现**: 阿里云网关要求 `X-Access-Token` 头（值为创建沙箱时返回的 `envdAccessToken`）
- **验证**: 不加此头返回 403 `AccessDenied`
- **状态**: ✅ SDK 已正确发送（`E2bPlatformHttp.applySandboxFields` 会设置 token）

---

## 四、技术细节

### 4.1 SDK 关键流程

```
AgentRegistry.applyE2b()
  → E2bFilesystemSpec 构建（apiKey/domain/template/codec/workspaceRoot）
  → WorkspaceSpec 设置（root=workspaceRoot）
  → HarnessAgent.Builder.filesystem(spec)
  
HarnessAgent.start()
  → SandboxManager.acquire()
  → E2bSandboxClient.create(workspaceSpec, snapshotSpec, options)
  → E2bSandbox.start()
    → ensureSandbox()          // 调用平台 API 创建沙箱
    → doSetupWorkspace()       // ❌ 失败点：runShell("mkdir -p <root>", cwd=<root>)
    → workspaceRootReady=true
```

### 4.2 envd 协议

**请求**:
```
POST https://49983-{sandboxId}.{domain}/process.Process/Start
Headers:
  Content-Type: application/connect+json
  Connect-Protocol-Version: 1
  E2b-Sandbox-Id: {sandboxId}
  E2b-Sandbox-Port: 49983
  Authorization: Basic base64("user:")
  X-Access-Token: {envdAccessToken}
Body: [flag=0][4-byte BE length][JSON payload]
  {"process":{"cmd":"/bin/bash","args":["-l","-c","mkdir -p '/home/user'"],"cwd":"/home/user"}}
```

**响应**（curl 实测成功）:
```
HTTP/1.1 200 OK
Content-Type: application/connect+json
Transfer-Encoding: chunked

[flag=0][len=29]{"event":{"start":{"pid":1}}}
[flag=0][len=36]{"event":{"data":{"stdout":"aGkK"}}}
[flag=0][len=64]{"event":{"end":{"exitCode":0,"exited":true,"status":"exited"}}}
[flag=2][len=2]{}
```

### 4.3 字节码关键发现

**E2bSandbox.doSetupWorkspace**:
```java
runShell(state, 
  cwd=getWorkspaceRoot(),           // ← 问题：cwd 设为待创建目录
  cmd="mkdir -p '" + shellSingleQuote(getWorkspaceRoot()) + "'",
  timeout=30)
```

**E2bEnvdProcessClient.runShellCapture**:
```java
int exitCode = Integer.MIN_VALUE;   // ← 初始值
// ... HTTP 请求 ...
if (!response.isSuccessful()) {
  throw new SandboxRuntimeException(...);  // 403 走这里
}
exitCode = drainStartStream(body);  // ← 解析响应流
if (exitCode != 0) {
  throw new ExecException("Command exited with code " + exitCode);
}
```

**drainStartStream**:
```java
int exitCode = Integer.MIN_VALUE;
while (true) {
  int flag = stream.read();         // ← 返回 -1 时退出
  if (flag == -1) break;            // ← 连接关闭
  byte[] lenBytes = stream.readNBytes(4);
  if (lenBytes.length < 4) break;   // ← 读取不完整
  int len = ByteBuffer.wrap(lenBytes).order(BIG_ENDIAN).getInt() & 0x7FFFFFFF;
  byte[] frame = stream.readNBytes(len);
  if (frame.length < len) break;    // ← 读取不完整
  if (flag == 0) {
    DynamicMessage msg = parseStartResponseFrame(frame);
    // 解析 event.end.exitCode ...
  }
}
return exitCode;  // ← 如果没解析到 end 事件，返回 INT_MIN
```

---

## 五、当前状态

### 5.1 代码状态
- ✅ 双链路配置化改造完成
- ✅ 前端双链路凭证支持完成
- ✅ codec 修复已部署
- ✅ WorkspaceSpec 显式设置已部署
- ✅ workspaceRoot 改为 `/home/user` 已部署
- ✅ **SDK 解析 bug 补丁类已入库并验证**（本地 Repro 通过，fat jar 构建通过）
- 🟡 **待部署服务器做端到端验证**

### 5.2 服务器状态
- 服务正常运行（HEALTH_OK）
- E2B 凭证已注入（app.env）
- 沙箱配置已启用（link=e2b）
- 注意：服务器上跑的还是旧 jar，需部署含补丁类的新 `app.jar`

### 5.3 已排除的方向（勿重复排查）
- ❌ workspaceRoot/cwd 目录问题（whoami 也失败，且响应字节正确）
- ❌ HTTP/2 协议问题
- ❌ 服务端/网关问题（curl 与原生 OkHttp 均成功）
- ❌ protobuf-java/jackson 版本冲突（均为 SDK 声明版本 4.29.3/2.15.x）
- ❌ 升级 SDK 2.0.2（字节码相同）

---

## 六、建议下一步

### 6.1 立即执行
1. **部署含补丁类的新 `app.jar` 到服务器**，Agent 对话端到端验证沙箱启动与命令执行。
2. **向 agentscope-java 上游提 issue**：标题建议 "E2bEnvdProcessClient drops exitCode=0 due to proto3 FieldSet default-value semantics"，附 `deploy/e2b-repro` 的 shadowparse/OneofTest 证据。

### 6.2 后续维护
1. SDK 升级时核对 `E2bEnvdProcessClient` 是否已修复该 bug：官方修复后可删除 `teapot-ai-start` 中的补丁类；未修复则保留。
2. 沙箱状态持久化失败（3.5.2）仍未修复，每次对话新建沙箱约 2 秒延迟，优先级低。
3. 备选链路：AgentRun MCP（`sandbox.link=agentrun`）仍可作为 E2B 链路不可用时的降级方案。

---

## 七、相关文件清单

### 7.1 核心代码
- `d:\teamer\teapot-ai\teapot-ai-server\teapot-ai-core\src\main\java\com\teamer\teapot\ai\core\service\AgentRegistry.java`
- `d:\teamer\teapot-ai\teapot-ai-server\teapot-ai-core\src\main\java\com\teamer\teapot\ai\core\config\TeapotAiProperties.java`
- `d:\teamer\teapot-ai\teapot-ai-server\teapot-ai-start\src\main\resources\application.yml`
- `d:\teamer\teapot-ai\teapot-ai-server\teapot-ai-core\src\main\java\com\teamer\teapot\ai\core\controller\ConfigController.java`
- `d:\teamer\teapot-ai\teapot-ai-server\teapot-ai-core\src\main\java\com\teamer\teapot\ai\core\service\AgentRunConnection.java`

### 7.2 前端代码
- `d:\teamer\teapot-ai\teapot-ai-web\src\types.ts`
- `d:\teamer\teapot-ai\teapot-ai-web\src\pages\AgentDetail.tsx`

### 7.3 调试脚本（服务器）
- `/tmp/probe-envd2.sh` - envd 协议探测（无 token）
- `/tmp/probe-envd3.sh` - envd 协议探测（有 token）
- `/tmp/show-e2b-env.sh` - 显示 E2B 环境变量

### 7.4 本地复现工程
- `d:\teamer\teapot-ai\deploy\e2b-repro\` - 最小复现项目
  - `Repro.java` - 复现代码（模式：full/spy/rawokhttp/shadowparse/hold/nostart）
  - `OneofTest.java` / `DumpDesc.java` - protobuf FieldSet 行为与描述符验证
  - `decompiled/E2bEnvdProcessClient.java` - CFR 反编译原版源码
  - `run.ps1` - 运行脚本（⚠️ 内含明文 E2B 凭证，归档前需清理）

### 7.5 补丁类
- `d:\teamer\teapot-ai\teapot-ai-server\teapot-ai-start\src\main\java\io\agentscope\extensions\sandbox\e2b\E2bEnvdProcessClient.java`
  - 头部注释说明了两处补丁点与生效原理（BOOT-INF/classes 优先）

---

## 八、经验教训

### 8.1 阿里云 E2B 兼容端点坑点
1. **仅支持 connect+json 编码**（PROTO 会 400）
2. **要求 X-Access-Token 头**（否则 403）
3. **envd 版本 0.5.2**（可能与 SDK 预期不一致）

### 8.2 AgentScope SDK 坑点
1. **WorkspaceSpec 默认 root=/workspace**（不会自动同步 workspaceRoot）
2. **doSetupWorkspace 的 cwd 问题**（设为待创建目录）
3. **状态持久化 ID 含路径分隔符**（MySQL store 拒绝）

### 8.3 调试经验
1. **反编译字节码**是理解 SDK 行为的有效手段
2. **curl 探测**能验证协议层面是否正常
3. **最小复现**能隔离问题（本次通过 spy/shadowparse 模式最终复现）
4. **network interceptor 抓包**：证明"SDK 收到的字节正确但仍失败"是锁定客户端 bug 的关键一步
5. **proto3 陷阱**：`DynamicMessage.Builder.setField` 设置标量默认值（0/false/""）会被 protobuf-java `FieldSet` 静默丢弃，不能依赖 `getAllFields()`/`hasField()` 判断"是否收到过该字段"

---

**文档结束**
