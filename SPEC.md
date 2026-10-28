# Teapot AI 平台 — 建设技术规格书（Spec）

> 版本：v1.8（Spring Boot 与 MyBatis 升级为配套兼容的新版本）
> 日期：2026-08-12
> 变更摘要：① Spring Boot 升至 **3.5.x**（3.x 线最新稳定版），MyBatis Starter 用配套 **3.0.x 最新补丁**；SB 4.x + MyBatis Starter 4.0.x 待 AgentScope 适配后二期评估（§3 / 风险 12）；② 后端工程 Git 仓库：`https://github.com/tanzzj/teapot-ai.git`；③ 模块结构 bom / common / rbac / core / start；一期 DashScope + OpenAI、Spark Design 前端、Docker MySQL 8.4 LTS
> 作者：tanzj + AI 结对
> 状态：**待评审**（本文档只做设计，不含任何代码实施）

---

## 1. 项目背景与目标

Teapot 现有平台（`d:\teamer\teapot`）是基于 JDK 8 / Spring Boot 2.1.9 的 Java 多模块工程，已具备
RBAC 登录鉴权体系（`teapot-rbac` 模块）。本项目在**新仓库 `teapot-ai`** 中建设独立的 AI Agent 平台：

1. **AI 框架基座**：基于 AgentScope Java **2.0.1**（2026-08-05 发布）+ **JDK 21** 构建 HarnessAgent 运行时。
2. **前端**：React（SPA），通过 AG-UI 协议（SSE）与 Agent 运行时实时交互。
3. **登录-用户体系**：继承并演进 `teapot-rbac` 的 RBAC 设计（`RBACUser`/`RBACRole` 接口、
   配置驱动的资源-角色映射、`ContextUtil` 线程上下文），适配 JDK 21 / Spring Boot 3.x / jakarta 命名空间。
4. **Agent 管理**：用户可在平台上新增 Agent（人设、模型、压缩策略等配置化），并即时对话。
5. **Skill 管理**：为 Agent 注册 skill；skill 内容（`SKILL.md` + 附件资源）通过**平台配置化创建**，
   存储于 MySQL Skill Repository，Agent 运行时实时生效。
6. **数据库**：MySQL **8.4 LTS**（Docker 部署于用户服务器 `114.116.14.26`，现状见 §11.1），同时承载业务数据与
   AgentScope 的会话状态（AgentStateStore）与 Skill 仓库（SkillRepository）。

### 1.1 非目标（本期不做）

- 不改造老 teapot 工程（JDK 8 / SB 2.1 保持原样，新平台独立部署）。
- 沙箱执行选用**阿里云 AgentRun** 托管沙箱（`AgentRunFilesystemSpec`，见 §16）；不自建 K8s / Docker 沙箱。
- 不引入 Redis / Nacos / OSS（生产强化项，见 §14）。
- 不做 Agent 自学习闭环（`enableSkillManageTool` + Promotion Gate），留作二期。

---

## 2. 总体架构

```
┌────────────────────────────────────────────────────────────────────┐
│                    React 前端 (teapot-ai-web)                       │
│   登录页 │ Agent 管理台 │ 对话台(AG-UI/SSE) │ Skill 工坊 │ 用户管理    │
└───────────────┬────────────────────────────────┬───────────────────┘
                │ REST /api/**                   │ SSE /agui/**
┌───────────────▼────────────────────────────────▼───────────────────┐
│             Spring Boot 3.3.x 应用 (JDK 21, teapot-ai-server)       │
│                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────────┐  │
│  │ RBAC 模块     │  │ Agent 管理    │  │ AG-UI Spring Boot Starter │  │
│  │ JWT 登录/鉴权 │  │ Skill 管理    │  │ (SSE 事件流 + HITL 中断)   │  │
│  └──────────────┘  └──────┬───────┘  └───────────┬───────────────┘  │
│                           │                      │                  │
│                    ┌──────▼──────────────────────▼──────┐           │
│                    │      AgentRegistry（实例缓存）       │           │
│                    │   HarnessAgent × N（无状态，多用户）  │           │
│                    └──┬──────────────┬──────────────┬───┘           │
│                       │              │              │               │
│            MysqlAgentStateStore  MysqlSkillRepo  ModelRegistry      │
└───────────────────────┼──────────────┼──────────────┼───────────────┘
                        │              │              │ HTTPS
                ┌───────▼──────────────▼───┐    ┌─────▼─────────────┐
                │  MySQL 8.4 LTS（Docker，用户服务器）│    │  模型服务（DashScope │
                │  teapot_ai 业务库          │    │  / OpenAI 等）      │
                │  agentscope 状态+技能库    │    └───────────────────┘
                └──────────────────────────┘
```

**关键设计依据（来自 AgentScope Java v2 官方文档）**：

| 结论 | 出处 |
|------|------|
| `HarnessAgent` 为推荐入口，打包 workspace / 长期记忆 / 会话持久化 / subagent 等工程能力 | Quickstart |
| Agent 在 call 之间**无状态**，单实例可并发服务多用户，`RuntimeContext(userId, sessionId)` 隔离 | Quickstart "Multi-user concurrency" |
| 生产环境建议 `MysqlSkillRepository(writeable=false)` 平台侧集中治理，Agent 只读，写回走管理台 | Going to Production §4 |
| `agentscope-extensions-mysql` 提供 `MysqlAgentStateStore`，`createIfNotExist=true` 自动建库建表 | Integration: MySQL State Store |
| MySQL Skill Repository 提供完整 CRUD，管理台保存后 Agent 下一次推理立即生效 | Integration: MySQL Skill Repository |
| AG-UI Spring Boot Starter 自动注册 SSE 端点，支持 `X-Agent-Id` 头多 Agent 路由与 HITL 中断恢复 | Integration: AG-UI |

---

## 3. 技术栈与版本基线

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | **21**（LTS） | AgentScope Java 要求 ≥ 17 |
| Maven | ≥ 3.9 | 官方推荐 |
| Spring Boot | **3.5.x**（3.x 线最新稳定版，官方维护至 2026-11） | JDK 21 支持完善；Web MVC（非 WebFlux）；SB 4.x 基于 Spring Framework 7，存在破坏性变更，待 AgentScope 适配后二期评估（风险 12） |
| AgentScope Java | **2.0.1** | `io.agentscope:*` |
| MySQL | **8.4 LTS（Docker 部署，当前最新 8.4.11）**；本机原生 5.7.25 仅作备选 | 业务库 + AgentScope 库，选型论证见 §11.2 |
| MyBatis Spring Boot Starter | **3.0.x 最新补丁**（与 SB 3.5.x 配套） | 延续老 teapot 的 MyBatis 风格；配套关系：starter 3.0.x↔SB 3.x，starter 4.0.x↔SB 4.0，勿跨线混用 |
| 连接池 | HikariCP（Spring Boot 默认） | AgentScope 官方示例基于 Hikari `DataSource` |
| 认证 | JWT（auth0 java-jwt 4.x）+ BCrypt | 替代老 RBAC 的 HttpSession + MD5 |
| React | 18.x + TypeScript | Vite 5 构建 |
| 前端 UI | **AgentScope Spark Design**（`@agentscope-ai/design`，基于 Ant Design 5 封装） | 与 AgentScope 官方视觉体系对齐，见 §12 |
| 前端状态 | zustand | 轻量 |
| 前端对话 | **`@agentscope-ai/chat`**（Spark Chat：Bubble/Sender/Conversations/ChatAnywhere/AGUI 组件）+ AG-UI 事件流（SSE/fetch stream） | 对话台能力尽量复用 spark-chat；自研轻量 hook `useAguiRun` 作为鉴权头/事件适配的兜底 |

### 3.1 后端 Maven 依赖清单（核心）

> 以下依赖版本统一由 `teapot-ai-bom` 锁定（§4.1），业务模块声明时不写版本号；
> 片段中的 `${agentscope.version}` 仅用于展示 bom 内的版本属性。

```xml
<properties>
    <java.version>21</java.version>
    <agentscope.version>2.0.1</agentscope.version>
</properties>

<!-- AgentScope 核心（HarnessAgent，传递依赖 agentscope-core） -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<!-- 模型接入：一期同时接入 DashScope + OpenAI 双供应商 -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-dashscope</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<!-- MySQL 会话状态存储（MysqlAgentStateStore / JdbcStore） -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mysql</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<!-- MySQL Skill 仓库 -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-skill-mysql-repository</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<!-- AG-UI Spring Boot Starter（SSE 端点、多 Agent 路由、HITL） -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-agui-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<!-- JDBC 驱动（自带） -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
</dependency>
```

---

## 4. 仓库与模块结构

新仓库根目录：`d:\teamer\teapot-ai`；远程仓库（origin）：**`https://github.com/tanzzj/teapot-ai.git`**（默认分支 **main**；`deploy/` 目录已入 `.gitignore`，**不入库**——脚本仅存服务器 `/main/apps/teapot-ai/` 与本地工作区）。

```
teapot-ai/
├── SPEC.md                          # 本文档
├── deploy/                          # ⚠️ 不入库（.gitignore），仅存服务器/本地工作区
│   ├── teapot-ai.service            # systemd unit（§11.4）
│   ├── docker-compose-mysql.yml     # MySQL 8.4 LTS 容器编排（§11.3）
│   ├── nginx-teapot-ai.conf         # 站点配置片段（§11.5）
│   └── backup-mysql.sh              # mysqldump 定时备份（§11.6）
├── sql/
│   ├── V1__init_teapot_ai.sql       # 业务库初始化 DDL（§10）
│   └── V2__seed_data.sql            # 初始管理员 / 角色 / 示例 Agent
├── teapot-ai-server/                # Maven 多模块
│   ├── pom.xml                      # parent（模块聚合 + 构建插件；版本管理下沉到 bom）
│   ├── teapot-ai-bom/               # BOM：集中管理全部外部依赖版本（packaging=pom，§4.1）
│   ├── teapot-ai-common/            # Result、异常、工具类
│   ├── teapot-ai-rbac/              # 登录-用户-RBAC（演进自 teapot-rbac）
│   ├── teapot-ai-core/              # Agent / Skill 管理服务 + AgentRegistry（核心业务模块）
│   └── teapot-ai-start/             # Spring Boot 主类、AG-UI starter 装配、application 配置、fat jar 打包（§4.2）
└── teapot-ai-web/                   # React 前端（Vite + TS）
    ├── package.json
    └── src/
        ├── api/                     # REST 客户端
        ├── agui/                    # AG-UI SSE 事件 hook 与类型
        ├── pages/                   # Login / Agents / Chat / Skills / Users
        └── components/
```

> 命名约定：Java 包前缀 `com.teamer.teapot.ai.*`；表前缀 `t_`（延续老 teapot 风格）。

### 4.1 teapot-ai-bom（依赖版本中心）

- `packaging=pom`，**工程所有外部依赖的版本号只出现在此模块**；parent pom 通过
  `scope=import` 引入，各业务模块声明依赖一律**不带版本号**。
- `dependencyManagement` 收录范围（实施时以此为准补齐）：

```xml
<dependencyManagement><dependencies>
    <!-- 框架 BOM 引入 -->
    <dependency> <!-- org.springframework.boot:spring-boot-dependencies:${spring-boot.version}，scope=import --> </dependency>
    <!-- AgentScope 全家桶（统一 ${agentscope.version}=2.0.1） -->
    <dependency> <!-- io.agentscope:agentscope-harness --> </dependency>
    <dependency> <!-- io.agentscope:agentscope-extensions-model-dashscope --> </dependency>
    <dependency> <!-- io.agentscope:agentscope-extensions-model-openai --> </dependency>
    <dependency> <!-- io.agentscope:agentscope-extensions-mysql --> </dependency>
    <dependency> <!-- io.agentscope:agentscope-extensions-skill-mysql-repository --> </dependency>
    <dependency> <!-- io.agentscope:agentscope-agui-spring-boot-starter --> </dependency>
    <!-- 其余外部依赖 -->
    <dependency> <!-- org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.x --> </dependency>
    <dependency> <!-- com.auth0:java-jwt:4.x --> </dependency>
    <dependency> <!-- com.mysql:mysql-connector-j --> </dependency>
    <dependency> <!-- com.github.ben-manes.caffeine:caffeine --> </dependency>
</dependencies></dependencyManagement>
```

- 约束：新增/升级第三方依赖必须先在 bom 登记版本，PR 评审以此为准；
  内部模块（common/rbac/core/start）互引同样经 bom 管理版本，避免硬编码。

### 4.2 teapot-ai-start（启动与配置）

- 原独立的 teapot-ai-starter（配置模块）已并入本模块：同时承担 **Spring Boot 主类、
  AG-UI starter 装配、application 配置（`application.yml` / `-dev` / `-prod`，草案见 §13）、
  fat jar 打包**四项职责。
- `spring-boot-maven-plugin` repackage 产出 `app.jar`，与老 teapot 的 `teapot-start`
  部署形态一致（§11.4）。

---

## 5. 登录-用户体系（基于 teapot-rbac 演进）

### 5.1 继承与变更对照

| 维度 | 老 teapot-rbac | 新 teapot-ai-rbac |
|------|----------------|-------------------|
| Servlet 规范 | `javax.servlet` + `@WebFilter` | `jakarta.servlet` + `OncePerRequestFilter`（Spring Boot 3） |
| 会话机制 | HttpSession（`USER_PREFIX="user"`） | **JWT（无状态）**，Access Token 2h + Refresh Token 7d |
| 密码算法 | `MD5Util.encode(pwd, salt)` | **BCrypt**（随机盐，自带 salt） |
| 鉴权入口 | `RBACFilter`（全量请求过滤） | `RbacAuthFilter`（JWT 解析）+ `RbacAccessFilter`（资源-角色匹配，逻辑照搬） |
| 权限定义 | yml `rbac.resource-list`（roleId → resource[]） | **照搬**：配置驱动的 URI 通配匹配（`ValidationUtil.stringMatcher` 语义保留） |
| 用户模型 | `TeapotUser implements RBACUser` | **保留接口** `RBACUser` / `RBACRole`；`TeapotUser` 字段扩展（email/status） |
| 上下文 | `ContextUtil` ThreadLocal | **保留**，并新增对异步/Reactor 场景的传递支持（AG-UI SSE 长连接场景） |
| 用户表 | `t_portal_user (userId, username, password)` | `t_user`（字段兼容 + 扩展，见 §10.1） |

**保留的核心语义**（逐条对应老代码）：

1. `RBACLoginFilter` 语义 → 新 `POST /api/auth/login`：读取 username/password → BCrypt 校验 →
   签发 JWT；失败返回统一 `Result.fail("账号名或密码错误", "401")` 结构（沿用 `Result` 返回包装）。
2. `RBACFilter.isAuthenticated` 语义 → 资源-角色匹配算法原样保留：
   `resourceMap: Map<uriPattern, List<roleId>>`，用户角色与资源所需角色取交集，
   URI 用通配匹配（`/api/agent/*` 风格）。
3. `permitList` 白名单语义保留：`/api/auth/login`、`/actuator/health`、前端静态资源免鉴权。
4. `ContextUtil.setUp/cleanUp` 在 filter 链首尾执行，业务代码继续通过
   `ContextUtil.getUserFromContext()` 获取当前用户。

### 5.2 角色模型（一期：配置驱动，与老平台一致）

| roleId | 说明 | 资源（URI pattern） |
|--------|------|---------------------|
| `admin` | 平台管理员 | `/*`（含用户管理、模型配置） |
| `developer` | Agent/Skill 开发者 | `/api/agent/*`、`/api/skill/*`、`/agui/*`、`/api/user/profile` |
| `viewer` | 只读/对话用户 | `/api/agent/list`、`/api/agent/detail/*`、`/agui/*`、`/api/user/profile` |

yml 配置形态（与老 `rbac:` 前缀同构）：

```yaml
rbac:
  permit-list:
    - /api/auth/login
    - /api/auth/refresh
    - /actuator/health
  resource-list:
    - roleId: admin
      resource: /*
    - roleId: developer
      resource:
        - /api/agent/*
        - /api/skill/*
        - /api/user/profile
        - /agui/*
    - roleId: viewer
      resource:
        - /api/agent/list
        - /api/user/profile
        - /agui/*
  jwt:
    secret: ${RBAC_JWT_SECRET}        # 环境变量注入，不入库不入 git
    access-token-ttl: PT2H
    refresh-token-ttl: P7D
```

> 二期可选：角色-资源迁移到数据库（`t_role` + `t_role_resource`）实现动态权限。一期保持配置驱动，
> 与老平台运维习惯一致，降低建设成本。

### 5.3 认证流程

```
登录:  POST /api/auth/login {username, password}
       → BCrypt 校验 t_user.password → 签发 {accessToken, refreshToken, user}
请求:  Authorization: Bearer <accessToken>
       → RbacAuthFilter 解析 JWT → 组装 TeapotUser(含 roleList) → ContextUtil.setUp
续期:  POST /api/auth/refresh {refreshToken} → 新 accessToken
登出:  POST /api/auth/logout（客户端丢弃 token；一期不做服务端吊销，二期可加黑名单表）
```

**AG-UI 通道鉴权**：SSE 端点 `/agui/**` 同样走 JWT（前端用 `fetch` + 流式读取而非原生
`EventSource`，因为后者无法携带 `Authorization` 头）。`RuntimeContext.userId` **强制取自已认证
的 JWT 用户**（通过自定义 `AguiRuntimeContextResolver` Bean 实现），**禁止**信任前端
`forwardedProps` 里的身份字段——这是 AG-UI 官方文档的明确安全指引。

### 5.4 RBAC REST API

| Method | Path | 说明 | 权限 |
|--------|------|------|------|
| POST | `/api/auth/login` | 登录 | 公开 |
| POST | `/api/auth/refresh` | 刷新 token | 公开 |
| POST | `/api/auth/logout` | 登出 | 登录 |
| GET | `/api/user/profile` | 当前用户信息 | 登录 |
| GET | `/api/user/list` | 用户列表（分页） | admin |
| POST | `/api/user/create` | 创建用户（BCrypt 存密） | admin |
| PUT | `/api/user/{userId}` | 修改用户（角色/状态/重置密码） | admin |
| DELETE | `/api/user/{userId}` | 停用/删除用户 | admin |

---

## 6. AgentScope 2.0.1 核心运行时设计

### 6.1 AgentRegistry（平台核心组件）

平台管理的每个 Agent（`t_agent` 记录）对应一个**进程内 `HarnessAgent` 实例**：

```java
// 伪代码 — 构建逻辑
HarnessAgent agent = HarnessAgent.builder()
        .name(agentDO.getAgentKey())                       // 全局唯一 key
        .sysPrompt(agentDO.getSysPrompt())                 // 人设（平台可编辑）
        .model(agentDO.getModelId())                       // 如 "dashscope:qwen-plus"，ModelRegistry 解析
        .workspace(workspaceRoot.resolve(agentDO.getAgentKey()))  // 每 agent 独立 workspace
        .skillRepository(mysqlSkillRepository)             // 平台级 MySQL skill 市场（只读）
        .compaction(CompactionConfig.builder()
                .triggerMessages(agentDO.getCompactionTrigger())   // 默认 30
                .keepMessages(agentDO.getCompactionKeep())         // 默认 10
                .build())
        .stateStore(mysqlAgentStateStore)                  // MySQL 会话状态持久化
        .build();
```

设计要点：

1. **实例缓存**：`ConcurrentHashMap<agentKey, HarnessAgent>` + Caffeine 容量上限；
   Agent 配置变更（编辑/删除/绑定 skill）时**失效重建**（AgentScope 动态 skill 合并保证
   skill 变更无需重建，但 sysPrompt/model 变更需要重建）。
2. **无状态并发**：同一实例服务全部用户；`RuntimeContext.builder().userId(teapotUserId)
   .sessionId(aguiThreadId).build()` 实现 `(userId, sessionId)` 级隔离，同 session 自动串行。
3. **skill 生效机制**：`MysqlSkillRepository` 为 Layer-2 市场层，动态合并（默认每次推理前重合并），
   平台管理台保存 skill 后 Agent **下一轮对话即生效**，无需重建实例——这是"配置化建设 skill"
   的技术基础。
4. **Agent 可见 skill 范围**：一期所有 Agent 共享平台 skill 市场全集；
   "Agent↔Skill 绑定关系"（`t_agent_skill`）通过平台侧包装实现：为每个 Agent 构建时注册
   **过滤后的 Repository 视图**（只暴露 `t_agent_skill` 中绑定的 skill name 集合）。
   > 若 AgentScope 2.0.1 未提供 repository 视图过滤钩子，降级方案：
   > 每 Agent 独立的 `MysqlSkillRepository` 自定义表（`t_skill_<agentKey>` 风格不推荐），
   > 或在 AG-UI 请求前置 middleware 里限制——实施阶段验证后二选一，默认走视图过滤。

### 6.2 状态存储（MysqlAgentStateStore）

```java
HikariDataSource ds = ...; // 与业务库不同，指向 agentscope 库
AgentStateStore stateStore = new MysqlAgentStateStore(ds, /*createIfNotExist*/ true);
```

- 自动建表 `agentscope_sessions(session_id, state_key, item_index, state_data, ...)`；
  `session_id` 编码为 `{userId}:{sessionId}`。
- 作用：会话历史、压缩摘要、权限规则、Plan Mode 状态、工具状态的跨进程持久化；
  应用重启 / 多实例部署后同一 `(userId, sessionId)` 可无缝续聊。
- 一期单实例部署即可；多副本时 MySQL state store 原生支持（无状态引擎 + 共享存储）。

### 6.3 AG-UI 接入（前后端对话协议）

启用 `agentscope-agui-spring-boot-starter`，配置：

```yaml
agentscope:
  agui:
    path-prefix: /agui
    cors-enabled: true
    run-timeout: 30m
    sse-timeout: 1800000
    enable-path-routing: false
    agent-id-header: X-Agent-Id        # 前端请求头携带目标 agent key
    emit-state-events: true
    emit-tool-call-args: true
    emit-token-usage: true
    enable-reasoning: true             # 输出思考过程（REASONING_MESSAGE_*）
    server-side-memory: false          # 记忆由 HarnessAgent 管理，不用 starter 侧内存
```

- 多 Agent 路由：请求头 `X-Agent-Id: <agentKey>` → 平台自定义 `AguiAgentAdapterFactory` /
  `AguiRuntimeContextResolver` Bean 从 **AgentRegistry** 取对应 HarnessAgent，
  并注入已认证的 `userId`（§5.3）。
- 事件流：`RUN_STARTED → TEXT_MESSAGE_* / REASONING_MESSAGE_* / TOOL_CALL_* →
  RUN_FINISHED`（或 `RUN_ERROR`），前端按事件类型渲染气泡、思考面板、工具调用卡片。
- HITL（一期预留、二期启用）：权限确认中断 → `RUN_FINISHED.outcome.interrupts[]` →
  前端审批卡 → 下次 run 携带 `resume[]`。

### 6.4 模型接入（一期同时接入 DashScope + OpenAI 双供应商）

- **DashScope**（通义千问系）：环境变量 `DASHSCOPE_API_KEY`，Agent 的 `modelId` 形如 `dashscope:qwen-plus`。
- **OpenAI**：环境变量 `OPENAI_API_KEY`（另支持可选 `OPENAI_BASE_URL`，兼容代理/兼容端点），
  `modelId` 形如 `openai:gpt-5.2`；依赖已在 §3.1 声明。
- **模型入口界面配置化**（修订）：模型清单不再写死在配置文件，而是存于 `t_model_entry` 表
  （provider / model_name / display_name / base_url / status），admin 在管理台 `/models` 页 CRUD；
  `ModelRegistry` 按 `provider:model` 解析时读取入口配置（OpenAI 的入口 baseUrl 优先于环境变量）；
  yml `teapot.ai.model-presets` 退化为兜底（仅当 DB 无启用入口时生效）。
- API Key **只存在于服务器环境变量**；`t_agent` 与 `t_model_entry` 只存模型标识/baseUrl，不落任何密钥。
- GLM / Kimi / Ollama 等留作二期追加（仅需换依赖 + 环境变量，`ModelRegistry` 机制天然支持）。

---

## 7. Agent 管理模块

### 7.1 REST API

| Method | Path | 说明 | 权限 |
|--------|------|------|------|
| GET | `/api/agent/list` | Agent 列表（分页/搜索） | 登录 |
| GET | `/api/agent/detail/{agentKey}` | Agent 详情（含绑定 skill） | 登录 |
| POST | `/api/agent/create` | 新增 Agent | developer |
| PUT | `/api/agent/update/{agentKey}` | 修改（触发 Registry 重建） | developer |
| DELETE | `/api/agent/delete/{agentKey}` | 删除（软删） | developer |
| POST | `/api/agent/bindSkill/{agentKey}` | 绑定 skill（body: skillName） | developer |
| POST | `/api/agent/unbindSkill/{agentKey}` | 解绑 skill | developer |
| POST | `/api/agent/chat/{agentKey}` | （可选）非 AG-UI 同步对话，调试用 | developer |

`create` 请求体示例：

```json
{
  "agentKey": "code-reviewer",
  "name": "代码评审助手",
  "description": "负责团队代码评审",
  "sysPrompt": "你是一名资深 Java 评审员……",
  "modelId": "dashscope:qwen-plus",
  "compactionTrigger": 30,
  "compactionKeep": 10,
  "skillNames": ["code-reviewer", "sql-helper"],
  "feature": { "sandbox": { "enabled": false } }
}
```

`feature` 为通用扩展字段（JSON），一期仅 `sandbox` 命名空间，结构与校验规则见 §16.6；
`update` 请求携带 `feature` 时为整体替换语义。

### 7.2 关键流程

1. 新增 Agent：校验 `agentKey` 唯一（`^[a-z][a-z0-9-]{2,31}$`）→ 写 `t_agent` + `t_agent_skill`
   → 在 workspace 根目录下生成该 agent 的 `AGENTS.md`（内容 = sysPrompt，作为 workspace 人设种子）。
2. 修改/删除：写库 → `AgentRegistry.invalidate(agentKey)`；携带 `feature` 时先按 §16.6 规则强校验
   （枚举/范围/前缀/全局接入状态），不合法直接拒绝不写库。
3. 对话：前端携 `X-Agent-Id` 走 `/agui/**`，`AgentRegistry` 惰性构建实例。

---

## 8. Skill 管理模块（配置化建设一切 skill）

### 8.1 存储：MySQL Skill Repository

直接复用 AgentScope 官方 `agentscope-extensions-skill-mysql-repository`，
自动建表（`createIfNotExist=true`）：

- `agentscope_skills(id, name UNIQUE, description, skill_content, source, metadata_json, ...)`
  — `skill_content` 存完整 `SKILL.md`。
- `agentscope_skill_resources(id, resource_path, resource_content, ...)` — 附件（references/、scripts/），
  按 skill id 级联删除。

平台侧持有 `writeable=true` 的管理实例用于 CRUD；**注入 Agent 的实例 `writeable=false`**
（官方生产清单：Agent 只读，写回走管理台）。

Git 仓库为第二 skill 来源（Git PR 流程管控版本，详见 §15）。

### 8.2 配置化 Skill 工坊（核心产品能力）

"通过配置建设一切 skill" = 用户**不写 SKILL.md 文件**，用表单 + 编辑器生产 skill：

| 表单字段 | 映射到 SKILL.md |
|----------|-----------------|
| 名称 `name` | frontmatter `name:`（全局唯一） |
| 触发描述 `description` | frontmatter `description:`（决定 Agent 何时加载该 skill，界面给出写作指引） |
| 指令正文 `instructions` | markdown body（Monaco 编辑器，支持变量提示） |
| 参考文档列表 `references[]` | `references/<文件名>` → `agentscope_skill_resources` |
| 脚本列表 `scripts[]` | `scripts/<文件名>` → `agentscope_skill_resources`（提示：脚本执行依赖 shell/sandbox；AgentRun 沙箱启用后（§16）可在沙箱内执行，落盘方式见 §16.12） |

后端职责：把表单序列化为标准 `SKILL.md`（frontmatter + body），调用
`MysqlSkillRepository.save(List.of(skill), overwrite=true)`；删除调用 `repo.delete(name)`。
**Agent 下一轮推理自动看到新 skill**（动态合并），无需重启。

### 8.3 REST API

| Method | Path | 说明 | 权限 |
|--------|------|------|------|
| GET | `/api/skill/list` | skill 列表（name/description/更新时间） | 登录 |
| GET | `/api/skill/detail/{name}` | 详情（SKILL.md 解析回表单结构 + 资源清单） | 登录 |
| POST | `/api/skill/save` | 新建/更新（upsert） | developer |
| DELETE | `/api/skill/delete/{name}` | 删除（级联资源；同时解绑所有 agent） | developer |
| POST | `/api/skill/preview` | 预览生成的 SKILL.md（不落库） | developer |

### 8.4 与 AgentScope 四层 skill 模型的对位

| 层 | 平台落地 |
|----|----------|
| L1 项目全局目录 | 不使用 |
| L2 市场（Marketplace） | **MysqlSkillRepository（平台主战场，配置化 CRUD）** + GitSkillRepository（Git 管控来源，见 §15） |
| L3 workspace 共享 | 各 agent workspace 预留 `skills/`，用于运维手工放置紧急 skill |
| L4 用户级 | 一期不使用；二期"个人 skill"可用 `<userId>/skills/` 覆盖 |

同名冲突优先级：L4 > L3 > L2 > L1（官方规则），平台 UI 在保存时做重名提示。

---

## 9. 会话管理（补充能力）

- 会话 = `(userId, sessionId)`；前端对话台为每个 Agent 维护多会话列表。
- 一期会话列表存业务库 `t_chat_session(id, user_id, agent_key, session_id, title, ...)`；
  消息体不落业务库（以 `agentscope_sessions` 为唯一事实源，避免双写）。
- 提供"清空会话"：调用 `stateStore.delete(userId, sessionId)` + 删 `t_chat_session` 记录。

---

## 10. 数据库设计

### 10.1 业务库 `teapot_ai`（DDL）

```sql
-- 用户表（兼容老 t_portal_user 语义，字段扩展）
CREATE TABLE t_user (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id      VARCHAR(64)  NOT NULL COMMENT '业务用户ID',
  username     VARCHAR(64)  NOT NULL COMMENT '登录名',
  password     VARCHAR(100) NOT NULL COMMENT 'BCrypt hash',
  real_name    VARCHAR(64)  NULL,
  mobile       VARCHAR(20)  NULL,
  email        VARCHAR(128) NULL,
  roles        VARCHAR(255) NOT NULL DEFAULT 'viewer' COMMENT '逗号分隔 roleId: admin,developer,viewer',
  status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  UNIQUE KEY uk_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台用户';

-- Agent 定义表
CREATE TABLE t_agent (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  agent_key           VARCHAR(32)  NOT NULL COMMENT '全局唯一，AG-UI 路由键',
  name                VARCHAR(64)  NOT NULL,
  description         VARCHAR(512) NULL,
  sys_prompt          TEXT         NOT NULL,
  model_id            VARCHAR(64)  NOT NULL DEFAULT 'dashscope:qwen-plus' COMMENT 'provider:model',
  compaction_trigger  INT          NOT NULL DEFAULT 30,
  compaction_keep     INT          NOT NULL DEFAULT 10,
  feature             JSON         NULL COMMENT '扩展功能配置(JSON)：sandbox 等，SPEC §16.6（存量升级走 V4__agent_feature.sql）',
  status              TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_by          VARCHAR(64)  NOT NULL,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_key (agent_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent定义';

-- Agent-Skill 绑定表
CREATE TABLE t_agent_skill (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  agent_key   VARCHAR(32)  NOT NULL,
  skill_name  VARCHAR(255) NOT NULL,
  created_by  VARCHAR(64)  NOT NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_skill (agent_key, skill_name),
  KEY idx_skill_name (skill_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent-Skill绑定';

-- 会话索引表（消息体在 agentscope 库）
CREATE TABLE t_chat_session (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     VARCHAR(64)  NOT NULL,
  agent_key   VARCHAR(32)  NOT NULL,
  session_id  VARCHAR(128) NOT NULL COMMENT 'AG-UI threadId',
  title       VARCHAR(128) NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_session (user_id, session_id),
  KEY idx_user_agent (user_id, agent_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话索引';

-- 系统配置表（含加密凭证，SPEC §16.5.1，存量升级走 V5__sys_config.sql）
CREATE TABLE t_sys_config (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  config_key   VARCHAR(64)  NOT NULL COMMENT 'agentrun.api_key / agentrun.account_id / agentrun.region / …',
  config_value TEXT         NOT NULL COMMENT '敏感项存 AES-GCM 密文 v<keyVer>:<base64(iv+ciphertext+tag)>',
  key_version  TINYINT      NOT NULL DEFAULT 1 COMMENT '主密钥版本，轮换用',
  encrypted    TINYINT      NOT NULL DEFAULT 0 COMMENT '1密文 0明文',
  updated_by   VARCHAR(64)  NOT NULL,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置（含加密凭证）';
```

### 10.2 AgentScope 库 `agentscope`（框架自动建表，DDL 列此供审阅/备份参考，勿手工改）

建表由框架 `createIfNotExist=true` 自动完成；以下 DDL 摘自 AgentScope 2.0.1 官方文档，
已逐条核对 **MySQL 8.4 兼容**（无 8.0 专有语法，8.4 全量向下兼容）：

```sql
-- 会话状态表（MysqlAgentStateStore）
-- session_id 编码为 {userId}:{sessionId}；列表型状态按 item_index 多行存储，
-- 另有 state_key='xxx:_hash' 行用于变更检测
CREATE TABLE IF NOT EXISTS agentscope_sessions (
    session_id VARCHAR(255) NOT NULL,
    state_key  VARCHAR(255) NOT NULL,
    item_index INT NOT NULL DEFAULT 0,
    state_data LONGTEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Skill 主体表（MysqlSkillRepository）；skill_content 存完整 SKILL.md；name 全局唯一
CREATE TABLE IF NOT EXISTS agentscope_skills (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT NOT NULL,
    skill_content LONGTEXT NOT NULL,
    source VARCHAR(255) NOT NULL,
    metadata_json LONGTEXT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Skill 附件资源表（references/、scripts/ 等），按 id 级联删除
CREATE TABLE IF NOT EXISTS agentscope_skill_resources (
    id BIGINT NOT NULL,
    resource_path VARCHAR(500) NOT NULL,
    resource_content LONGTEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id, resource_path),
    FOREIGN KEY (id) REFERENCES agentscope_skills(id) ON DELETE CASCADE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 10.3 审计表（一期先建表，埋点写入随 M3 落地）

```sql
CREATE TABLE t_audit_log (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     VARCHAR(64)  NOT NULL COMMENT '操作人',
  action      VARCHAR(64)  NOT NULL COMMENT '如 agent.create / skill.save / user.reset_password',
  target      VARCHAR(255) NULL COMMENT '操作对象标识',
  detail      TEXT         NULL COMMENT 'JSON 摘要（脱敏）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, created_at),
  KEY idx_action_time (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作审计';
```

### 10.4 种子数据

- 管理员账号：`admin` / 初始密码部署时生成（BCrypt），首次登录强制改密（`t_user.status` 语义或单独标记位，实施时确定）。
- 示例 Agent：`general-assistant`（绑定 0 个 skill），用于冒烟。
- 示例 Skill：`meeting-notes`（会议纪要），验证 L2 市场链路。

---

## 11. 服务器部署方案（基于 2026-08-12 实测盘点）

### 11.1 服务器现状盘点（server-ops skill 实测，只读采集）

连接方式：server-ops skill 统一入口 `_ssh.ps1`；主机 `114.116.14.26`。

| 项 | 实测值 | 对本项目的影响 |
|---|---|---|
| OS | CentOS 7 (Core) | 兼容 JDK 21 二进制部署 |
| 配置 | 2 vCPU / 3.7G RAM（可用约 2.7G） | 新应用堆上限建议 ≤ 1G |
| 磁盘 | 40G 已用 49%（**2026-08-12 清理 Docker 镜像/卷后可用 20G**，此前 88%） | ✅ 磁盘恢复健康水位，可承载 Docker MySQL 及其数据卷 |
| Docker | 26.1.4（清理后无运行容器；镜像 26 个 → 4 个） | **本期用于部署 MySQL 8.4 LTS 容器** |
| **MySQL** | **原生 5.7.25 运行中**（systemd enabled），监听 `:::3306`，datadir 仅 204M | 降级为备选（方案 B）；待确认无业务依赖后停用，由 Docker MySQL 8.4 LTS 接管（§11.2） |
| **JDK** | **Temurin JDK 21.0.12 已安装**：`/opt/rising-sun/jdk21` | 无需安装 JDK，直接复用 |
| nginx | 1.16.1 源码编译，`/main/main-nginx`，监听 80/443/18080；已有 teapot/dify/nacos/tsp 等十余个 `*.teamer.com.cn` server 块 | 新站点沿用同一改配流程（server-ops skill §nginx） |
| 既有应用 | `/main/apps`：teapot（jar+ui，当前未运行）、dify（compose，1.8G）、nacos、seata、yapi、tsp、kind、sampler、clash-for-linux | 端口避让；部署目录风格对齐 |
| 8082 | `/opt/rising-sun/app.jar`（JDK21 运行中，`-Xms256m -Xmx1024m`） | 应用部署形态直接对齐它 |
| 老 teapot DB | jar 内配置指向**另一台远程 MySQL**，非本机实例 | 新老平台数据库无耦合，互不干扰 |
| 本机 MySQL root | 空密码登录被拒（ERROR 1045） | **建库建号需用户提供凭证**（M0 前置） |
| Redis | Docker 容器暴露 6379（0.0.0.0） | 一期不使用；二期可作 token 吊销/状态存储 |

### 11.2 MySQL 方案选型

| 方案 | 内容 | 结论 |
|---|---|---|
| **A（推荐）** | Docker 部署 **MySQL 8.4 LTS**（当前最新 8.4.11，长期支持版）：映射宿主机 3306，数据卷落 `/main/mysql84/` | 磁盘清理后可用 20G，约束解除；8.4 完全覆盖所需 DDL（utf8mb4 / DATETIME 默认值 / LONGTEXT / ON UPDATE / 外键级联），且与 AgentScope 官方示例（基于 8.x）兼容性最好；LTS 支持周期长，适合长期运行的平台 |
| B（备选） | 复用本机原生 MySQL 5.7.25 | 零新增服务；仅当方案 A 的 8.4 兼容性验证失败、或确认原生 5.7 仍被其他服务依赖不允许停用时启用 |

前置动作（M0 首日完成）：
1. **停用依赖核查**：确认本机 5.7.25（datadir 仅 204M）无任何业务连接（老 teapot jar 指向远程 MySQL，不受影响）→ `systemctl stop mysqld && systemctl disable mysqld` 让出 3306；
2. **兼容性验证**：用最小 demo 工程引入 `agentscope-extensions-mysql` 与
   `agentscope-extensions-skill-mysql-repository`，`createIfNotExist=true` 启动一次，
   确认 8.4 下自动建库建表 + 读写往返成功。失败则降级方案 B。

### 11.3 库与账号初始化（方案 A 实施清单）

容器部署（服务器侧 `deploy/docker-compose-mysql.yml`，不入库；要点等价于）：

```bash
docker run -d --name teapot-mysql --restart unless-stopped \
  -p 3306:3306 \
  -v /main/mysql84/data:/var/lib/mysql \
  -e MYSQL_ROOT_PASSWORD='<root 强密码，仅存 app.env，不入仓>' \
  -e TZ=Asia/Shanghai \
  mysql:8.4 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
```

> 内存限额：容器不另设 `--memory` 硬限（服务器可用内存约 2.7G），innodb_buffer_pool_size
> 建议 512M，经启动参数追加；观察一周后再调。

容器健康后执行建库建号：

```sql
CREATE DATABASE teapot_ai  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE agentscope DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER 'teapot_ai'@'localhost' IDENTIFIED BY '<强密码>';
CREATE USER 'teapot_ai'@'127.0.0.1' IDENTIFIED BY '<强密码>';
-- 宿主机经 127.0.0.1:3306 映射进容器时，MySQL 侧来源为 docker 网桥 IP（M0 实测，必须覆盖）
CREATE USER 'teapot_ai'@'172.17.0.1' IDENTIFIED BY '<强密码>';
-- 注：REFERENCES 为 MysqlSkillRepository 自动建外键所需（M0 实测）
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES ON teapot_ai.*  TO 'teapot_ai'@'localhost';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES ON teapot_ai.*  TO 'teapot_ai'@'127.0.0.1';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES ON teapot_ai.*  TO 'teapot_ai'@'172.17.0.1';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES ON agentscope.* TO 'teapot_ai'@'localhost';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES ON agentscope.* TO 'teapot_ai'@'127.0.0.1';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES ON agentscope.* TO 'teapot_ai'@'172.17.0.1';
FLUSH PRIVILEGES;
```

- 应用与 DB 同机，账号仅绑定 localhost/127.0.0.1；3306 不对公网开放（**M0 检查项**：确认云安全组/iptables
  未放行 3306，因容器映射的是全部接口）。
- 数据卷 `/main/mysql84/` 纳入 §11.6 备份范围；旧 5.7 的 datadir（仅 204M）确认无用后择期清理。
- 初始化脚本入仓：`sql/V1__init_teapot_ai.sql`（§10 DDL）、`sql/V2__seed_data.sql`（§10.4）。

### 11.4 应用部署（对齐服务器既有形态）

- 目录：`/main/apps/teapot-ai/`：`app.jar`、`application-prod.yml`、`app.env`、`logs/`、`workspace/`、`ui/`
- JDK：复用 `/opt/rising-sun/jdk21`（Temurin 21.0.12）
- 进程管理：systemd unit `teapot-ai.service`（服务器侧 `deploy/`，不入库），要点：
  - `EnvironmentFile=/main/apps/teapot-ai/app.env` 注入 `TEAPOT_AI_DB_PASSWORD` /
    `RBAC_JWT_SECRET` / `DASHSCOPE_API_KEY` / `OPENAI_API_KEY` / `AGENTSCOPE_WORKSPACE`
  - `ExecStart=/opt/rising-sun/jdk21/bin/java -Xms256m -Xmx1024m -jar /main/apps/teapot-ai/app.jar --spring.profiles.active=prod`
  - `Restart=on-failure`，日志走 journald + 应用内 logback 滚动文件
- 发布流程：`mvn package` → `_ssh.ps1 put` 上传 jar → `systemctl restart teapot-ai`
  → `curl 127.0.0.1:9126/actuator/health` 冒烟

### 11.5 nginx 站点（teapot.teamer.com.cn）

沿用 server-ops skill 改配流程（备份 → sed 打补丁 → `nginx -t -c` → `nginx -s reload -c`，
禁止 systemctl reload）：

```nginx
server {
    listen 80;
    server_name teapot.teamer.com.cn;

    root /main/apps/teapot-ai/ui;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;    # SPA 回退
    }
    location /api/ {
        proxy_pass http://127.0.0.1:9126;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
    location /agui/ {                         # SSE 长连接，必须关闭缓冲
        proxy_pass http://127.0.0.1:9126;
        proxy_http_version 1.1;
        proxy_set_header Connection '';
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
        proxy_set_header Host $host;
    }
}
```

- HTTPS：后续按 server-ops skill 证书流程加 `listen 443 ssl` + `/main/main-nginx/cert-teapot-ai/`（证书来源待确认，一期可先 80 上线）。
- ⚠️ 验证必须用域名 `teapot.teamer.com.cn`，裸 IP 命中默认站点会误判 502/404（server-ops 已知坑）。

### 11.6 备份与运维

- 备份：crontab 每日 03:00 `mysqldump` 导出 `teapot_ai`、`agentscope` 两库 →
  `/main/backup/teapot-ai/YYYY-MM-DD.sql`，保留 7 份（脚本为服务器侧 `deploy/backup-mysql.sh`，不入库）；
  **磁盘水位超过 90% 时优先清理备份与日志**。
- 日志：logback rolling（单文件 ≤ 100M，保留 7 天）。
- 巡检：一期手工（`ss -lntp` / `ps` / `df -h`）；二期再评估可观测性（OtelTracingMiddleware 等）。

### 11.7 环境判定约定与 qodercli（2026-08-12 配置）

- **环境判定约定：若当前处于 Linux 环境，即可认为当前就在服务器（114.116.14.26）上，构建与部署均可直接在服务器上执行**——无需"本地构建 + 上传产物"，可直接在本机 `mvn package`、改 nginx、`systemctl restart teapot-ai`。
- 服务器已安装 Qoder CLI：v1.1.20，入口 `/root/.local/bin/qodercli`（安装命令 `curl -fsSL https://qoder.com/install | bash`，PATH 已写入 `/root/.bashrc`）。
- 本 Spec 与 skill 已同步给服务器上的 qodercli：
  - 本文件存于服务器 `/main/apps/teapot-ai/SPEC.md`（项目工作目录）；
  - server-ops skill 存于 `/root/.qoder/skills/server-ops/SKILL.md`（qodercli 全局 skill）。
- 注意：Linux 环境下对自身的操作不再经由 `_ssh.ps1` 远程入口，直接本地 bash 执行即可；涉及 systemd 服务（teapot-ai.service）与 `/main/main-nginx` 的操作规范仍遵循 §11.4/§11.5 与 server-ops skill。
- **qodercli 守护进程（2026-08-13 部署）**：systemd 服务 `qoder-remote.service`（unit 为服务器侧 `deploy/qoder-remote.service`，不入库）常驻运行 `qodercli remote-control --name 114-teapot-ai --directory /main/apps/teapot-ai --capacity 4`，开机自启、异常退出 5s 后重拉；可随时从 Qoder 移动端 / https://qoder.com/agents 向服务器下发任务（并发会话上限 4，受内存约束），运维命令：`systemctl restart|status qoder-remote`、`journalctl -u qoder-remote -f`。

---

## 12. 前端设计（teapot-ai-web，基于 AgentScope Spark Design）

### 12.1 技术选型

UI 体系采用 AgentScope 官方 **Spark Design**（[agentscope-spark-design](https://github.com/agentscope-ai/agentscope-spark-design)，阿里云飞天实验室 UI 组件库，MIT / Apache-2.0 双包授权）：

| 包 | 职责 |
|----|------|
| `@agentscope-ai/design` | 核心设计系统：基于 Ant Design 5 的主题定制与增强组件（Button/Modal/Select 等）、自定义图标（`@agentscope-ai/icons`）、i18n |
| `@agentscope-ai/chat` | LLM 对话组件库：**AGUI 组件、Bubble（消息气泡）、Sender（输入发送）、Conversations（会话列表）、ChatAnywhere（开箱即用聊天容器）、Markdown（公式+代码高亮）、Mermaid**，支持流式响应 |

| 项 | 选择 | 说明 |
|----|------|------|
| 脚手架 | Vite 5 + React 18 + TypeScript 5 | 快速冷启动，产物可静态部署 |
| UI | **`@agentscope-ai/design`**（Spark Design） | 基于 Ant Design 5 封装，管理台表格/表单/布局直接用；主题跟随 Spark 默认 |
| 样式方案 | Tailwind CSS + antd-style | 与 Spark Design 技术栈对齐 |
| 包管理 | pnpm | Spark Design 生态推荐 |
| 路由 | react-router 6 | 路由守卫对接 JWT |
| 状态 | zustand | auth store / chat store |
| HTTP | axios + 拦截器 | 401 自动 refresh，统一 `Result` 解包 |
| 编辑器 | Monaco Editor | SKILL.md 指令正文 / sysPrompt 编辑 |
| 对话渲染 | **`@agentscope-ai/chat`**：Bubble + Markdown（内置代码高亮/公式） | 替代原 react-markdown 自组合方案 |
| 对话流接入 | 优先 spark-chat 的 **AGUI 组件 / ChatAnywhere**；若其鉴权头注入或事件映射不满足需求，降级自研 `useAguiRun` hook（`fetch` + ReadableStream 解析 SSE） | `EventSource` 无法带 Authorization 头，流式接入必须走 fetch 流或组件内置客户端 |

**Spark Design 范式落地（实施约定）**：应用根部用 Spark `ConfigProvider` 包裹并注入官方 **百炼主题**
（`@agentscope-ai/design/lib/antd/themes/bailianTheme.json` 作为 `theme.token`，紫色主色 #615CED）；页面交互组件
（Button/Card/Modal/Form/Input/Select/Table/Tag/Switch/Popconfirm/Breadcrumb/Avatar/message 等）一律从
`@agentscope-ai/design` 导入，不用裸 antd；仅 design 未导出的布局件（Layout/Menu/Space/Row/Col/Spin）保留 antd
（经 ConfigProvider 继承同一主题 token）。

> ⚠️ 实施前置验证（M2 启动前）：spark-chat 尚年轻，需验证两点：① 包是否已发布到 npm 及版本稳定性
> （未发布则改用 git 依赖或源码引入子包）；② AGUI 组件对自定义 `Authorization` 头 / `X-Agent-Id`
> 头的支持程度——不满足则对话流走自研 `useAguiRun`，仅复用 Bubble/Sender/Conversations 等展示组件。

### 12.2 页面清单

| 路由 | 页面 | 说明 |
|------|------|------|
| `/login` | 登录 | username/password |
| `/agents` | Agent 列表 | 卡片/表格、搜索、新建入口（Spark Design 组件） |
| `/agents/:agentKey` | Agent 配置 | sysPrompt、模型选择（下拉来自 `/api/model/presets`，即 t_model_entry 启用入口）、压缩参数、**skill 绑定穿梭框** |
| `/chat/:agentKey` | 对话台 | 多会话侧栏（**Conversations**）、流式气泡（**Bubble**）+ 输入区（**Sender**）、思考过程折叠面板、工具调用卡片、token 用量角标 |
| `/skills` | Skill 市场 | 列表、删除、查看使用它的 agent |
| `/skills/:name` | Skill 工坊 | 配置表单（§8.2）+ SKILL.md 实时预览 + 资源文件上传 |
| `/models` | 模型入口管理 | admin 专属：t_model_entry CRUD（§6.4 修订） |
| `/users` | 用户管理 | admin 专属 |

### 12.3 AG-UI 事件渲染映射

| AG-UI 事件 | UI 行为 |
|------------|---------|
| `RUN_STARTED` | 进入"思考中"态 |
| `TEXT_MESSAGE_CONTENT` | 追加当前 assistant 气泡文本（Bubble + Markdown 渲染） |
| `REASONING_MESSAGE_CONTENT` | 追加思考面板（可折叠） |
| `TOOL_CALL_START/ARGS/END` | 渲染工具调用卡片（名称 + 参数） |
| `TOOL_CALL_RESULT` | 卡片内回填结果 |
| `CUSTOM(name=token_usage)` | 更新用量角标 |
| `RUN_FINISHED` / `RUN_ERROR` | 收尾 / 错误 toast |
| `outcome.interrupts[]`（二期） | 审批卡片 → `resume[]` |

### 12.4 开发联调

- Vite dev server 端口 5173，`/api` 与 `/agui` proxy 到后端 `http://localhost:9126`。
- 生产：`vite build` 产物上传 `/main/apps/teapot-ai/ui/`，由服务器既有 nginx 托管（§11.5）。

---

## 13. 后端配置（application.yml 草案，存放于 teapot-ai-start 模块）

```yaml
server:
  port: 9126
  servlet:
    encoding: { charset: UTF-8, force: true }

spring:
  application: { name: teapot-ai }
  datasource:                                    # 业务库（Docker MySQL 8.4 LTS 映射宿主机 3306）
    # 注：8.4 默认 caching_sha2_password，非 SSL 连接必须加 allowPublicKeyRetrieval=true（M0 实测踩坑）
    url: jdbc:mysql://127.0.0.1:3306/teapot_ai?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true&zeroDateTimeBehavior=CONVERT_TO_NULL
    username: teapot_ai
    password: ${TEAPOT_AI_DB_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver

mybatis:
  mapper-locations: classpath*:sqlclient/*.xml
  configuration: { jdbc-type-for-null: null }

teapot:
  ai:
    agentscope:
      datasource:                                # agentscope 库（独立连接池）
        url: jdbc:mysql://127.0.0.1:3306/agentscope?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true
        username: teapot_ai
        password: ${TEAPOT_AI_DB_PASSWORD}
      workspace-root: ${AGENTSCOPE_WORKSPACE:/data/teapot-ai/workspace}
      create-if-not-exist: true

rbac:                                            # 结构见 §5.2
  ...

agentscope:                                      # AG-UI starter，见 §6.3
  agui:
    path-prefix: /agui
    agent-id-header: X-Agent-Id
    ...
```

环境变量（部署侧）：`TEAPOT_AI_DB_PASSWORD`、`RBAC_JWT_SECRET`、`DASHSCOPE_API_KEY`、
`OPENAI_API_KEY`、`AGENTSCOPE_WORKSPACE`。

---

## 14. 安全设计

1. 密钥管理：DB 密码 / JWT secret 仅环境变量注入；**业务侧密钥**（AgentRun API Key 等，§16.5.1）
   允许对称加密入库（AES-256-GCM，主密钥仍仅环境变量），禁止明文落库；UI/日志/审计一律脱敏。
2. BCrypt 存密；登录失败统一文案，不区分"用户不存在/密码错误"；登录增加失败计数锁定
   （5 次锁 10 分钟，内存计数一期即可）。
3. JWT 校验在过滤器链最前；`/agui/**` 与 `/api/**` 同等鉴权；`RuntimeContext.userId` 服务端强制。
4. CORS：仅允许前端来源（配置化白名单）。
5. 输入校验：`agentKey`/`skill name` 正则约束；skill 内容大小上限（SKILL.md ≤ 256KB，单资源 ≤ 1MB）。
6. 审计：Agent/Skill/User 的写操作记录操作日志表 `t_audit_log`（二期建表，一期先用应用日志）。
7. 二期强化项：Redis 黑名单吊销 token、OtelTracingMiddleware 观测、
   `enableSkillPromotionGate`（若开放 agent 自产 skill，**必须**配审核门，禁止 autoPromote）。

---

## 15. Git Skill 仓库接入（GitSkillRepository）

### 15.1 背景与目标

§8 的 MySQL Skill Repository 支撑“配置化建设一切 skill”的在线编辑场景；但团队级技能资产还需要
**Git PR 流程管控**（可 review、可回滚、跨项目共享）。本章接入 AgentScope 官方
`agentscope-extensions-skill-git-repository`，把远程 Git 仓库作为**第二 skill 来源**，与 MySQL 来源共存：

- Git 来源：**只读**，内容经 PR 合并后由平台自动/手动同步生效；
- MySQL 来源：保持 §8 全部能力（表单 CRUD、预览、级联解绑）不变；
- 两来源对 Agent 透明：绑定仍按 skill name（`t_agent_skill` 不感知来源）。

### 15.2 非目标

- 平台**不写回 Git**（不提供在线编辑 git skill 的能力，修改一律走 PR）；
- 一期仅支持**单仓库**配置（多仓库管理表 `t_skill_repo` 留二期）；
- 不改变 §8.4 四层模型与 `SkillFilter` 语义；沙箱执行由 §16 单独接入，本章不涉及。

### 15.3 官方能力基线（文档 + 2.0.1 构件实测）

依赖构件：`io.agentscope:agentscope-extensions-skill-git-repository:${agentscope.version}`（2.0.1，
Maven Central 已核实存在；底层 JGit，HTTPS/SSH 均支持）。

类：`io.agentscope.core.skill.repository.GitSkillRepository implements AgentSkillRepository`。
2.0.1 实测构造器（javap）：

| # | 签名 | 语义（官方文档） |
|---|------|------------------|
| 1 | `(String remoteUrl)` | 默认分支 + 临时目录 + autoSync=true |
| 2 | `(String remoteUrl, Path localPath)` | 自定义本地 clone 路径 |
| 3 | `(String remoteUrl, String branch)` | 选定分支 |
| 4 | `(String remoteUrl, boolean autoSync)` | 关闭自动同步 |
| 5 | `(String remoteUrl, String branch, Path localPath, String source, boolean autoSync)` | **平台采用**：全参 |
| 6 | `(…, boolean autoSync, String extra)` | 末参语义待实施期确认（疑似凭证参数，见 §18 风险 13） |

关键行为（官方文档 v2《Git 技能仓库》）：

- **autoSync=true（默认）**：`getSkill/getAllSkills/skillExists` 读操作前先 `ls-remote` 轻量检查，
  仅远端 HEAD 变化才真正 pull，平时几乎零开销；
- **autoSync=false**：完全不自动 pull，需手动 `repo.sync()`；
- **鉴权复用系统级 Git 配置**，Java 侧不管理凭证：HTTPS 走 `~/.gitconfig` credential helper；
  SSH 走 `~/.ssh/` 密钥与 ssh-agent；
- 本地 clone：临时目录（注册 JVM Shutdown Hook 清理）或自定义 Path；建议 Spring 单例持有、退出统一 `close()`；
- 多实例部署各自维护一份 clone，无锁竞争。

`AgentSkillRepository` 接口（javap 实测）：`getSkill / getAllSkillNames / getAllSkills / save /
delete / skillExists / getRepositoryInfo / getSource / setWriteable / isWriteable / close`。

**关键实测**：`HarnessAgent.Builder` 同时提供 `skillRepository(单个)` 与
`skillRepositories(List<AgentSkillRepository>)` —— 多来源**无需自写组合仓库**，直接传列表。

### 15.4 总体架构

```
                    ┌────────────────────────────┐
  Skill 工坊(§8) ──写──▶ MysqlSkillRepository(admin, writeable=true)
                    │    MysqlSkillRepository(agent, writeable=false) ─
  远程 Git 仓库 ──clone──▶ GitSkillRepository(read-only, autoSync) ────┤ skillRepositories(List)
                    └────────────────────────────┘                    ▼
                                                              HarnessAgent（SkillFilter 按 name 过滤）
```

- **读侧合并**：`SkillService.list/detail` 同时读两来源，`source` 字段区分 `platform` / `git`；
- **写侧隔离**：`save/delete` 只落 MySQL，且对 git 来源做同名守卫（§15.8）；
- **Agent 侧**：`AgentRegistry` 改传 `skillRepositories([mysqlAgent, git?])`，`SkillFilter` 语义不变
  （按 name 跨来源过滤；空绑定 = 两来源全集）。

### 15.5 配置设计

`TeapotAiProperties` 增加嵌套 `SkillGit`；`application.yml` 草案：

```yaml
teapot:
  ai:
    skill-git:
      enabled:    ${GIT_SKILL_ENABLED:false}          # 功能开关：false 时 Bean 不装配，零副作用
      remote-url: ${GIT_SKILL_REMOTE:}                # 空 = 未配置；私有仓凭证仅经环境变量注入
      branch:     ${GIT_SKILL_BRANCH:main}
      local-path: ${GIT_SKILL_LOCAL_PATH:${AGENTSCOPE_WORKSPACE:./workspace}/git-skills}
      source:     git                                 # 列表/详情展示的 source 标识
      auto-sync:  true                                # 读操作轻量 ls-remote，HEAD 变化才 pull
      skills-root: ${GIT_SKILL_SKILLS_ROOT:}          # 仓内 skill 目录根（相对仓库根）；空=自动探测（§15.10）
```

服务器侧（§11/§13 约定）：`app.env` 增加 `GIT_SKILL_ENABLED=true`、`GIT_SKILL_REMOTE=…`、
`GIT_SKILL_LOCAL_PATH=/main/apps/teapot-ai/git-skills`、`GIT_SKILL_SKILLS_ROOT=…`（嵌套布局必填）；
clone 目录可再生，**不纳入备份清单**。

### 15.6 Bean 装配（AgentScopeConfig 改造）

```java
@Bean(destroyMethod = "close")
@ConditionalOnProperty(prefix = "teapot.ai.skill-git", name = "enabled", havingValue = "true")
public GitSkillRepository gitSkillRepository(TeapotAiProperties props) {
    SkillGit cfg = props.getSkillGit();
    return new RootSkillAwareGitSkillRepository(cfg.getRemoteUrl(), cfg.getBranch(),
            Path.of(cfg.getLocalPath()), cfg.getSource(), cfg.isAutoSync(),
            cfg.getSkillsRoot());                     // 6 参构造器：仓内 skill 目录根（§15.10）
}
```

- 装配的是兜底子类 `RootSkillAwareGitSkillRepository`（core/config）：官方扫描之外补识根级
SKILL.md（单 skill 仓布局），覆盖 getAllSkills/getAllSkillNames/getSkill/skillExists 四个读路径，
写操作沿用官方只读语义；声明类型仍为 `GitSkillRepository`，消费方无感知；
- `remote-url` 为空时启动报配置错误（fail-fast，避免静默降级）；
- 消费方一律 `ObjectProvider<GitSkillRepository>` 注入，`enabled=false` 时自然缺席；
- BOM 增加 `agentscope-extensions-skill-git-repository`（版本随 `${agentscope.version}`），core 模块加依赖。

### 15.7 AgentRegistry 改造

`build(agentKey)` 中：

```java
List<AgentSkillRepository> repos = new ArrayList<>();
repos.add(skillRepositoryAgent);                       // MySQL 只读实例（现状）
GitSkillRepository git = gitRepoProvider.getIfAvailable();
if (git != null) repos.add(git);
HarnessAgent.builder()… .skillRepositories(repos) …   // 替换原 .skillRepository(单个)
```

- 列表顺序 `[mysql, git]`；同名合并优先级官方未文档化 → 平台以**跨来源同名守卫**（§15.8）规避歧义，
  实际行为由集成测试记录（§18 风险 14）；
- `invalidate()` 语义不变；git 内容更新无需重建实例（autoSync 读时生效，与 §8.2 动态合并一致）。

### 15.8 SkillService 改造（双来源读、单来源写）

| 方法 | 改造 |
|------|------|
| `list()` | mysql 全量 + git 全量合并；按 name 去重（防御性：git 优先 + `log.warn`）；`SkillListVO.source` 区分 |
| `detail(name)` | 两来源按“mysql 优先存在性”取数并回填 `source`；git 来源前端只读（§15.12） |
| `save()` | **同名守卫**：`git.skillExists(name)` 为真 → `BizException("与 Git 仓库 skill 同名，请走 Git PR 流程修改")` |
| `delete()` | 仅 mysql 存在才允许删；git 来源 → 拒绝 |
| `gitStatus()` | 新增：`{enabled, remoteMasked, branch, skillCount, lastSyncAt}`；remote 脱敏（剥 userinfo） |
| `gitSync()` | 新增：`repo.sync()` + 记录 `lastSyncAt` + 审计 `skill.git.sync`；返回最新列表计数 |

体积上限（§8.2 SKILL.md ≤256KB / 单资源 ≤1MB）对 git 来源**只做展示告警不拦截**（内容归 Git 管）。

### 15.9 REST API 增量

| Method | Path | 说明 | 权限 |
|--------|------|------|------|
| GET | `/api/skill/git/status` | Git 来源状态（enabled/branch/skillCount/lastSyncAt/remoteMasked） | 登录（viewer 可读） |
| POST | `/api/skill/git/sync` | 手动同步（autoSync=false 或运维强制刷新） | developer |

RBAC yml：viewer 资源列表追加 `/api/skill/git/status`；developer 已被 `/api/skill/*` 通配覆盖 sync。
现有 `/api/skill/list`、`/api/skill/detail/{name}` 出参增加 `source` 字段（向后兼容）。

### 15.10 Git 仓库目录约定

```
repo-root/
├── <skill-name>/
│   ├── SKILL.md            # frontmatter: name（必须等于目录名）+ description
│   ├── references/*.md     # 参考文档（可选）
│   └── scripts/*           # 脚本（可选；一期只分发不执行，§1.1）
├── <another-skill>/SKILL.md
└── README.md               # 无 SKILL.md 的目录/文件被忽略
```

- **扫描规则（2.0.1 源码实测，已核实）**：只扫 skillsRoot 下**第一层**子目录（`Files.list`，非递归），
  子目录含 `SKILL.md` 即识别为 skill；默认 skillsRoot = 仓库根的 `skills/` 子目录（存在则用），否则仓库根；
- **根级 SKILL.md（单 skill 仓布局）官方不识别**，由平台兜底子类 `RootSkillAwareGitSkillRepository` 补识：
  有效 skillsRoot 下直接存在 `SKILL.md` 时按单 skill 加载（资源递归并入，`.git`/隐藏目录自动跳过），
  与官方扫描结果按 name 去重合并；2026-08-18 已用 gitee 单 skill 仓（rising-sun-rules，20 个 references）实测通过；
- 嵌套布局（如 Qoder 的 `.qoder/skills/<name>/SKILL.md`）需经 6 参构造器的 `skillsRoot` 显式指定
  （配置 `skills-root`，如 `.qoder/skills`）；`skillsRoot` 拒绝绝对路径与 `..` 段；
- CI 建议（仓库侧）：校验 frontmatter `name` 与目录名一致、description 非空、体积上限。

### 15.11 同步与降级矩阵

| 场景 | 行为 |
|------|------|
| 启动首次 clone 失败（网络/凭证） | error 日志；git 来源空集；平台（mysql）功能不受影响；status 体现 skillCount=0 |
| 运行期 ls-remote/pull 失败 | 沿用上次成功 clone 内容，warn 日志（官方 autoSync 语义） |
| 本地 clone 目录损坏 | 运维处置：删除 `local-path` 后调用 `/api/skill/git/sync` 或重启（触发重 clone） |
| `enabled=false` | Bean 不装配；list/detail/save 全链路短路 git 分支；回滚即关 |

### 15.12 鉴权与安全

- **公开仓库**：直接 HTTPS URL，无凭证；
- **私有仓库（推荐）**：服务器生成 ed25519 **deploy key（只读）** 注册到 Git 平台，私钥放服务账号
  `HOME/.ssh`（chmod 600），`remote-url` 用 SSH 形式（`git@host:org/skills.git`）；systemd 单元确保
  `HOME` 指向正确（§11）；
- **私有仓库（备选）**：HTTPS + PAT 内嵌 URL（`https://oauth2:<token>@host/…`），整串仅存 `app.env`
  环境变量；**脱敏规则**：日志/status/审计一律剥离 userinfo 只显 host+path；凭证不入库、不入 git、不入审计；
- git skill 内容进入 Agent 上下文，等同平台 skill，受 §14 输入校验与审计约束。

### 15.13 前端设计（teapot-ai-web）

- **Skills 页**：`status.enabled` 时顶部状态条：`branch · skillCount · lastSyncAt` + 「立即同步」按钮
  （developer 及以上可见，调用 sync 后刷新列表）；
- 列表增加 `source` 标签（platform / git）；git 行**隐藏编辑/删除**，详情抽屉只读并展示横幅：
  “该 skill 由 Git 仓库（branch xxx）管控，修改请提交 PR”；
- **Agent 详情-技能绑定**：选项 label 追加来源后缀（如 `name（git）`），绑定值仍为 name；
- API 层 `api/skill.ts` 增加 `gitStatus()/gitSync()`。

### 15.14 审计

`skill.git.sync`（操作人、同步后 skillCount）写入现有审计通道（§14 第 6 条）；status 查询不审计。

### 15.15 数据库变更

**无新表、无改表**。`t_agent_skill` 按 name 绑定、不感知来源，天然兼容双来源。
二期演进：多仓库管理表 `t_skill_repo(id, repo_key, remote_url, branch, …)` + 管理 UI。

### 15.16 部署与运维

- `app.env` 新增 `GIT_SKILL_ENABLED/GIT_SKILL_REMOTE/GIT_SKILL_BRANCH/GIT_SKILL_LOCAL_PATH`（chmod 600，不入仓）；
- deploy key 生成与注册步骤写入 `deploy/` 脚本注释（`setup-git-skill.sh`，幂等）；
- clone 目录磁盘占用小（纯文本），纳入 §11.6 观察项即可；多实例各自 clone 无锁竞争；
- 回滚：`GIT_SKILL_ENABLED=false` + 重启，立即回到单 MySQL 来源。

### 15.17 测试计划

- **单元**：同名守卫、remote 脱敏、双来源合并去重排序；
- **集成**：本地 `file://` 远程仓库 fixture：clone→`getAllSkills` 往返；push 新 commit 后读操作可见新内容
  （autoSync）；`sync()` 手动路径；
- **e2e**：git skill 出现在列表（source=git）→ 绑定 Agent → 对话中 `<available_skills>` 可见并可调用；
  平台 save 同名拒绝；`enabled=false` 全链路无 git 痕迹。

### 15.18 实施任务分解

| # | 任务 | 涉及 |
|---|------|------|
| T1 | BOM + core pom 增加 git-repository 依赖 | `teapot-ai-bom/pom.xml`、`teapot-ai-core/pom.xml` |
| T2 | `TeapotAiProperties.SkillGit` + yml + `AgentScopeConfig` 条件 Bean | core/config |
| T3 | `AgentRegistry.skillRepositories` 切换；`SkillService` 双来源读/守卫/status/sync；Controller + rbac yml | core/service、controller、start/resources |
| T4 | 前端 Skills 页 source 标签/只读/状态条/同步按钮；Agent 详情来源后缀 | teapot-ai-web |
| T5 | 部署：app.env、deploy key 脚本、重启验证 | deploy/ |
| T6 | 单元/集成/e2e 按 §15.17 验收 | teapot-test |

---

## 16. 阿里云 AgentRun 沙箱接入（AgentRunFilesystemSpec）

### 16.1 背景与目标

§1.1 原定“沙箱执行留二期”（风险 7：skill 脚本只分发不执行）。本章将**隔离执行能力**提入一期，
后端选用阿里云 AgentRun（函数计算 FC 3.0 Sandbox API，版本 2025-09-10）托管沙箱：

- Agent 获得 `shell_execute` 与文件读写能力，真实 IO/进程全部发生在阿里云隔离容器内，宿主机（114 服务器）无感；
- skill `scripts/` 具备执行条件（pip install / python / bash），跨轮次状态保留（同 session 恢复同一沙箱）；
- 不在生产服务器自建 Docker daemon（2C3.7G 资源紧张，且容器与业务进程同机有安全风险）；
  Serverless 按量计费、免运维、中国大陆低延迟。

### 16.2 非目标

- 不使用「AgentRun 内置 Agent & Skills MCP 模式」（该模式面向外部客户端经 MCP 调用内置 Agent；
  平台走 harness 官方沙箱适配器，与 HarnessAgent 生命周期深度整合）；
- 不自建 K8s / Daytona / E2B 后端（同为 `SandboxFilesystemSpec` 可换，留二期多云选项）；
- 不承诺沙箱网络出口白名单（依赖模板凭证/VPC 配置，运维按模板评估）。

### 16.3 官方能力基线（文档 + 2.0.1 构件 javap 实测）

依赖构件：`io.agentscope:agentscope-extensions-sandbox-agentrun:${agentscope.version}`（2.0.1，
Maven Central 已核实存在）。

**关键实测结论**：

- `agentscope-harness:2.0.1` 本体**仅含 docker 实现**（`sandbox/impl/docker`，jar tf 核实），
  AgentRun 适配器在**独立扩展构件**，BOM 需显式声明；
- 实际包名 `io.agentscope.extensions.sandbox.agentrun`（官网文档页写作
  `io.agentscope.harness.agent.sandbox.impl.agentrun`，已过时；**以构件 javap 实测为准**，见 §17 风险 16）。

核心类（javap 实测）：

| 类 | 职责 |
|----|------|
| `AgentRunFilesystemSpec extends SandboxFilesystemSpec` | HarnessAgent 装配入口，fluent 配置 |
| `AgentRunSandboxClient / AgentRunSandboxClientOptions` | 数据面 HTTP（OkHttp）+ 生命周期；含 `MAX_OSS_MOUNTS`、`ALLOWED_MOUNT_PREFIXES` 约束 |
| `AgentRunNasMountConfig` | NAS 挂载：serverAddr / mountDir / remotePath / enableTLS |
| `AgentRunOssMountConfig` | OSS 挂载（单实例 ≤5 个） |
| `AgentRunHarnessSandboxJacksonModule` | SandboxState JSON 多态注册（NamedType） |
| `AgentRunMcpChannel` | 执行通道：`process_exec_cmd` / `read_file` / `write_file` |

`AgentRunFilesystemSpec` 关键配置项（fluent 方法名无 `set` 前缀）：

| 方法 | 说明 |
|------|------|
| `apiKey(String)` / `accountId(String)` / `region(String)` | 数据面鉴权 X-API-Key + X-Acs-Parent-Id；数据面 `https://{accountId}.agentrun-data.{region}.aliyuncs.com`，不引入完整 OpenAPI SDK |
| `templateName(String)` | AgentRun 控制台预创建的沙箱模板名 |
| `mcpServerUrl(String)` | **必填**：模板 MCP 服务地址（adapter 不自动发现，控制台拷贝） |
| `dataPlaneBaseUrl(String)` / `mcpEndpoint(String)` | 可选覆盖（专有云/代理场景） |
| `nasConfig(...)` / `addOssMount(...)` | 实例级动态挂载 |
| `workspaceRoot(String)` | 沙箱工作区根；以 nasConfig.mountDir 为前缀时自动判定 NAS 持久化 |
| `sandboxIdleTimeoutSeconds(int)` | 闲置回收阈值（默认 1800），超时销毁、下次同 id 重建恢复 |
| `connectTimeoutSeconds / readTimeoutSeconds / maxRetries` | 健壮性参数 |
| `snapshotSpec(...)` / `isolationScope(...)` | 基类 `SandboxFilesystemSpec` 能力 |

关键行为（官方文档 + 实测）：

- **sandboxId 由 sessionId 派生**（SHA-256 → 26 字符 Crockford Base32）：同 session → 同 sandbox；
  销毁后同 id 重建 ≒ resume（StopSandbox 是终态，adapter 用 DeleteSandbox + 重建模拟）；
- **IsolationScope=SESSION（平台选型）**：每会话独立沙箱，多用户 SaaS 天然隔离；
- **工作区投影**：宿主 workspace 的 `AGENTS.md / skills/ / subagents/ / knowledge/` 每次启动按
  SHA-256 增量同步进沙箱，skill 脚本执行的官方基础机制；
- 生命周期自动：PreCall acquire→start（4-分支工作区恢复），PostCall/Error stop（快照持久化）→release。

### 16.4 总体架构

```
HarnessAgent.builder().filesystem(AgentRunFilesystemSpec)…   ← 该 agent 的 feature.sandbox.enabled=true（§16.6）
     │ 每次 call
     ▼
SandboxLifecycleMiddleware
  PreCall : acquire(sessionId) → CreateSandbox/GetSandbox（数据面）→ start() → MCP 通道就绪
  [模型推理] shell_execute / 文件读写 → process_exec_cmd / read_file / write_file（阿里云容器内）
  PostCall: stop()（快照持久化）→ persist state → release；闲置 1800s 自动回收，下次同 id 重建
```

### 16.5 全局连接配置（凭证与默认值）

凭证管理采用**对称加密入库 + 环境变量应急覆盖**双通道（细则见 §16.5.1）：

- **主通道**：管理员在 UI 配置 API Key / 账号 ID，AES-256-GCM 加密后落 `t_sys_config`，
  无需 SSH 改 `app.env` 重启；多副本天然共享；
- **覆盖通道**：`app.env` 同名环境变量存在时**优先于 DB**（应急切换/迁移过渡）；
- region / 默认模板 / 默认 workspaceRoot 等非敏感项存同表明文；MCP URL 含账号 ID 但不含秘密，
  同表明文存储，UI 展示不脱敏；
- snapshot 本地路径仍走 yml（服务器本地目录，非界面配置项）：

```yaml
teapot:
  ai:
    sandbox:
      agentrun:
        snapshot-path: ${AGENT_SNAPSHOT_PATH:${AGENTSCOPE_WORKSPACE:./workspace}/sandbox-snapshots}
```

- `configured()` 判定：apiKey/accountId/mcpServerUrl 三项齐备（env 或 DB 合并后）；
  未接入时选项接口返回 `configured=false`，前端禁用启用开关（§16.11）；
- **无全局 enabled 开关**：是否启用沙箱按 Agent 由 `feature.sandbox.enabled` 决定（§16.6）；
  回滚 = 页面关闭对应 Agent 开关，或清空凭证使全部 Agent 回退 `disableShellTool`；
- snapshot 目录可再生，**不纳入备份清单**，磁盘占用入 §11.6 观察项。

### 16.5.1 凭证对称加密入库

**表**（`sql/V5__sys_config.sql`，§10.1 同步登记）：

```sql
CREATE TABLE t_sys_config (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  config_key   VARCHAR(64)  NOT NULL COMMENT 'agentrun.api_key / agentrun.account_id / agentrun.region / …',
  config_value TEXT         NOT NULL COMMENT '敏感项存 AES-GCM 密文 v<keyVer>:<base64(iv+ciphertext+tag)>',
  key_version  TINYINT      NOT NULL DEFAULT 1 COMMENT '主密钥版本，轮换用',
  encrypted    TINYINT      NOT NULL DEFAULT 0 COMMENT '1密文 0明文',
  updated_by   VARCHAR(64)  NOT NULL,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置（含加密凭证）';
```

**加密方案**（`ConfigCryptoService`）：

- AES-256-**GCM**（认证加密，防篡改；禁用 ECB/无认证模式）；每次加密生成**随机 12B IV**，
  密文格式 `v{keyVersion}:{base64(iv | ciphertext | tag)}`；
- 主密钥 `TEAPOT_SECRET_KEY`（32B base64）**仅 `app.env` 环境变量**，绝不入库不入 git；
- 密钥轮换：`key_version` 随密文存储，解密按版本选钥；一期单版本，轮换工具留二期；
- DB 备份泄露 ≠ 凭证泄露（还需主密钥）；主密钥与 DB 同时泄露才可解密，属已知风险边界。

**脱敏与权限**：

- GET 回显一律脱敏：`configured` 布尔 + 末 4 位掩码（如 `****abcd`），任何接口不返回明文；
- 写仅 admin（RBAC：`/api/config/*` 读 = developer，写 = admin 独占）；
- 审计：凭证写入/更新记 `config.update`（只记 key 名不记值，§14 第 6 条）。

### 16.6 按 Agent 沙箱配置：t_agent.feature 字段

新增通用扩展字段（承载一切 Agent 级功能配置，沙箱是首个消费者，后续能力按命名空间追加）：

```sql
ALTER TABLE t_agent ADD COLUMN feature JSON NULL COMMENT '扩展功能配置(JSON)，SPEC §16.6';
-- 迁移文件 sql/V4__agent_feature.sql；§10.1 的 CREATE TABLE 同步含该列
```

JSON 结构（一期仅 `sandbox` 命名空间）：

```json
{
  "sandbox": {
    "enabled": true,
    "isolationScope": "SESSION",
    "persistence": "LOCAL_SNAPSHOT",
    "templateName": "teapot-ci-2c4g",
    "workspaceRoot": "/home/agentscope/workspace",
    "idleTimeoutSeconds": 1800,
    "nas": {
      "serverAddr": "12345abc-xxx.cn-hangzhou.nas.aliyuncs.com",
      "mountDir": "/mnt/nas",
      "remotePath": "/",
      "enableTLS": false
    }
  }
}
```

字段与校验规则（AgentService 保存时强校验，不合法直接拒绝）：

| 字段 | 类型 | 校验 | 缺省 |
|------|------|------|------|
| `enabled` | boolean | true 时要求全局已接入（§16.5 `configured=true`） | false |
| `isolationScope` | 枚举 | `SESSION/USER/AGENT/GLOBAL`；USER 及以上为顺序复用非并发共享，一期单实例部署可接受，二期多副本需配 ExecutionGuard（§16.3） | SESSION |
| `persistence` | 枚举 | `NONE`（不持久化，回收即丢）/ `LOCAL_SNAPSHOT`（tar 拉回本机）/ `NAS`（需 nas 子对象） | LOCAL_SNAPSHOT |
| `templateName` | string | 可选，覆盖全局 default-template（不同规格模板按 Agent 选型） | 全局默认 |
| `workspaceRoot` | string | 绝对路径；`persistence=NAS` 时**必须**以 `nas.mountDir` 为前缀（官方 Branch A 判定） | 全局默认 |
| `idleTimeoutSeconds` | int | 300–21600 | 1800 |
| `nas.mountDir` | string | 必须 `/home/`、`/mnt/` 或 `/data/` 前缀（官方约束） | — |

向前兼容：未知顶层命名空间原样保留（不拒不改）；`sandbox` 内字段强校验。
`feature` 为 NULL = 无任何功能启用（存量 Agent 向后兼容）。

### 16.7 后端装配与 AgentRegistry 改造

```java
// 全局连接配置 Bean：凭证取值 env 优先、DB 次之（解密）；configured() = 三项齐备
@Bean AgentRunConnection agentRunConnection(SysConfigService configService) { … }

// AgentRegistry.build(agentKey)：
AgentFeature.Sandbox sb = AgentFeature.parse(agentDO.getFeature()).getSandbox();
if (sb != null && sb.isEnabled()) {
    if (!connection.configured()) throw new BizException("AgentRun 未接入，不能启用沙箱"); // 防御，正常已在保存时拦截
    AgentRunFilesystemSpec spec = new AgentRunFilesystemSpec()
            .apiKey(connection.getApiKey()).accountId(connection.getAccountId())
            .region(connection.getRegion())
            .templateName(sb.getTemplateName())            // feature 覆盖全局默认
            .mcpServerUrl(connection.getMcpServerUrl())
            .workspaceRoot(sb.getWorkspaceRoot())
            .sandboxIdleTimeoutSeconds(sb.getIdleTimeoutSeconds())
            .snapshotSpec(buildSnapshot(sb))               // NONE→Noop / LOCAL→LocalSnapshotSpec / NAS→Noop
            .isolationScope(IsolationScope.valueOf(sb.getIsolationScope()));
    if ("NAS".equals(sb.getPersistence())) spec.nasConfig(toNasConfig(sb.getNas()));
    builder.filesystem(spec);                              // 沙箱模式：shell/文件全部走阿里云容器
} else {
    builder.disableShellTool();                            // 现状：脚本仅分发不执行
}
```

- spec 实例**按 agent 构建**（隔离维度/持久化/模板各不相同），生命周期随 Registry 缓存；
  `agentUpdate` 已触发 `invalidate(agentKey)` → 页面改配置下次对话即生效；
- `persistence=NAS` 时 workspaceRoot 落挂载目录内，快照自动退化为 no-op（官方 Branch A）；
- **Jackson 要求**（官方自检清单）：参与 SandboxState 反序列化的 ObjectMapper 必须注册
  `AgentRunHarnessSandboxJacksonModule`，否则跨 call 状态恢复失败（§18 风险 17）；
- BOM 增加 `agentscope-extensions-sandbox-agentrun`（版本随 `${agentscope.version}`），core 模块加依赖。

### 16.8 阿里云侧准备（运维清单）

1. 开通函数计算 + AgentRun 并完成授权（RAM 子账号需 FC + AgentRun 权限）；
2. 控制台创建沙箱模板：一期建议**代码解释器沙箱 2C4G**（纯代码执行，成本低）；
   如需浏览器/文档处理再升 AIO 沙箱 4C8G；
3. 模板激活 MCP：`process_exec_cmd / read_file / write_file` 三件套（adapter 仅使用这三个工具）；
4. 沙箱详情 → 集成与案例 → MCP 集成：启动服务，拷贝服务地址
   `https://<账号ID>.agentrun-data.<地域>.aliyuncs.com/templates/<模板名>/mcp` → `AGENTRUN_MCP_URL`；
5. 生成数据面 API Key → `AGENTRUN_API_KEY`；记录主账号 ID → `ALIYUN_ACCOUNT_ID`；
6. 模板高级配置：闲置超时与 yml 对齐（1800s）；TTL 默认 6h；执行角色一期最小权限（不授 OSS/NAS 策略）；
7. （二期可选）升级 NAS/OSS 持久化：同地域资源 + RAM 读写策略 + 挂载目录必须
   `/home/`、`/mnt/` 或 `/data/` 前缀。

### 16.9 安全

- API Key / 账号 ID 对称加密入库（§16.5.1），主密钥仅 env；GET 回显只给 configured + 末 4 位掩码，
  任何接口不返回明文；日志/审计脱敏；`feature` 只存功能参数不含凭证，可直接回显；
- 平台 → 阿里云为出站 HTTPS，服务器**不新增任何入站端口**；JWT/CORS 体系（§14）不变；
- 隔离边界 = FC 容器：`rm -rf`、`pip install` 等仅影响沙箱工作区，宿主机无感；
  已安装依赖随快照保留，下次恢复无需重装；
- 沙箱网络出口由模板凭证配置管控（一期默认公网匿名访问；访问内网资源留二期 VPC 方案评估）；
- `shell_execute` 能力暴露给模型：sysPrompt 约束 + 写操作审计（§14 第 6 条）。

### 16.10 成本与容量

- FC 按沙箱实例 CPU/内存 × 存活时长计费；实例数 ≈ 启用沙箱的 Agent 的活跃会话数，闲置 1800s 自动回收控费；
- 初始规格 2C4G（代码解释器）起步；账单异常纳入 §11.6 巡检项；
- 公测免费项（如内置 Agent & Skills 的模型调用）平台不使用，不依赖其政策。

### 16.11 前端（AgentDetail 新增 Sandbox 配置区）

- 左侧胶囊菜单新增 **Sandbox** 分区（Basic Info 与 Skills 之间），字段与 §16.6 一一对应：
  启用 Switch、隔离维度 Select（SESSION/USER/AGENT/GLOBAL + 中文注释）、持久化 Radio
  （不持久化/本地快照/NAS 挂载）、模板 Input（placeholder 显示全局默认）、闲置超时 InputNumber（300–21600）；
  `persistence=NAS` 时条件展开 NAS 四字段子表单；分区顶部（admin 可见）提供「全局接入凭证」
  编辑入口（API Key/账号 ID/region/MCP URL，回显脱敏，写调 `PUT /api/config/sandbox`，§16.5.1）；
- 页面加载 `GET /api/config/sandbox-options`（developer）：`{configured, region, defaultTemplate,
  defaultWorkspaceRoot}`；`configured=false` 时显示横幅“AgentRun 未接入（请联系管理员配置凭证）”
  并禁用启用开关；
- 保存：表单序列化为 `feature.sandbox` 随 `PUT /api/agent/update/{agentKey}` 提交；
  `enabled=false` 时仅提交 `{"enabled": false}`（剔除其余子项，保持 feature 精简）；
  后端校验失败（枚举/范围/前缀/未接入）返回错误文案，前端 message 展示；
- 保存成功后 `invalidate` 自动触发，下一轮对话即按新配置构建；
- API 层 `api/agent.ts` 增 `sandboxOptions()`；`AgentDetailVO` 出参增加 `feature` 用于表单回显；
- RBAC：developer 资源列表追加 `/api/config/sandbox-options`；`/api/config/*` 写操作仅 admin（`/*` 已覆盖）。

对话台无需改动：沙箱开启后 agent 自动具备 shell 能力，工具调用事件经 AG-UI 透明展示
（`emit-tool-call-args: true` 已启用）。

### 16.12 与 Skill 脚本的关系

- 官方工作区投影只同步宿主 `workspace/<agent>/skills/` 目录；平台 skill 存于 DB（MySQL/Git 来源），
  **不会自动进入投影目录**；
- 一期脚本类 skill 的执行路径：SKILL.md 正文内嵌脚本，模型经 `write_file` 落盘沙箱后执行；
  或运维按 §8.4 L3 通道把紧急脚本 skill 手工放入宿主 workspace `skills/`（走投影同步）；
- 「DB skill 脚本自动落盘 workspace 参与投影」为二期优化项（见 §17 演进路线）。

### 16.13 测试计划

- **单元**：`AgentFeature` 解析与保存校验（枚举越界、NAS 缺子对象、mountDir/workspaceRoot 前缀不符、
  idle 范围、`enabled=true` 但全局未接入拒绝）；feature 为 NULL/空/未知命名空间的兼容分支；
  `ConfigCryptoService` 加解密往返、篡改检测（GCM tag）、随机 IV 不重复、脱敏掩码、
  `/api/config/*` 写操作非 admin 403；
- **集成**（需阿里云测试账号）：spec 构建 → 首次 call 创建沙箱 → `shell_execute` 执行
  `python3 -c "print(1)"` 返回 1 → stop 后再次 call，同 sandboxId 恢复且前次创建的文件仍在；
- **e2e**：页面为 Agent A 启用沙箱（SESSION+本地快照）→ 对话 shell_execute 生效、同会话恢复工作区；
  Agent B 未启用则无 shell 能力（互不影响）；页面改 isolationScope/persistence 保存后下一轮对话生效；
  关闭开关后回退 `disableShellTool`，对话功能不受影响。

### 16.14 实施任务分解

| # | 任务 | 涉及 |
|---|------|------|
| S1 | BOM + core pom 增加 sandbox-agentrun 依赖 | `teapot-ai-bom/pom.xml`、`teapot-ai-core/pom.xml` |
| S2 | `sql/V5__sys_config.sql` + `SysConfigService` + `ConfigCryptoService`（AES-GCM）；
     `GET/PUT /api/config/sandbox` + `sandbox-options` + RBAC yml；`AgentRunConnection`（env 优先 DB 次之） | sql、core/config、controller |
| S3 | `sql/V4__agent_feature.sql`；`AgentDO.feature` + Mapper；Create/Update DTO 加 `feature`；`AgentFeature` 模型 + §16.6 保存校验 | sql、core/model、core/service |
| S4 | `AgentRegistry` 按 feature 构建 per-agent spec（三种持久化分支 + Jackson Module） | core/service |
| S5 | 前端 AgentDetail Sandbox 分区（表单/回显/未接入横幅/凭证编辑）+ api/types | teapot-ai-web |
| S6 | 阿里云控制台：模板创建 + MCP 激活 + API Key；凭证经 UI 配置（env 覆盖通道验证） | 阿里云控制台 |
| S7 | 单元/集成/e2e 按 §16.13 验收（含加密往返/脱敏/权限用例）；账单观察 | teapot-test |

---

## 17. 里程碑与验收标准

| 阶段 | 内容 | 验收标准 |
|------|------|----------|
| **M0 基础设施** | 停原生 5.7 → Docker MySQL 8.4 LTS 建库建号、AgentScope-on-8.4 最小兼容性验证、备份脚本、nginx 站点、systemd 部署单元 | 两库可连；框架自动建表成功；`https://teapot.teamer.com.cn` 200；SSE 经 nginx 1 小时不断流 |
| **M1 登录-用户体系** | rbac 模块、JWT、用户管理 API + 登录页 | admin 登录→拿 token→访问受控 API；无 token 401；权限不足 403；老 rbac 语义用例全部通过 |
| **M2 Agent 运行时** | AgentRegistry + AG-UI starter + 对话台 | 新建 `general-assistant`→前端流式对话→重启进程后同会话续聊记忆保留 |
| **M3 Skill 平台** | Skill 工坊 CRUD + Agent 绑定 | 表单创建 `meeting-notes`→绑定 agent→下一轮对话 agent 的 `<available_skills>` 出现该 skill 并可被调用；删除后消失 |
| **M3.5 Git Skill** | GitSkillRepository 接入（§15） | fixture Git 仓库→列表 `source=git` 展示→绑定 Agent 生效→push 新 commit 后自动/手动 sync 可见；平台 save 同名拒绝；`enabled=false` 一键回退 |
| **M3.6 AgentRun 沙箱** | 阿里云 AgentRun 沙箱接入（§16） | Agent 配置页按 Agent 设置沙箱隔离维度/持久化/参数（数据落 `t_agent.feature`）→ shell_execute 在沙箱内执行 python 并返回结果；同会话再次 call 恢复工作区；关闭开关后下轮对话回退无 shell |
| **M4 前端收口** | Agent 配置页、用户管理、权限路由守卫 | 三角色权限矩阵手工验收通过 |
| **M5 生产收口** | Dockerfile、部署脚本、监控日志、备份演练 | 服务器一键部署跑通；备份可恢复 |

二期路线（不在本 spec 范围）：按 Agent 粒度沙箱开关、DB skill 脚本投影落盘、NAS/OSS 持久化升级、
Agent 自学习 skill 闭环、
Redis 状态存储/多副本、角色权限入库动态化、Plan Mode HITL 审批 UI、subagent 编排。

---

## 18. 风险与待确认项

| # | 事项 | 状态 |
|---|------|------|
| 1 | 服务器盘点（server-ops skill）：CentOS7 / 2C3.7G；磁盘已清理至 49%（可用 20G）/ 本机 MySQL 5.7.25 / JDK21 已装 | **已完成**，结论落 §11 |
| 2 | 本机 MySQL root 凭证未知（空密码被拒）；方案 A 改用 Docker 自建实例，root 密码自主生成，**此风险解除** | 已解除 |
| 3 | AgentScope mysql 扩展在 MySQL **8.4 LTS** 下的兼容性（官方示例基于 8.x 验证，风险低） | M0 最小验证；失败降级原生 5.7.25（方案 B） |
| 4 | ~~磁盘水位 88%~~ → 已清理至 49%（可用 20G）；workspace 增长、备份、日志仍需限额与滚动策略 | 已缓解，§11.6 持续观察 |
| 5 | 模型供应商：**已确认一期接入 DashScope + OpenAI**（`OPENAI_BASE_URL` 支持代理端点）；GLM/Kimi 二期 | 已确认，落 §6.4 |
| 6 | `t_agent_skill` 视图过滤方案依赖 AgentScope 2.0.1 实际 API 形状 | 实施阶段验证，降级方案见 §6.1 注 |
| 7 | ~~skill 内 `scripts/` 一期只分发不执行~~ → AgentRun 沙箱已提入一期（§16），脚本可在沙箱内执行 | 已解决，落盘方式见 §16.12 |
| 8 | 老平台 `t_portal_user` 存量用户是否迁移到新平台 | 待确认（迁移脚本可后补） |
| 9 | 域名已改用 `teapot.teamer.com.cn`（老平台迁至 `old-teapot.teamer.com.cn`）；HTTPS 证书来源 | 待确认（一期可先 80 上线） |
| 10 | 停原生 MySQL 5.7 前需最终确认无残留业务依赖（datadir 仅 204M，老 teapot 指向远程库） | M0 首日核查 |
| 11 | Spark Design（`@agentscope-ai/design` / `chat`）npm 发布状态、版本稳定性及 AGUI 组件自定义鉴权头支持度 | M2 启动前验证；降级方案见 §12.1 注 |
| 12 | Spring Boot 4.x（Framework 7）破坏性变更较多，AgentScope agui starter 基于 SB 3.x 生态构建；一期选 3.5.x 兼顾新与稳 | 二期待 AgentScope 官方适配 SB 4.x 后升级（连带 MyBatis Starter 切 4.0.x） |
| 13 | `GitSkillRepository` 6 参构造末位 `String` 参数语义未确认（疑似凭证/用户名），sources jar 无法获取 | 实施期反编译 class 或源码仓库核实；不影响一期（采用 5 参构造 + 系统 git 鉴权），见 §15.3 |
| 14 | 多 repository 同名 skill 的官方合并优先级未文档化 | 平台以「save 同名守卫 + 列表 git 优先并 warn」规避运行时歧义，见 §15.8 |
| 15 | Git 仓库目录扫描规则（子目录深度、非 SKILL.md 目录是否忽略）官方文档未穷举 | 实施期用 file:// fixture 仓库集成测试核实，见 §15.17 |
| 16 | AgentRun 官方文档包名与 2.0.1 构件不符（文档写 harness 内置 `impl.agentrun`，实测为独立扩展 `agentscope-extensions-sandbox-agentrun`，包名 `io.agentscope.extensions.sandbox.agentrun`） | 已 javap 核实，以构件为准（§16.3） |
| 17 | SandboxState 反序列化需 ObjectMapper 注册 `AgentRunHarnessSandboxJacksonModule`，否则跨 call 沙箱状态恢复失败 | 实施期按官方自检清单落实，集成测试覆盖（§16.6） |
| 18 | AgentRun 按沙箱实例计费（FC 按量），实例数随活跃会话数增长 | 闲置 1800s 回收 + 账单巡检（§16.10）；规格按任务选型（代码解释器 2C4G 起步） |
| 19 | 多模态一期（§19）：DashScope multimodal-generation 端点对 base64 data URL 的实际接受度未运行时验证（源码支持不等于端点兼容）；qwen-vl 系列需开通模型入口 | §19.8 T5 验收首项；失败降级为 URL 源（需 OSS，二期方案提前） |

---

## 19. 多模态接入（图片先行，AgentScope 2.0.1 能力已核实）

目标：用户在对话台可随消息上传图片，绑定多模态模型的 Agent 能看图作答；
一期只做**图片**（audio/video 协议与 SDK 已就绪，留二期），不新增任何模型供应商。

### 19.1 现状与差距

| 环节 | 现状 | 差距 |
|------|------|------|
| SDK 消息模型 | `ImageBlock/AudioBlock/VideoBlock` + `Source`（URL / Base64+mediaType） | 无 |
| AG-UI 入站 | `AguiMessageConverter.toMsg` 已支持 user 消息结构化 content（`ImageInputContent` 等，url/data 双源）；非 user 消息结构化块直接拒绝 | 无 |
| DashScope 扩展 | `EndpointType.AUTO` 按模型名路由多模态端点（`qvq*` / 含 `-vl` / 含 `-asr` / `qwen3.5、qwen3.6` 前缀），可 `endpointType(MULTIMODAL)` 强制；`DashScopeMessageConverter/MediaConverter` 支持图/音/视频 | ModelRegistry 未透传 endpointType |
| OpenAI 兼容扩展 | `OpenAIMessageConverter` 支持 ImageBlock→image_url（data URL 可用），图片处理失败降级占位文本 | 无 |
| 状态持久化 | MysqlAgentStateStore 序列化 Msg（ImageBlock 带 Jackson 注解） | 无（历史多模态消息天然可存） |
| 前端发送 | `aguiBridge.createAguiFetch` 只取 text 拼成纯文本 content，附件被丢弃 | **主要缺口** |
| 模型配置 | `t_model_entry` 无能力位；Agent 选到纯文本模型时发图会被供应商 400 | 需 capabilities + 前端 gating |

### 19.2 端到端链路（目标态）

```
前端选图/粘贴 → 压缩限尺 → base64 data URL
→ AG-UI messages[0].content = [ {type:'text',text}, {type:'image', source:{type:'data', mimeType, value}} ]
→ POST /agui/run/{agentKey}（AguiMessageConverter → Msg[TextBlock, ImageBlock]）
→ HarnessAgent → ModelRegistry.resolve 的 DashScope/OpenAI Model
→ formatter 转供应商多模态报文 → 流式回复照旧（TEXT_MESSAGE_CHUNK 等）
```

### 19.3 附件承载方式（决策）

候选：A 前端直传 base64；B 上传平台后传 URL。
**选 A（base64 data URL）**，理由：
- DashScope/OpenAI 云 API 无法回访问平台内网 URL，B 需额外接 OSS，一期不值；
- AG-UI `data` 源与 SDK `Base64Source` 原生对接，零后端改动；
- 成本是请求体膨胀（约 4/3 倍）与会话存储体积，用限额控制（§19.5）。
二期如需大图/音视频，再引入 OSS 预签名 URL（切 `url` 源，协议不变）。
（已落地为 §20：OSS 承载已实现，因多轮回放需 URL 永久有效，改用对象 public-read 直链而非预签名。）

### 19.4 变更清单

**DB（V6__model_multimodal.sql，幂等）**
```sql
ALTER TABLE t_model_entry
  ADD COLUMN capabilities VARCHAR(64) NULL COMMENT '能力位逗号分隔：image,audio,video；NULL=纯文本' AFTER base_url;
-- 预置：qwen-vl 系列按需由管理员界面添加，脚本不预置多模态模型
```
- `capabilities` 含 `image` 时前端展示上传入口；NULL/不含则隐藏并拦截。
- 模型管理页（Models.tsx + ModelService）表单增「多模态能力」多选（一期仅 image 可选）。

**后端**
- `ModelEntryDO` / ModelService / ModelController 出入参增 `capabilities`（向后兼容，缺省 null）；
- `ModelRegistry.create`：dashscope 分支读 entry.capabilities，含 `image` 时
  `.endpointType(EndpointType.MULTIMODAL)`（避免依赖模型名启发式，兼容自建端点别名）；不含则维持 AUTO；openai 分支无需改（同一 chat completions 端点）；entry 变更后照旧 `evict`；
- 运行期守卫（可选，低成本）：AG-UI 入口无需改；若用户绕过前端 gating 对纯文本模型发图，供应商 400 由现有 RUN_ERROR 链路展示，可接受。

**前端（主要工作量）**
- `aguiBridge.createAguiFetch`：content 改为 parts 数组——text 段照旧；附件转
  `{type:'image', source:{type:'data', mimeType, value: base64}}`（与 `ImageInputContent` 对齐）；
- 输入区（SessionPanel / Spark Design Sender）：回形针按钮 + 粘贴 + 拖拽，缩略图预览可移除；
- 发送前压缩：canvas 缩到长边 ≤ 2048px、JPEG quality 0.85（PNG 透明图保留 png）；
- gating：当前会话 Agent 的 modelId 查 `/api/model/list` 的 capabilities，不含 image 时隐藏上传入口并 toast 说明；
- 历史回显：一期用户消息中的图片**不回显**（模板历史仅文本），附件仅影响当轮模型输入，记入限制说明。

**nginx / 部署**
- `client_max_body_size` 提至 20m（base64 膨胀后余量）；SSE 相关配置不动。

### 19.5 限额与安全

| 项 | 限额 | 执行点 |
|------|------|--------|
| 单图原始体积 | ≤ 5 MB | 前端选择时拦截 |
| 单条消息图片数 | ≤ 4 | 前端拦截 |
| 压缩后长边 | ≤ 2048 px | 前端 canvas |
| MIME 白名单 | image/jpeg、image/png、image/webp、image/gif | 前端 + AG-UI 入口可选校验 |
| 请求体 | nginx 20m | §19.4 |
- 图片经模型供应商云端处理，**不额外落平台磁盘/库**（base64 随 Msg 进 state store，属既有会话数据，备份策略不变）；
- 提示词注入风险（图内恶意指令）等同既有用户输入面，RBAC 已限定登录用户，不额外处理。

### 19.6 兼容与降级

| 场景 | 行为 |
|------|------|
| 纯文本模型收到图 | 供应商 400 → RUN_ERROR 卡片；前端 gating 正常时不可达 |
| AUTO 误判（自建端点别名） | capabilities 显式 MULTIMODAL 规避（§19.4） |
| 图片解码/下载失败（OpenAI 链路） | 官方 formatter 降级占位文本 `[Image - processing failed...]`，不中断对话 |
| 旧前端 + 新后端 | 纯文本链路不变，零影响 |
| 回滚 | 前端隐藏入口即下线；DB 列可留不改 |

### 19.7 验收标准

1. Models 页给某 Agent 配 `dashscope:qwen-vl-plus`（capabilities=image）；
2. 对话台上传一张截图 + 提问 → 流式回复正确描述图内内容；
3. 同一会话追问「刚才图里…」→ 多模态历史生效（state store 回放）；
4. 纯文本模型 Agent 的输入区无上传入口；强发（接口直调）得 RUN_ERROR 不崩服务；
5. 超 5MB / 第 5 张图被前端拦截并提示；
6. 重启进程后续聊，含图轮次不报错（可继续文本对话，图不回显）。

### 19.8 实施任务分解

| # | 任务 | 模块 | 预估 |
|---|------|------|------|
| T1 | V6 迁移 + ModelEntryDO/Service/Controller/前端表单 capabilities | 后端+前端 | 0.5d |
| T2 | ModelRegistry endpointType 透传 | 后端 | 0.2d |
| T3 | aguiBridge parts 化 + 上传/压缩/预览/gating | 前端 | 1d |
| T4 | nginx body 限额 + 部署验证 | 运维 | 0.2d |
| T5 | §19.7 全量验收（含 qwen-vl 入口开通） | 全员 | 0.5d |

实施状态（2026-08-19）：T1–T4 已完成并部署（V6 迁移、`/api/model/capabilities` gating 端点、
`imageUpload.ts` 本地压缩 customRequest、nginx `client_max_body_size 20m`）；待 T5 浏览器验收：
admin 在 Models 页新增 `dashscope:qwen-vl-plus`（勾选 image）并绑定 Agent 后发图验证。

二期延伸（不在本节范围）：音频/视频块接入（协议已通，需验证 DashScope omni/asr 模型与限额）、
~~OSS 预签名 URL 承载大图~~（已由 §20 实现：OSS 对象直链 + 存储策略切换）、模型出图（OpenAIMultiModalTool）。

---

## 20. 图片存储策略：base64 内联 vs 阿里云 OSS（策略模式，管理员可配）

目标：图片承载从“base64 内联唯一解”升级为**可配置的双策略**：
`base64`（现状，默认）与 `oss`（阿里云对象存储，OSS Java SDK V2）。
管理员在 Teapot 页面填 AK/Bucket 等配置并切换策略；凭证不齐/上传失败时安全回落，存量数据零迁移。

### 20.1 背景与动机

| 痛点（§19 base64 内联实测） | 数据 |
|------|------|
| 会话状态膨胀 | 单图 base64 ≈270KB，含图会话 state_data ≈340KB，每轮全量序列化/传输 |
| 请求体膨胀 | AG-UI run 请求携 base64，约 4/3 倍原始体积，弱网下易超时/被掐断 |
| 历史回放成本 | messages JSON 曾内联 base64 导致传输中断（后改独立取图端点缓解，但 state 仍存全量 base64） |

OSS 策略下 state 只存 URL（几十字节），模型供应商直接回源 OSS 公网 URL，请求体与存储体积同步下降。

### 20.2 总体方案（已决策）

```
【base64 策略（默认，即 §19 现状）】
前端压缩 → data URL → AG-UI part source:{type:'data'} → ImageBlock(Base64Source) → state 存 base64

【oss 策略】
前端压缩 → POST /api/chat/image/upload（multipart）
→ ImageStorageRouter → OssImageStorageStrategy → OSSClient.putObject（对象 ACL public-read）→ 返回 URL
→ antd Upload response.url = OSS URL → aguiBridge 既有 url 源分支（零改动）
→ AG-UI part source:{type:'url'} → AguiMessageConverter → ImageBlock(URLSource) → state 只存 URL
→ DashScope/OpenAI 推理时回源 OSS 公网 URL；跨设备历史回显直接用 URL
```

关键决策：
1. **介入点选前端预上传**（而非后端改写 Msg）：AG-UI `ImageInputContent` 原生支持 url/data 双源（§19.1 已核实），
   前端 `aguiBridge` 的 url 源分支已就绪（非 data URL 自动转 `{type:'url', value}`），后端零协议改动；
2. **访问模式选对象级 public-read + 随机 UUID key**（bucket 保持私有），不用预签名 URL：
   多轮追问会重放历史 Msg 中的 URL，预签名过期即废；public-read 直链永久有效，模型回源与历史回显零维护。
   等价于 unguessable URL（key 含 UUID），敏感性风险见 §20.8；
3. **策略解析带回落**（同 AgentRegistry 双链路模式）：strategy=oss 但凭证不齐/OSS 开关关闭 →
   生效策略自动回落 base64 并 warn；前端按 `effectiveStrategy` 走链路，不感知回落细节。

### 20.3 策略模式设计（后端）

```java
/** 图片存储策略（SPEC §20）：store 返回可直接作为 AG-UI url 源的引用 */
public interface ImageStorageStrategy {
    String name();                                  // base64 | oss
    StoredImage store(byte[] data, String mediaType); // 返回值含 url（data URL 或 OSS URL）与策略名
}

// InlineBase64StorageStrategy：bytes → data:{mediaType};base64,{...}（与现状产物一致）
// OssImageStorageStrategy：OSSClient.putObject，key = {keyPrefix}{yyyyMMdd}/{uuid}.{ext}，
//                          对象 ACL public-read + Cache-Control 一年；返回 {customDomain|bucket域}/{key}

// ImageStorageRouter：解析生效策略 = yml 开关 ∧ t_sys_config strategy ∧ OSS 凭证齐备；
//                      持有两策略实现，上传端点统一入口；配置变更（PUT /api/config/storage）后重建 OSSClient
```

- `OssClientManager`：单例 `OSSClient`（AutoCloseable，@PreDestroy close，SDK 要求）；
  `StaticCredentialsProvider`（AK 对）+ region；配置了 customDomain 时 `.endpoint(domain).useCName(true)`；
- OSS Java SDK V2 依赖（bom 锁版）：`com.aliyun:alibabacloud-oss-v2:0.5.x`（preview 期 SDK，锁版本不浮动；
  包名 `com.aliyun.sdk.service.oss2.*`，注意其传递引入 jackson-dataformat-xml，与现有 Jackson 版本对齐验证）。

### 20.4 配置项（t_sys_config，复用 §16.5.1 加密体系；env 优先覆盖）

| config_key | 加密 | env 覆盖 | 说明 |
|------|------|------|------|
| `storage.image.strategy` | 明文 | `STORAGE_IMAGE_STRATEGY` | `base64`（默认）/ `oss` |
| `oss.access_key_id` | **密文** | `OSS_ACCESS_KEY_ID` | RAM 用户 AK（建议仅授目标 bucket 的 PutObject/GetObject） |
| `oss.access_key_secret` | **密文** | `OSS_ACCESS_KEY_SECRET` | 同上 |
| `oss.region` | 明文 | `OSS_REGION` | 如 `cn-beijing` |
| `oss.bucket` | 明文 | `OSS_BUCKET` | 目标 bucket 名 |
| `oss.endpoint` | 明文（可选） | `OSS_ENDPOINT` | 显式 endpoint；与 customDomain 二选一，customDomain 优先 |
| `oss.custom_domain` | 明文（可选） | `OSS_CUSTOM_DOMAIN` | 自定义域名（含 https://），作 URL 前缀 + SDK useCName |
| `oss.key_prefix` | 明文 | — | 对象 key 前缀，默认 `teapot-ai/chat-images/` |

- yml 行为开关：`teapot.ai.storage.oss.enabled`（默认 true；false 时即使凭证齐备也不启用）；
- 凭证齐备判定：`access_key_id ∧ access_key_secret ∧ region ∧ bucket`（同 `AgentRunConnection.configured()` 模式）；
- 读回显一律脱敏：AK/Secret 仅 `configured` 布尔 + 末 4 位掩码（`ConfigCryptoService.mask`）。

### 20.5 REST API

| 接口 | 权限 | 说明 |
|------|------|------|
| `GET /api/config/storage-options` | developer+ | `{strategy, effectiveStrategy, ossEnabled, ossConfigured, region, bucket, endpoint, customDomain, keyPrefix, accessKeyIdMasked, accessKeySecretMasked}` |
| `PUT /api/config/storage` | admin | 写 strategy + OSS 各项；密文字段 AES-GCM 入库；非 null 才更新（留空不修改，同 `/api/config/sandbox`） |
| `POST /api/chat/image/upload` | 登录用户（/api/chat/* 覆盖） | multipart `file`；服务端复检 ≤5MB + MIME 白名单（§19.5）+ magic number；经 `ImageStorageRouter` 返回 `{url, strategy}`。base64 策略下亦可用（返回 data URL），供统一入口与联调 |

### 20.6 变更清单

**后端（teapot-ai-core）**
- bom + core pom：新增 `alibabacloud-oss-v2`（§20.3）；
- 新增 `storage` 包：`ImageStorageStrategy` / `InlineBase64StorageStrategy` / `OssImageStorageStrategy`
  / `ImageStorageRouter` / `OssClientManager` / `OssConnection`（env+DB 合并解析，同 AgentRunConnection 结构）；
- `TeapotAiProperties` 增 `storage` 嵌套配置（oss.enabled、keyPrefix 默认值）；
- `ConfigController` 增 `storage-options` / `storage` 两端点（§20.5）；
- 新增 `ChatImageController`（`/api/chat/image/upload`）或并入 ChatSessionController；
- `ChatSessionService` **无需改**：URLSource 分支已原样返回 URL（本期图片外置修复已铺路），历史回显天然兼容。

**前端（teapot-ai-web）**
- `imageUpload.ts`：`imageCustomRequest` 启动时拉一次 `storage-options` 并缓存：
  `effectiveStrategy=oss` → 压缩产物改 Blob 直传 `/api/chat/image/upload`，`response.url` = OSS URL；
  `base64` → 维持现链路（零往返）；压缩参数与限额不变（§19.5）；
- `aguiBridge.ts` / `sessionBridge.ts` **零改动**：url 源发送分支与 http URL 直渲染分支均已就绪；
- `api/config.ts`：增 `storageOptions()` / `saveStorageConfig()`；
- 存储配置 UI 落在 **系统配置页的「存储」分区**（§21.3，不再放 AgentDetail）：策略 Radio（base64 内联 / OSS 对象存储）
  + OSS 凭证表单（AK/Secret `Input.Password` 留空不修改、region、bucket、endpoint、customDomain、keyPrefix）
  + 状态横幅（OSS：已接入/未接入 · Key 掩码，交互同 E2B 凭证区，§16.11）。

**DB**：无新表（复用 t_sys_config）。

**nginx/部署**：无改动（上传走既有 /api/ 块，20m body 限额已足）。

### 20.7 兼容与降级

| 场景 | 行为 |
|------|------|
| strategy=oss 但凭证不齐/开关关闭 | 生效策略回落 base64 + warn 日志；前端按 effectiveStrategy 走内联链路 |
| OSS 上传失败（网络/权限/域名不通） | 上传端点报错，前端 onError 提示用户；**不静默改发 base64**（避免两种承载混杂于同一会话语义不清），由用户重试或管理员切策略 |
| 存量 base64 会话 | 零迁移：Base64Source 走既有取图端点，URLSource 直链渲染，两者并存 |
| 运行中切换策略 | 仅影响新消息；历史消息按各自 source 类型回放 |
| 会话删除/清空 | 一期不联动清理 OSS 对象；建议 OSS 控制台配生命周期规则（如 90 天前缀过期），联动清理列二期 |
| 旧前端 + 新后端 | 旧前端不感知 storage-options，永远走 base64 链路，零影响 |
| 回滚 | 策略切回 base64 即下线 OSS 链路；依赖与表结构可留 |

### 20.8 安全与合规

- AK 密文入库（AES-GCM，主密钥 TEAPOT_SECRET_KEY 仅环境变量，§16.5.1）；GET 回显脱敏；审计只记 key 名；
- RAM 最小权限：建议仅授目标 bucket `oss:PutObject/GetObject`，不用 AliyunOSSFullAccess；
- bucket 保持私有，仅对象级 public-read；key 含 UUID 不可枚举；
  **敏感性告知**：获得 URL 即可读取图片，涉密场景应保持 base64 策略（配置页需提示文案）；
- 上传端点服务端复检体积/MIME/magic number，防绕过前端限额；
- **阿里云合规（重要）**：2025-03-20 起新开通 OSS 的中国内地地域 bucket，默认外网域名禁用数据面 API，
  必须自定义域名（CNAME + HTTPS 证书）访问 → `oss.custom_domain` 为一等配置项；
  若目标 bucket 属此类，未配 customDomain 时上传将失败，需在配置页提示引导。

### 20.9 验收标准

1. 存储配置页填齐 OSS 凭证并切 `oss`：发图对话成功，模型能看图作答；DB 中该会话 state 内图片为 `URLSource`（url 指向 OSS，无 base64）；
2. OSS 控制台可见对象（前缀正确、public-read）；消息 JSON/AG-UI 请求体不含 base64；
3. 跨设备/刷新后历史回显正常（图片直链加载）；同会话追问“刚才图里…”正常；
4. 删除 AK 配置后发图：自动回落 base64，对话不中断（日志有 warn）；
5. 切回 base64：新消息恢复内联，历史 OSS 图片仍可回显；
6. 无凭证时 `/api/chat/image/upload` 在 oss 策略下返回明确业务错误；越权（未登录）401；
7. 超 5MB/非白名单 MIME 被服务端拦截。

### 20.10 实施任务分解

| # | 任务 | 模块 | 预估 |
|---|------|------|------|
| T1 | bom/core 引入 oss-v2 依赖 + OssClientManager + OssConnection（env+DB 合并） | 后端 | 0.5d |
| T2 | 策略接口 + 两实现 + ImageStorageRouter + 上传端点 | 后端 | 0.5d |
| T3 | ConfigController storage-options/storage 端点 + 脱敏回显 | 后端 | 0.3d |
| T4 | imageUpload 双链路 + storage-options 缓存 | 前端 | 0.5d |
| T5 | 存储配置 UI（随 §21 系统配置页「存储」分区落地） | 前端 | 0.5d |
| T6 | 阿里云侧准备（RAM 用户/bucket/CNAME 域名与证书）+ 部署验证 | 运维 | 0.5d |
| T7 | §20.9 全量验收 | 全员 | 0.5d |

### 20.11 风险与待确认

| # | 风险 | 缓解 |
|---|------|------|
| 1 | oss-v2 SDK 处 preview 期，API 可能变动 | bom 锁版本；实现前以构件 javap 核实 PutObjectRequest/ACL 设置 API |
| 2 | 内地新 bucket 默认域名数据面禁用（§20.8） | customDomain 一等配置 + 配置页提示；运维清单含 CNAME/证书 |
| 3 | DashScope 回源 OSS URL 的连通性/频率 | 验收项 1 实测；同地域公网直链，预期无碍 |
| 4 | public-read 敏感性 | 配置页明示；涉密保持 base64；二期可加预签名模式（重新签名回放 URL） |
| 5 | OSS 对象无清理 → 存储成本累积 | 生命周期规则建议值写入配置页提示；二期联动 clear |

### 20.12 OSS 多记录管理（2026-08-19 追加，已实施）

背景：单套 OSS 凭证无法满足多环境/多 bucket 场景（如生产与测试分桶、不同账号），改为多条连接记录 + 激活其一。

**数据模型**：新表 `t_storage_config`（V7 迁移）——name 唯一键 + AK/Secret（AES-GCM 密文）+ region/bucket/endpoint/custom_domain/key_prefix/remark；激活记录名存 `t_sys_config.storage.image.active`，策略仍存 `storage.image.strategy`。

**三级解析（OssConnection）**：env 应急覆盖 > 激活记录（StorageConfigService.getActivePlain，带轻量缓存，变更即失效）> 旧 `oss.*` 单键（向后兼容）；`configured()` 语义不变，OssClientManager 指纹缓存/上传端点/aguiBridge 均零改动。

**REST API（写仅 admin，读同 §20.5 授权）**：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/config/storage-list | 记录列表（AK/Secret 只回 accessKeyConfigured 布尔）+ active |
| POST | /api/config/storage-record | 新建（name 唯一，AK/Secret/Region/Bucket 四项必填，凭证加密入库） |
| PUT | /api/config/storage-record | 按 name 更新（AK/Secret 留空不修改） |
| DELETE | /api/config/storage-record/{name} | 删除（激活中的记录禁删，先切换再删） |
| PUT | /api/config/storage | 增 `active` 字段：切换激活记录（Service 校验凭证齐备）；兼容旧单键字段写入 |

storage-options 出参增 `active`。审计只记记录名不记凭证（§14.6）。

**前端（系统配置-存储分区）**：策略 Radio + 激活记录 Select（选 oss 时必选）；下方记录表（名称/Region/Bucket/凭证/更新时间 + 激活/编辑/删除），新建/编辑走 Modal（编辑态 AK/Secret 留空不修改）。imageUpload 双链路无感知（仍只看 effectiveStrategy）。

**降级**：未设激活记录且无旧单键 → ossConfigured=false → 回落 base64（同 §20.7）。

---

## 21. 系统配置菜单（admin 一站式管理台）

目标：新增顶导航「系统配置」入口（admin 专属），集中管理模型 / 用户 / 存储（OSS）/ 沙箱凭证；
Agent 配置页（AgentDetail）不再内嵌全局凭证表单，改为**下拉选择 + 状态摘要 + 跳转链接**。

### 21.1 菜单与路由

- 顶栏 Segmented 导航：原「模型」「用户」两个 admin 入口合并为单一「系统配置」项；
- 路由 `/system/:section`，section ∈ `models | users | storage | sandbox`，默认 `models`；
  旧路由 `/models`、`/users` 保留为重定向（书签兼容）；
- 页面布局复刻 AgentDetail 左侧胶囊菜单风格（glass-card），移动端为顶部切换；
- RBAC：前端 `RequireRole admin` 包裹 `/system/*`；后端权限不变
  （GET `sandbox-options`/`storage-options` developer+，写 `/api/config/*` admin 独占，既有规则覆盖）。

### 21.2 分区内容

| 分区 | 内容 | 复用 |
|------|------|------|
| 模型 | 现有模型管理页整体迁入 | `Models.tsx` 组件不动 |
| 用户 | 现有用户管理页整体迁入 | `Users.tsx` 组件不动 |
| 存储 | §20 策略 Radio + OSS 凭证表单 + 接入状态 | 新增 |
| 沙箱 | 全局接入凭证表单（E2B + AgentRun 双链路），自 AgentDetail 平移 | 逻辑复用 `sandboxOptions`/`updateSandboxConfig` |

### 21.3 AgentDetail 下拉化改造

- Sandbox 分区：删除「全局接入凭证（admin）」表单卡片，替换为接入状态摘要
  （已接入/未接入 · 链路 · Key 掩码）+「前往系统配置」按钮（admin 可见）；
- 新增 **沙箱链路下拉**（feature.sandbox.link）：`自动（跟随全局）` / `e2b` / `agentrun`，
  选项可用性由 `sandbox-options` 的 e2bConfigured / agentrun 凭证状态标注禁用态；
- Model 已是下拉（modelPresets），保持不变；
- 存储策略为全局配置，不出现在 AgentDetail（由系统配置页管理）。

### 21.4 后端配套（Agent 级沙箱链路覆盖）

- `AgentFeature.Sandbox` 增 `link` 字段（auto/e2b/agentrun，缺省 auto）；
- `AgentRegistry.resolveSandboxLink` 增 agent 级参数：agent 显式指定的链路可用则用之，
  不可用回落自动路由（优先级：agent 级 link > 全局 `teapot.ai.sandbox.link`）；
- 其余端点零改动（写 feature 走既有 AgentService update 通道，无新表无迁移）。

### 21.5 验收

1. admin 顶栏见「系统配置」，四个分区均可达；developer/普通用户不可见且直访路由 403；
2. 旧 `/models`、`/users` 重定向到对应分区；
3. AgentDetail Sandbox 分区无凭证表单，链路下拉可选且保存后 feature.sandbox.link 落库；
4. agent 指定 e2b 而 e2b 凭证缺失 → 回落自动路由并 warn（日志可见）。

---

## 22. 存储/沙箱载体按 Agent 选择（记录化 + feature 落库，2026-08-19 修订 §20/§21）

目标：把「图片存储载体」与「沙箱承载」从全局策略下放为 **Agent 级选择**，全部落在
`t_agent.feature` 中；系统配置页只维护连接记录（OSS / 沙箱均多记录 CRUD），不再有全局策略单选与全局激活概念。

### 22.1 图片存储载体按 Agent 选择（修订 §20）

- 系统配置-存储分区：移除「图片存储策略」Radio（§20.1 全局策略入口废弃，存量字段保留仅作文本兼容），
  仅保留 **OSS 连接记录表**（§20.12 多记录，AK/Secret AES-GCM 加密入库，列表只回凭证布尔）；
- AgentDetail **Basic Info** 新增「图片存储载体」下拉：`默认 Base64` + 各条 OSS 记录；
  落库 `feature.storage = { mode: 'base64' }` 或 `{ mode: 'oss', storageRecord: <记录名> }`；
- 上传链路按 Agent 路由：前端 `imageCustomRequestFor(agentKey)` →
  `GET /api/chat/image/strategy?agentKey=` 探测 / `POST /api/chat/image/upload?agentKey=` 上传（探测结果按 agentKey 缓存）；
- `ImageStorageRouter.effectiveStrategy(agentKey)`：feature.storage 存在 → mode=oss 且记录存在+凭证齐备+yml 开关开才生效 oss，否则 base64 并 warn；
  feature.storage 缺省（存量 Agent）→ 回落全局策略（§20.7 原语义）；
- 会话消息中已存的 base64 图片不受影响，渲染端照常展示；
- RBAC：新增轻量名单端点 `GET /api/config/storage-record-names`（name/region/bucket）入 developer/viewer resource-list；写接口仍 admin 独占。

### 22.2 沙箱连接记录 + Agent 必选承载（修订 §16/§21）

- 新表 `t_sandbox_config`（迁移 `V8__sandbox_config.sql`）：name 唯一键，`link_type ∈ e2b|agentrun`，
  e2b 链路列（api_key/api_base_url/domain/default_template）与 agentrun 链路列（api_key/account_id/region/default_template/mcp_server_url）；敏感列 AES-GCM 加密；
- 系统配置-沙箱分区：由全局凭证表单改为 **沙箱连接记录表**（多记录 CRUD，链路 Tag + 凭证布尔，编辑留空不修改敏感列）；
  E2B 链路仅填 Region（唯一变量），前端派生 API Base URL（`https://api.<region>.e2b.fc.aliyuncs.com`）与 Domain（`<region>.e2b.fc.aliyuncs.com`）入库，库内仍存完整 URL；
  新增端点 `GET /api/config/sandbox-list`、`POST/PUT /api/config/sandbox-record`、`DELETE /api/config/sandbox-record/{name}`、`GET /api/config/sandbox-record-names`；
- AgentDetail Sandbox 分区：启用开关开启后 **必选**一条沙箱记录（`feature.sandbox.sandboxRecord`），原「沙箱链路」下拉废弃——链路由所选记录的 `linkType` 决定；
- `AgentRegistry.applySandbox` 记录优先：引用记录且凭证齐备 → 按记录链路装配（模板解析链：feature.templateName > 记录默认模板 > 全局默认）；记录不存在/凭证不齐 → 降级无 shell 并 error；
  存量 Agent（无 sandboxRecord）回落全局链路原语义（`feature.sandbox.link` + `agentRunConfigured` 门控）；
- 记录删除不阻止：引用它的 Agent 运行期降级无 shell 并 warn（前端 Popconfirm 已提示）；
- `AgentFeature.validate` + `AgentService.validateFeatureRecords` 双重校验：oss 模式记录存在且 AK/Secret/Region/Bucket 齐备；sandbox 记录存在且对应链路凭证齐备。

### 22.3 Skill 同步按钮下沉到 git 卡片级

- Skill 列表页顶部 Git 状态条移除全局「立即同步」按钮；
- 「立即同步」出现在每个 `source=git` 的 skill 卡片上（同 git 来源卡片均可见，如 rising-sun-rules）；
- 接口不变：`POST /api/skill/git/sync` 仍为仓库级同步（git 来源同仓，卡片级仅是入口位置变化），权限 developer+。

### 22.4 验收

1. 存储页无策略 Radio，仅 OSS 记录表；沙箱页为记录表（双链路 CRUD，凭证不回显）；
2. AgentDetail Basic Info 可选 base64/某条 OSS 记录，保存后 feature.storage 落库；上传按所选载体生效；
3. Agent 启用沙箱不选记录无法保存；选记录后按记录链路装配，模板回落链正确；
4. 存量 Agent（未配 storage/sandboxRecord）行为不变，回落全局链路；
5. Skill 页全局同步按钮消失，git 卡片上可见「立即同步」且同步后列表刷新。

### 22.5 AG-UI 沙箱兼容修复（2026-08-19 线上故障）

故障：启用沙箱的 Agent 对话报错 `AGUI_INTERRUPT_CONTRACT_ERROR: Thread already has an active run`，同会话后续消息全部被拒。

根因链：
1. harness 的 `SessionSandboxStateStore` 用带 `/` 的 slot（如 `sandbox/user/<uid>`）作为 sessionId 读写沙箱状态；
   而 `MysqlAgentStateStore.validateSessionId` 拒绝含路径分隔符的 ID（文件型 Store 语义）→ 沙箱状态读写抛异常，打断 run 收尾，
   `AguiResumeCoordinator.activeRunsByThread` 残留僵尸 run，同 thread 后续请求全部被合约拦截；
2. AG-UI starter 在异步线程池回调 `AguiRuntimeContextResolver`，`ContextUtil`（ThreadLocal）不传播 → userId 恒为 anonymous，
   沙箱 USER 隔离与会话状态归属失真。

修复：
- `LenientMysqlAgentStateStore`（core config 包，继承官方类重写 protected `validateSessionId`）：仅保留空值/长度校验，
  允许路径分隔符（MySQL 键为不透明字符串，无路径语义）；`AgentScopeConfig.agentStateStore` 改用该子类；
- `TeapotRuntimeContextResolver` 注入 JwtService：ContextUtil 无值时从请求头 `Authorization: Bearer` 补解析 uid（/agui/** 已经过滤器验签）；
- 残留僵尸 run 为内存态，重启服务即清除。

### 22.6 AG-UI 合约冲突静默重试（2026-08-26 线上复现）

故障：连续对话再次出现 `AGUI_INTERRUPT_CONTRACT_ERROR: Thread already has an active run`（与 §22.5 不同根因）。

根因链（digit-tim，E2B 沙箱 + LOCAL_SNAPSHOT）：
1. `AguiRequestProcessor` 的 `finishRun` 挂接在 adapter 事件流 `doFinally`：回复文本流式结束后，
   harness 尾部后置处理（记忆 flush/整合、会话持久化、沙箱快照）仍在同步执行，实测最长约 3.6min（PRE_CALL→POST_CALL）；
2. 此间用户已看到完整回复并发送下一条 → `AguiResumeCoordinator.beginRun` 合约拦截，
   事件流回 `RUN_STARTED → RUN_ERROR(AGUI_INTERRUPT_CONTRACT_ERROR) → RUN_FINISHED`；
3. 尾部还会触发 `session-tree-mirror` 线程异常（沙箱已释放，`No active sandbox`），属无害噪声。
   starter 无配置可让尾部异步化或提前 finishRun。

修复（前端 `chat/aguiBridge.ts`，无后端改动）：
- `createAguiFetch` 内置合约冲突探测（`peekContractError`：扫 SSE 首段，命中特征串即定性；
  超 8KB 未命中必为正常业务流，回放已消费字节放行）；
- 命中后静默退避重试（首等 3s，×1.6 递增封顶 30s，总预算 5min），UI 表现为持续「思考中」；
  尾部终会完成，重试必能接上；预算耗尽才重建错误事件落卡片（中文可读提示）；
- 被拒请求未触达 `beginRun` 之后的状态变更，重试同一条消息幂等安全；等待期可被模板 AbortSignal 打断。

---

## §23 头像上传（Agent + 用户，OSS 记录承载）

### 23.1 设计

- Agent 头像与用户头像均为 OSS 对象直链，分别落 `t_agent.avatar` / `t_user.avatar`（V9 迁移，VARCHAR(512)）；NULL = 未设置，前端回落首字母占位。
- 承载记录固定为 yml 配置 `teapot.ai.storage.avatar-record`（默认 `oss-cn-beijing.aliyuncs.com`，引用 t_storage_config.name）；不参与 §22.1 的按 Agent 选择。记录不存在/凭证不齐/OSS 总开关关闭时明确报错，不回退 base64（列长不允）。
- 对象 key：`teapot-ai/avatars/{agent|user}/{id}-{时间戳}.{ext}`（avatar-key-prefix 可配）；时间戳使换头像后不命中旧缓存；对象级 public-read + 一年缓存头，同 §20.8。

### 23.2 接口与权限

| 端点 | 说明 | 权限 |
|---|---|---|
| `POST /api/avatar/agent/{agentKey}` | multipart file → 校验 → OSS → t_agent.avatar | developer/admin |
| `POST /api/avatar/user` | multipart file → 校验 → OSS → t_user.avatar（仅本人） | 全部登录角色 |

服务端复检：≤2MB、MIME 白名单（JPEG/PNG/WebP/GIF）+ magic number，与 §20.5 对话图片上传一致；审计 `agent.avatar` / `user.avatar`。

### 23.3 前端

- 用户头像：顶栏用户菜单「更换头像」（含移动端 Drawer），上传后 zustand setUserPatch 回填；`LoginResponse.user` 携带 avatar。
- Agent 头像：AgentDetail Profile 大头像卡点击更换（底部遮罩提示），页头与 Agents 列表卡片有头像图则展示。

### 23.4 验收

1. AgentDetail 上传头像后 Agents 卡片/页头/Profile 卡均展示；刷新后仍在（落库）；
2. 顶栏更换用户头像后即时生效，重新登录仍在；
3. viewer 可传本人头像、不可传 Agent 头像（RBAC 403）；非图片/超 2MB 被拒；
4. OSS 对象位于 `teapot-ai/avatars/` 前缀，直链 200 可访。

---

## §24 Channel 连接器（Agent 对外消息通道：钉钉 / Discord）

### 24.1 背景与目标

现状对话入口仅 Web AG-UI（§6.2）。本节基于 AgentScope 2.0.1 的 Gateway/Channel 体系（`io.agentscope.harness.agent.gateway.*`，harness 包内置）为 Agent 增加**对外连接器**：每个 Agent 可配置自己的 channel（钉钉机器人、后续飞书/企微等），用户在 IM 里直接 @ 机器人对话，复用该 Agent 的模型/Skill/人设。

SDK 能力盘点（字节码级已验证）：
- `GatewayBootstrap.builder().agent(id, HarnessAgent).channel(Channel).mainAgent(id).build()` + `start()/stop()`：channel 生命周期 init/start/stop 由 gateway 托管，同 session 并发排队、按 peer 会话映射；
- 钉钉扩展 `io.agentscope:agentscope-extensions-channel-dingtalk:2.0.1`：`DingTalkChannel.fromProperties(channelId, ChannelConfig, props)`，Stream 协议（出站 WebSocket 长连，**无需公网 IP/回调域名**，与 teapot 服务器现状匹配）；props 消费 `appKey/appSecret/robotCode`（apiBase/oapiBase/streamRegisterUrl 有默认值）；内置幂等去重（IdempotencyStore）与机器人自环防护（BotLoopGuard）；
- `ChannelConfig.builder(channelId)`：`defaultAgentId` / `dmScope`（MAIN、PER_PEER、PER_CHANNEL_PEER、PER_ACCOUNT_CHANNEL_PEER，会话粒度）/ `bindings`。
- **Discord（§24 修订，§24.10）**：Java SDK 无官方 Discord 扩展（Maven Central 2.0.1 仅 dingtalk/feishu/wecom/github/gitlab），自实现 harness `Channel` 接口 + JDA 5.6.1 Gateway WebSocket 出站长连（同钉钉 Stream，**无需公网回调**）。

### 24.2 总体架构

```
钉钉云 ──Stream WS(反连)──▶ DingTalkChannel ─▶ HarnessGateway ─▶ HarnessAgent(长驻)
                                                                    │ 共享组件
Web ──AG-UI SSE──▶ TeapotAguiAgentRegistrar ─▶ AgentRegistry(每轮重建) ─┤ model/skill/sandbox/stateStore
                                                                    ▼
                                                      MysqlAgentStateStore（agentscope_sessions）
```

- **双链路独立实例**：Web 链路保持现状（AgentRegistry 每轮重建，§6.1 修订）；channel 链路需要**长驻** HarnessAgent（gateway 持有实例做会话排队），由新增 `ChannelHub` 装配，与 AgentRegistry 共享 build 逻辑（抽出 `AgentAssembler`，同一份 model/skill/sandbox/stateStore 装配规则）。
- **会话域隔离**：channel 会话的 sessionId 由 gateway 生成（gw-…），与 Web 的 t_chat_session UUID 不同域；channel 对话历史落 agentscope_sessions 但**不进用户自己的 Web 会话面板**（避免双端互串）；admin 可在 Agent 配置面板「会话历史」统一查看全量用户对话（§24.9）。
- **身份归属**：channel 链路 userId 统一使用**渠道类型名称**（如 `discord`，v1 修订：原设计为钉钉 peer/Discord snowflake），非平台用户；沙箱状态按该 userId 分槽（USER scope 时整条 channel 链路共享一槽，SESSION scope 仍按会话细分）。**channel 与沙箱可同时启用**（原 v1 互斥约束已解除）；已知限制：钉钉为 SDK 官方 channel，senderId 由 SDK 控制无法覆盖为渠道名称，仅 Discord（自实现 channel）生效。

### 24.3 数据模型

**新表 `t_channel_config`（V10 迁移，镜像 t_sandbox_config 模式）：**

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT UNSIGNED PK | 自增 |
| name | VARCHAR(64) UK | 记录名，Agent feature 引用 |
| channel_type | VARCHAR(16) | `dingtalk` / `discord` / `github`（§24 修订，枚举留扩展） |
| app_key | VARCHAR(128) | 钉钉应用 ClientID（明文）；discord 不使用（空）；github 复用为可选 bot 账号 login（防环） |
| app_secret | TEXT | 钉钉 ClientSecret / **Discord Bot Token** / **GitHub PAT Token**（§24 修订），AES-GCM 密文（同 §22.2 加密方案） |
| robot_code | VARCHAR(128) | 机器人 robotCode，可空（缺省同 appKey）；discord/github 不使用 |
| webhook_secret | VARCHAR(512) | GitHub webhook secret，AES-GCM 密文（V11 新增，校验 X-Hub-Signature-256）；其他渠道不使用 |
| remark / updated_by / created_at / updated_at | | 同 t_sandbox_config |

**AgentFeature 新增 `channel` 命名空间**（与 sandbox/storage 同构，未知命名空间保留机制天然兼容）：

```json
{ "channel": { "enabled": true, "channelRecord": "dingtalk-main", "dmScope": "PER_CHANNEL_PEER" } }
```

- `enabled`：是否接入连接器；`channelRecord`：引用 t_channel_config.name（启用必填，模式同 §22.2 sandboxRecord）；`dmScope`：缺省 `PER_CHANNEL_PEER`（每个群/每个私聊各自一个会话），可选 `PER_PEER`（同一人跨群合并）/ `MAIN`（全机器人单会话）。
- validate 追加：enabled 必须选记录；记录不存在/凭证不齐在 AgentService 保存时拦截（同沙箱）；channel 与沙箱可同时启用（§24.2 修订）。

### 24.4 后端组件

| 组件 | 职责 |
|---|---|
| `ChannelConfigDO/Mapper`（sqlclient XML） | t_channel_config CRUD |
| `ChannelConfigService` | CRUD + 加解密 + `getPlain` + `configured(record)`（钉钉 appKey+appSecret 齐备；discord 仅 app_secret，§24 修订）；删除被引用记录时拒绝 |
| `ChannelController`（/api/channel-config） | list/create/update/delete + `GET /api/channel-config/registry`（轻量 name+type 下拉名单，模式同 §22.2）；全部 admin（RBAC yml 追加 `/api/channel-config/*`） |
| `AgentAssembler`（自 AgentRegistry.build 抽出） | 同一份装配规则供 Web/channel 双链路复用 |
| `ChannelHub` | `Map<agentKey, GatewayBootstrap>`：`start(agentKey)`（读 feature+记录 → build agent → 建 channel → gw.start）、`stop(agentKey)`（gw.stop）、`restart(agentKey)`；app_secret 只在此处解密消费 |
| 生命周期接线 | `ApplicationReadyEvent` 扫描全部启用 Agent，channel.enabled=true 者 start（单个失败仅告警不阻断启动）；`@PreDestroy` 全部 stop；AgentService.update/delete 及 ChannelConfig 变更 → 相关 Agent restart；Agent 停用同 delete 处理 |
| `ChannelFactory` | channel_type → Channel 构造（dingtalk 官方扩展 / discord 自实现 JDA 适配器 / github 自实现 webhook 适配器，§24.10/§24 修订），后续飞书/企微只加分支 |

钉钉 channel 构造：`DingTalkChannel.fromProperties("dingtalk-"+name, ChannelConfig.builder(channelId).defaultAgentId(agentKey).dmScope(...).build(), Map.of("appKey",..,"appSecret",..,"robotCode",..))`。

Discord channel 构造（§24.10）：`new DiscordChannel("discord-"+name, ChannelConfig.builder(channelId).defaultAgentId(agentKey).dmScope(...).build(), botToken, onlyAtReply=true)`。

**回复时效**：钉钉 Stream 要求及时 ack，长回复由扩展 SDK 的出站客户端处理（v1 不实现“打字中”卡片，列为后续）。

### 24.5 前端

- `api/channelConfig.ts`：CRUD + registry。
- **系统配置 → 新增「连接器」页**（admin，镜像沙箱配置页）：记录列表（name/类型/appKey/robotCode/备注，secret 回显掩码 `****`）、新建/编辑弹窗（按 channelType 动态切字段：钉钉 AppKey/AppSecret/RobotCode，Discord 仅 Bot Token，§24.10）、删除前提示引用检查。
- **Agent 编辑 → 「连接器」卡片**（镜像沙箱卡片）：启用开关 + 记录下拉（registry）+ dmScope 单选（每群独立/每人合并/全局单会话）；可与沙箱同时启用（§24.2 修订）。
- 对话页/会话面板无改动（channel 历史不进 Web，§24.2）。

### 24.6 范围与限制（v1）

1. 钉钉 Stream 机器人 + Discord Bot（§24.10）；文本进、markdown 出；图片/文件透传不做（后续 §）；
2. 一个连接器记录（一个钉钉应用）只绑一个 Agent；同 Agent 只绑一条记录（多机器人/多 Agent 路由留后续）；
3. 单实例部署假设：Stream 同 clientId 多连接会负载均衡分流，**多副本部署前需引入分布式协调**，文档明示；
4. channel 历史不展示于**用户**的 Web 会话面板；但 admin 可在 Agent 配置面板查看全量用户对话历史（§24.9）；无渠道级白名单（钉钉侧应用可见性控制）；
5. 群聊 @ 机器人触发（SDK 默认行为），私聊直发。

### 24.7 验收

1. 系统配置新建 dingtalk 记录（appKey/appSecret/robotCode）→ 列表回显 secret 掩码；DB 列为 AES-GCM 密文；
2. Agent 启用 channel 并选记录后重启服务，日志见 channel start；钉钉私聊机器人收到符合人设的回复，同用户第二轮有上下文；
3. 群 A 与群 B 对话互不串扰（dmScope 默认）；
4. 凭证错误的记录：启动告警但不阻断其他 Agent；
5. Agent 编辑页改绑/停用 channel → 新绑定即时生效、旧连接断开；删除被引用记录被拒；
6. channel 与沙箱同时启用可保存，channel 消息触发沙箱工具执行正常（userId=渠道名称）；viewer/developer 调 channel-config 接口 403。

---

### 24.8 风险
- ~~`GatewayBootstrap` 接受预建 HarnessAgent 的 builder 重载需 spike 验证~~ **已验证**：`Builder.agent(String, HarnessAgent)` 与 `agent(String, Consumer<HarnessAgent.Builder>)` 两种重载均存在（2.0.1 字节码），首选预建实例方案；
- 长回复超出钉钉 ack 窗口时的中间态展示（v1 容忍，后续加 AI 卡片流式）；
- channel 链路 RuntimeContext 由 gateway 覆盖身份字段，如需注入平台属性需 ChannelRuntimeContextResolver（v1 不用）。

---

### 24.9 Agent 全量会话历史（admin 视图）

**动机**：channel 会话不进用户 Web 会话面板（§24.2），运营/排障需要在 Agent 维度看到全部用户（Web + 各渠道）的对话。

**数据模型**：新表 `t_channel_session`（V10 迁移，与 t_channel_config 同批）：

| 列 | 说明 |
|---|---|
| agent_key | 所属 Agent |
| user_id | gateway 身份（钉钉 peer：staffId/conversationId） |
| session_id | gateway 生成的 gw-… 会话 id |
| channel_type | dingtalk（后续渠道枚举） |
| title | 首条用户消息截断 50 字（同 Web 会话标题规则） |
| created_at / last_active_at | 首次/最近活跃时间 |
| UNIQUE KEY (user_id, session_id)，索引 (agent_key, last_active_at) |

Web 会话已有 t_chat_session 索引，无需新表；两个索引在查看层 union。

**写入链路**：新增 `ChannelSessionIndexMiddleware`（harness Middleware 体系），**仅装配到 channel 链路 Agent**（ChannelHub build 时追加，Web 链路不受影响）：每次调用时从 RuntimeContext 取 userId/sessionId，upsert 索引行；title 仅首次写入（首条用户文本截断）。agentscope_sessions 本身按 (userId, sessionId) 存状态，消息体不双写。

**接口**（全部 admin，RBAC yml 追加；Service 层 requireAdmin 兜底）：

| 端点 | 说明 |
|---|---|
| `GET /api/agent/{agentKey}/session-history` | union 两索引：返回 user（Web 平台用户 / 渠道 peer）、title、source（web/dingtalk/discord）、lastActiveAt，按活跃时间倒序，分页 |
| `GET /api/agent/{agentKey}/session-history/{userId}/{sessionId}/messages` | 复用现有消息抽取逻辑（ChatSessionService.messages 的 block→Item 转换抽为共享组件），`stateStore.get(userId, sessionId)` 回放，图片同样走取图端点引用；**不校验会话归属**（admin 专用，与用户端隔离接口分开） |

**前端**：

- Agent 编辑页左侧菜单新增「会话历史」分区（与 Skills 同级）→ 分区内**完全复用 chat 页整套模板**：只读 `AgentScopeRuntimeWebUI` 实例，左侧 `SessionPanel`（readonly：无 New Chat/删除，标题带 [Web]/[Discord]/[钉钉] 来源前缀与用户标识），右侧同款聊天面板（Markdown/深度思考/工具卡片/图片）；会话桥接 `createHistorySessionBridge`（复合 id=source|userId|sessionId，列表=session-history union，详情=session-history messages 回放，建/改/删均 no-op，`getSession(undefined)` 需空值兼容——模板初始化会调一次）；输入框 CSS 隐藏 + `beforeSubmit` 兜底拦截，进入分区自动选中首个会话；**options 必传 welcome**（模板 DefaultResponseRender 取 `v.welcome.avatar/nick`，缺失时 selector 抛错被兜底为 `{}` 当 React child 渲染致整树崩溃）；移动端（<992px）同 Chat 页：`hideBuiltInSessionList` + 顶栏 `#topbar-history-slot` Portal 历史按钮/Drawer；
- 仅 admin 可见该菜单项（角色判断隐藏）。

**验收（并入 §24.7）**：

7. 钉钉对话后 Agent 面板「会话历史」可见该渠道会话（来源=钉钉、标题=首条消息），点开可回放全文；Web 会话同样在列（来源=web）；
8. developer/viewer 访问 session-history 接口 403，前端按钮不可见；用户自己的会话面板仍看不到 channel 会话。

---

### 24.10 Discord 渠道（§24 修订）

**背景**：参考 Python 版 AgentScope 的 Discord channel（Gateway WebSocket 长连、无需公网回调）；Java SDK 2.0.1 无官方 Discord 扩展，故自实现 harness `Channel` 接口。

**依赖**：`net.dv8tion:JDA:5.6.1`（bom 管版本，core 引入）；只启用 `GUILD_MESSAGES / MESSAGE_CONTENT / DIRECT_MESSAGES` 三个 intent，`createLight` 低内存模式。

**实现（`DiscordChannel extends ListenerAdapter implements Channel`）**：
- `start()`：`JDABuilder.createLight(botToken, intents).build()` 异步建连，不阻塞应用启动；`ReadyEvent` 打日志；`stop()` 调 `jda.shutdown()`，生命周期由 ChannelHub/Gateway 托管，与钉钉一致；
- **Gateway 代理**：JDA 底层 nv-websocket-client 不走 JVM `-Dhttps.proxyHost`（仅 HTTP 客户端生效），经 `JDABuilder.setWebsocketFactory()` + `ProxySettings`（HTTP CONNECT 握手，非 SOCKS）显式配代理，由系统属性 `discord.proxy.host/port` 驱动（服务器 clash 7890，service ExecStart 注入）；未配置则直连（本地开发）；
- 入站：guild 频道默认 `only_at_reply=true`（仅 @ 机器人响应，对齐 py 版默认值；DM 始终响应），去掉自身 @ 提及后构造 `InboundMessage`（peer=频道 id，guild=服务器 id，senderId=渠道名称 `discord`，作为沙箱分槽与会话索引身份；回复路由走 peer.id 不受影响）；DM 同机制（peer=用户 id）；bot 自身/webhook 消息忽略（防环）；
- `dispatch()`：`ChannelRouter.resolveRoute(config, message)` → `gateway.run(...)`。**关键：HarnessGateway.run() 不自动投递回复**（字节码验证：仅记录 lastRouteBySession 并返回 Mono<Msg>），channel 必须在 Mono 上自行 `deliver(outboundAddress, reply)`，否则用户永远收不到回复（v1 曾因订阅用空消费者丢弃回复导致无回复 bug）；dmScope/会话映射由 router 按 ChannelConfig 处理，与钉钉同机制；
- 出站 `deliver()`：**注意 OutboundAddress.to 是复合字符串** `{channelId} {PeerKind.value}:{peerId}`（如 `discord-DC Bot :CHANNEL:123…`），真实 snowflake 在最后一个冒号后，需自行提取并做数字校验（直接传给 JDA 会抛 NumberFormatException）；多条 Msg 拼接后按 Discord 单条 **2000 字符上限自动分段**（优先换行处切）；目标为频道 id 直查，用户 id 则 `openPrivateChannel()` 私聊发送；**发送失败（代理线路抖动常见 SSLHandshakeException）指数退避重试 3 次（1.5s/3s/6s），重试耗尽记 ERROR，避免回复静默丢失**；
- 凭证：Bot Token 存 t_channel_config.app_secret 列（AES-GCM），`configured()` 按类型判定（discord 不看 app_key）。

**会话归属**：channel 链路入站 senderId 为渠道名称 `discord`（非平台用户，Discord 用户/频道 snowflake 仍存于 peer 用于回复路由）；索引写 t_channel_session（channel_type=discord），admin 会话历史 source 展示 Discord 徽章；可与沙箱共存（§24.2 修订）。

**接入步骤（运营）**：Discord Developer Portal 建 Application+Bot → Bot 页 Reset Token 拿 Bot Token（仅显示一次）→ 开启 **MESSAGE CONTENT INTENT**（否则收不到文本）→ OAuth2 URL Generator 勾 bot 权限（Send Messages / Read Message History）生成邀请链接拉进服务器 → 系统配置新建 discord 记录填 Token → Agent 启用。

**py 版平台开关的取舍**：`only_at_reply` 固定 true（v1 不暴露配置）；`show_thinking`/`show_tool_process` 不实现（v1 仅发最终文本，与钉钉一致）；交互式按钮审批不做（harness HITL 渠道化后续 §）。

### 24.11 GitHub channel（webhook 回调，§24 修订）

官方 `agentscope-extensions-channel-github` 随 2.2.0-RC2 发布，平台锁 2.0.1 不可用，故参考其源码自实现（模式同 Discord 自实现）：
- **入站**：GitHub 将 `issue_comment` / `pull_request_review_comment` 事件 POST 到 `POST /api/webhook/github/{记录名}`（`GitHubWebhookController`，rbac permit-list 免 JWT）；处理管线（对齐官方）：HMAC-SHA256 签名校验（X-Hub-Signature-256，常量时间比较，失败 401）→ 事件过滤（其他事件 204）→ 仅 `action=created` → 幂等去重（comment.id，内存 LRU 512）→ 防环（评论者 == 记录的 bot login，缺省回退 `user.type==Bot`）→ 映射 `InboundMessage`（peer=THREAD `owner/repo#number`，senderId=`github`）→ `dispatch`（202 受理异步）；
- **出站**：`deliver` 从 `OutboundAddress.to` 末段还原 `owner/repo#number`，以 PAT 身份 `POST /repos/{owner}/{repo}/issues/{n}/comments` 追加评论（issue 与 PR 会话评论共用端点；**GitHub API 服务器境内可直达，用 `HttpURLConnection` + 显式 `Proxy.NO_PROXY` 直连，不经 clash 代理**——JDK 21 的 java.net.http.HttpClient 不支持 `-Dhttp.nonProxyHosts`，且出海线路不稳）；
- **注册表**：`GitHubChannel` 静态 `Map<记录名, channel>`，start 注册 / stop 反注册，webhook 按记录名路由；仅当有 Agent 启用该记录时 channel 才存活（同其他渠道，由 ChannelHub 托管）；
- **凭证**：PAT Token 存 app_secret 列、webhook secret 存 webhook_secret 列（均 AES-GCM，V11 新增列）；app_key 列复用为可选 bot login；`configured()` 要求 token+webhookSecret 齐备；测试连接：`GET api.github.com/user` 验 PAT；
- **会话隔离**：peer=THREAD，dmScope 生效同其他渠道（缺省 PER_CHANNEL_PEER = 每 issue/PR 独立会话）；索引写 t_channel_session（channel_type=github）。

**接入步骤（运营）**：GitHub 建 bot 账号并生成 PAT（目标仓库 Issue/PR 评论权限）→ 系统配置新建 github 记录（PAT + 自定义 webhook secret）→ 将界面展示的回调地址填入仓库/组织 Settings → Webhooks（Content type=JSON，Secret 与记录一致，勾 Issue comments 与 Pull request review comments 事件）→ Agent 启用该记录。注：GitHub 要求回调地址公网可达（平台域名为 HTTP，GitHub 允许但会提示不安全）。

**测试连接**：`POST /api/channel-config/test`（仅 admin）轻量调平台 API 验凭证/网络，不落库不触发建连：Discord GET `users/@me`（200 有效/401 Token 无效），钉钉 `gettoken`（errcode=0 有效）；凭证留空时回落库内解密值（支持已保存记录直测与编辑弹窗空密测试）。前端入口两处：记录列表行「测试」+ 新建/编辑弹窗「测试连接」按钮。Discord 测试经 JVM 系统代理（clash）出海，钉钉/阿里云境内域名在 nonProxyHosts 直连；GitHub 同钉钉直连（代码层显式 `Proxy.NO_PROXY`，不受 nonProxyHosts 对 HttpClient 不生效的限制）。

**验收（并入 §24.7）**：

9. 系统配置新建 discord 记录（仅 Bot Token）→ 保存成功，类型列展示 Discord；缺 Token 保存被拒；
9.1 「测试连接」：正确 Token 返回成功（含 bot 名）；错误 Token 提示 401；代理不通时提示超时；钉钉凭证同理（errcode 回显）；
10. Agent 绑定 discord 记录启用后重启，日志见 Discord Gateway 建连成功；服务器频道 @ 机器人/DM 均收到回复，同会话第二轮有上下文；
11. 超长回复（>2000 字符）自动分段不截断报错；会话历史中该会话来源前缀为 Discord。

---

## 25. Agent 高级能力开关（MultiAgent / 记忆 / 压缩 / 计划模式，MVP）

基于 AgentScope 2.0.1 Builder 能力（javap 验证）开放四项配置的界面化：
- **MultiAgent（Subagent）**：agentconfig 新增「MultiAgent」菜单，单开关 `feature.multiagent.enabled`。缺省命名空间 = 启用；`{enabled:false}` 时装配调 `disableSubagents()` + `disableDynamicSubagents()`；
- **记忆**：agentconfig 新增「记忆」菜单，`feature.memory = {enabled, flushTrigger(always|never|throttled), flushThrottleMinutes}`。缺省 = 启用（官方两层语义：对话日志落盘 + MEMORY.md 整合注入，不显式配置即默认开启）；`enabled=false` 调 `disableMemoryHooks()` + `disableMemoryTools()`；flushTrigger 映射 `MemoryConfig.FlushTrigger.always()/never()/throttled(Duration)`（throttled 间隔 1–1440 分钟，保存时强校验）；
- **上下文压缩**：复用既有 `t_agent.compaction_trigger/compaction_keep` 列（-1 = 关），Tool & Advanced 新增「启用上下文压缩」总开关：关 → 提交 -1/-1，开且未填有效值 → 默认 30/10；该分区补上 Save 按钮；
- **计划模式**：`feature.runtime.enablePlanMode`（Basic Info 既有开关，后端 `enablePlanMode` 已接线，本次仅确认）；
- **请求级记忆/计划开关（参数传递）**：chat 界面右上角悬浮三态选择（跟随配置/开启/关闭，按 Agent 存 localStorage `teapot.memoryMode.<agentKey>`）→ `RunAgentInput.forwardedProps.memoryMode`；发送框底部操作栏（附件按钮附近）悬浮「计划」按钮（点击切换开启/跟随，存 `teapot.planMode.<agentKey>`）→ `forwardedProps.planMode` → `TeapotRuntimeContextResolver` 解析写 `AgentRuntimeHints`（ThreadLocal，AG-UI 整请求单线程，resolver 先于 agent 解析）→ `AgentAssembler` 同线程消费后清理；优先级：请求级 > Agent 配置；未传参 = 跟随配置。AgentRegistry 每请求重建，开关即时生效。
- **计划模式可视化（计划卡片 + 进度清单）**：经模板 `customToolRenderConfig` 按工具名挂载自定义渲染（`src/chat/PlanCards.tsx`），替代默认 ToolCall 折叠面板：`plan_enter` → 「已进入计划模式」轻提示；`plan_write` → 计划 markdown 卡片（流式实时可见，完成后默认收起可点击展开）；`plan_exit` → 「计划已就绪 · 等待批准/已批准」卡片（含 summary）；`todo_write` → 执行进度清单（进度条 + pending/in_progress/completed 状态点，SDK 保证同一时刻恰好一个 in_progress）。数据全部来自既有 TOOL_CALL_* 事件流（aguiBridge 无需改动），解析容错流式不完整 JSON；output 合并消息（无 arguments）不重复渲染。批准交互（Interrupt/确认链路）前端尚未接入，为后续项。无后端改动。
- **前端附修**：系统配置渠道类型 Radio 改下拉；渠道/沙箱记录名提交前 trim（历史数据带尾随空格会被记录引用校验拒绝）。
- **无 SQL 变更**（feature 为 JSON 列）。
- **冒烟**：multiagent/memory 落库回显✓；非法 flushTrigger 拒绝✓；`memoryMode=false` 发起 run 日志见「Agent 记忆已关闭（请求级开关=false 配置=true）」✓；`planMode=true` 发起 run 日志见「Agent 计划模式已覆盖（请求级开关=true 配置=false）」+ `plan_enter/plan_write/plan_exit` 工具已注册✓。

---

## §26 会话状态存储（Redis 接入与回切）

会话状态（AgentState）后端曾切到本机 Redis，后经配置开关回切 MySQL（见 §27 存储拆分）：
- **服务器 Redis**：既有源码编译的 Redis 5.0.5，新建 `/main/redis/redis.conf`（bind 127.0.0.1、requirepass、AOF everysec、maxmemory 512mb/noeviction）+ systemd `redis.service`（Type=simple，非 notify——源码编译无 libsystemd）；开机自启。
- **依赖**：bom/core 新增 `agentscope-extensions-redis`（中央仓 2.0.1，与既有 SDK 同版本），Jedis 客户端传递引入。
- **接线（§27 修订后）**：`AgentScopeConfig` 共享 `JedisPooled` bean（会话/记忆任一启用才装配）；`agentStateStore` 按 `teapot.ai.agentscope.redis.session-store` 开关二选一：`true` → `RedisAgentStateStore`（keyPrefix `teapot:session:`，不校验 sessionId 斜杠），`false`（当前生产取值）→ `LenientMysqlAgentStateStore`（§22.5 宽校验补丁）。注入点一律 `AgentStateStore` 接口。
- **配置**：application-prod.yml `teapot.ai.agentscope.redis.*`；密码经 app.env `TEAPOT_REDIS_PASSWORD` 注入（SPEC §14）。注意前缀类值含冒号必须加引号（不加则 YAML 解析报错应用起不来，踩过一次）。开关：`TEAPOT_REDIS_SESSION_STORE`（默认 false）/ `TEAPOT_REDIS_MEMORY_STORE`（默认 true）；旧的 `enabled` 键已废弃（app.env 里残留无害）。
- **Redis 键结构（会话，当前不用）**：`teapot:session:{userId}/{sessionId}:{stateKey}` + `:_keys` 集合；支持版本化写入（saveIfVersion）。回切后存量键已清理。
- **已知代价**：Redis 期间（§26 上线 → 回切之间）产生的会话历史留在 Redis 已清理，不迁移；`agentscope_sessions` 表一直保留，回切后继续写入。
- **冒烟（回切后）**：新会话写入 `agentscope_sessions`（`tmp-verify-0816b:smoke-mem-1` 行✓）；`teapot:session:*` 键数归零✓。

---

## §27 存储拆分：会话状态在 MySQL，长期记忆路由 Redis（文件系统路由）

用户指令：“会话存储放在 mysql，记忆存储放在 redis”。落地为两条独立开关：
- **会话状态 → MySQL**：`redis.session-store=false`（生产默认），见 §26 修订。
- **长期记忆 → Redis**：`redis.memory-store=true`（生产默认）。harness 两层记忆（`MEMORY.md` + `memory/YYYY-MM-DD.md`，含 watermark `memory/.consolidation_state`）全部经 WorkspaceManager → AbstractFilesystem I/O，因此用“文件系统路由”把记忆路径改指 Redis，不碰会话链路。
- **实现（仅 2.0.1 可用能力）**：2.0.1 无 `filesystemRoute`（仅 2.0.3 开发线），用既有逃生舱 `HarnessAgent.Builder.abstractFilesystem(...)`（与 `filesystem(spec)` 互斥）：
  - `RedisMemoryFilesystems`（core/storage）：用 `LocalFilesystemSpec.toFilesystem(workspace, IsolationScope.USER.toNamespaceFactory())` 完整复刻 SDK 默认本地装配（上层 LocalFilesystemWithShell + project 下层、USER 命名空间），再以 `CompositeFilesystem` 前缀/精确文件路由：`memory/` 与 `MEMORY.md` → **直达 `RemoteFilesystem(RedisStore)`（Redis 为记忆唯一来源，不读磁盘）**，其余路径走原叠加。两条路由经 Composite 归一后落同一命名空间（`/MEMORY.md` 与 `/<name>`，`memory/` 前缀被剥离），故共用一个 RemoteFilesystem 实例。
  - **历史坑（已修）**：初版路由后端用 `OverlayFilesystem(Redis, LocalFilesystem 只读基线)` 想保住存量本地记忆可见，但基线**从未生效**——Composite 给路由后端的 backendPath 带前导斜杠（`/MEMORY.md`），而 `LocalFilesystem.isAbsolutePathString` 见 `/` 即视为绝对路径**跳过命名空间前缀**，基线永远读的是 `workspace/MEMORY.md`（无 `<uid>/` 段）而非真实的 `workspace/<uid>/MEMORY.md`。叠加用户指令“不想读磁盘记忆”，改为 Redis-only + 一次性存量迁移。
  - `RedisMemoryLocalFilesystem`：`extends OverlayFilesystem implements AbstractSandboxFilesystem`，继承只为保住三处类型判定（`getUpper() instanceof LocalFilesystemWithShell`：路径归一化 + skill shell 策略；`AbstractSandboxFilesystem`：ShellExecuteTool 注册）；文件操作全部委托内部 Composite（默认后端必须是平行等价叠加实例，用 `this` 会递归）；`execute()/id()` 直接透传上层。**shell 能力完整保留**。
  - `AgentAssembler.applySandbox` 改返 boolean；非沙箱（含降级）路径且路由工厂在场 → `builder.abstractFilesystem(...)`。**§27 修订（v52）**：该分支已拆为两条——非沙箱走 `abstractFilesystem`（同上），沙箱走 `builder.filesystemRoute(MEMORY.md / memory/, memoryFilesystem(agentKey, scope))`；`isolationScope` 解析收敛为 `isolationScope(sb)` 一处，保证沙箱复用粒度与记忆命名空间同源。
- **命名空间**：`teapot:memory:item:agents\0<agentKey>\0users\0<uid>\0<path>`（RedisStore 以 NUL 拼接命名空间）；uid 缺失回落 sessionId，对齐 IsolationScope.USER 降级语义。键前缀 `teapot:memory:`（`memory-key-prefix`）。**§27 修订**：末段改为跟随 Agent 自身的 `IsolationScope` 派生——`USER`→userId（回落 sessionId）、`SESSION`→sessionId、`AGENT`/`GLOBAL`→固定段 `_agent`（整个 Agent 共享一份，与沙箱 AGENT 隔离语义一致）。仍挤在 `users/` 段下是为了让 `listMemoryUids` 与记忆管理接口无需区分隔离语义即可发现它。
- **存量迁移**（`redis.migrate-legacy-memory=true` + `memory-store=true` 时，启动 `ApplicationRunner`）：`RedisMemoryFilesystems.migrateLegacyMemory(workspaceRoot)` 扫 `workspaceRoot/<agentKey>/<uid>/` 布局，把 `MEMORY.md` → `/MEMORY.md`、`memory/*` → `/<name>`（含 `.consolidation_state`），用运行时同款 `RemoteFilesystem.write`（create-if-absent）导入 Redis，**幂等可重跑**，磁盘文件保留作只读归档。生产已执行：`migrated=13 skipped=0`（teapot/admin 5 + digit-tim/discord 3 + digit-tim/admin 1 + general-assistant/anonymous 4），完成后已置回 `false`。
  - **§27 修订：沙箱记忆也可用同一机制导入**（开关是 `app.env` 的 `TEAPOT_REDIS_MIGRATE_LEGACY_MEMORY`，改一行 + 重启即可，无需改 jar 内 yml）：把快照 tar 里的 `MEMORY.md` + `memory/` 解到 `workspace/<agentKey>/_agent/`（即与 `IsolationScope.AGENT` 对应的命名空间末段），再开标志重启。2026-09-02 实执：`migrated=10 skipped=13`（MEMORY.md + 8 台账 + watermark），导入后管理接口立即可读到 32KB 真实记忆。
  - **时序要求（否则会静默失忆）**：必须先导入、再开启路由。若同一次重启里「边启动边导入」，从 web 端口就绪到 `ApplicationRunner` 跑完之间有秒级窗口，此期间 Agent 写入的新 MEMORY.md 会让 `create-if-absent` **跳过**旧内容。因此采用两次重启：先在旧版（路由未生效）上完成导入并验证，再发布开启路由的新版（同时把标志置回 `false`）。
- **适用范围**：**非沙箱与沙箱 Agent 均已覆盖（§27 修订，v52）**。原限制「2.0.1 沙箱模式文件系统固定为 `SandboxBackedFilesystem`，无任何注入点，记忆留在沙箱内」已随升级解除：2.0.3 提供 `HarnessAgent.Builder#filesystemRoute(prefix, fs)`，且 `sandboxFilesystemSpec != null` 分支会用 `RoutedSandboxFilesystem(capturedSandboxFs, routes)` 包装（主后端仍为沙箱，只接管记忆前缀，`execute()/id()` 透传，**`shell_execute` 不丢失**）。
  - **副作用（必读）**：文件 API 走路由，但 **shell 不走路由**。切换后沙箱内那份旧 `MEMORY.md`/`memory/` 不会被自动遮住：`ls /` 会出现重复项，全仓 `grep` 仍会命中过期结论（模型可能重新学会它），而且模型用 `sed -i MEMORY.md` 改的是那份没人维护的影子副本。因此导入并切换路由后必须补一步：把沙箱内旧副本 `mv` 出工作区（已由一轮会话让 Agent 自行完成）。
  - **影子备份的命名坑**：精确文件路由只匹配 `MEMORY.md` 本身，**不匹配 `MEMORY.md.bak-*`**（Composite 前缀匹配需 `MEMORY.md/` 才命中），所以清洗记忆时自动留下的 `MEMORY.md.bak-20260902` 会长期留在沙箱并被搜索命中，必须一并挪走。另外别挪进 `.某目录` 就以为完事——`**/*.md` glob 仍会捞到它，必须移出工作区。
  - 归档位置选沙箱 `/tmp` 时请注意：快照只抓工作区，`/tmp` 不随快照持久，沙箱重建即消失；真正可恢复的副本是宿主侧 `workspace/<agentKey>/_agent/` 那份导入用 staging 与 Redis 本体。
- **v52 冒烟（2026-09-02，digit-tim / isolationScope=AGENT）**：日志「沙箱 Agent 记忆已统一路由到 Redis agentKey=digit-tim isolationScope=AGENT」✓；工作区已无 `MEMORY.md`（17:06 快照内无根级记忆文件）的情况下，`memory_save` 写入的暗号 `memroute-7412` **出现在 Redis `/MEMORY.md`**（33,739 → 35,005 字符）✓，且 Agent 能逐字复述订正段✓ —— 读写双向均只可能来自 Redis，判定成立。孤儿记忆（`users/admin` 1 项 + `users/discord` 3 项，内容均停留在 2026-08-28）已核对磁盘原件仍在后删除，现该 Agent 只剩 `users/_agent` 一组。
- **回切**：`TEAPOT_REDIS_MEMORY_STORE=false` 重启即回纯本地（但迁移后新写的记忆仅在 Redis、未回写磁盘，回切后这部分不可见；磁盘存量仍在）。
- **子代理**：2.0.1 父代理的 `abstractFilesystem` 会自动传给子代理（`HarnessAgentBuilderSupport` capturedBackend），子代理记忆同走 Redis。
- **冒烟**：teapot 对话（含 shell `echo` 执行✓）后：Redis 出现 `agents\0teapot\0users\0tmp-verify-0816b` 命名空间下 `/MEMORY.md`（consolidator 合并产物）、`/2026-08-28.md`（每日台账）、`/.consolidation_state`（watermark）✓，冒烟暗号写入并可在 Redis 值中命中✓；日志“记忆文件系统已路由到 Redis agentKey=teapot”✓。
- **迁移后复验**（Redis-only）：tmpverify 登录后，Agent 从 Redis 读回旧暗号 `teapot-redis-mem-ok`（OLD_MARKER_RECITED✓）、shell `echo` 正常、新暗号 `migrate-mem-ok-8888` 落入 `/2026-08-28.md`（MARKER_FOUND✓）；`users\admin` 命名空间下 5 个迁移键完整（`/MEMORY.md` ver=1 内容为存量服务器架构记忆）。
- **坑记录**：RedisStore 键含 NUL 字节，bash 变量/`redis-cli --scan` 管道会截断键名（显示为 `…:item:agents`），检查需用 Lua 脚本服务端内联或 `od` 直接读管道。
- **坑记录（§27 修订补）**：排查沙箱 Agent 记忆时，**快照 tar 才是切换前的唯一可信数据源**（`workspace/sandbox-snapshots/<agentKey>/<sandboxId>.tar`，每轮 run 结束后刷新，复核前先比对 mtime 与当前时间）；Redis 里可能还残留一份早期迁移留下的孤儿，内容停在迁移当天，误读会得出「记忆未更新」的错误结论。另：接口上报的 `size` 是**字符数**，与 `wc -c` 的字节数不等（中文 1 字 3 字节），比对两份拷贝大小时不要误判为内容丢失。

---

## §28 业务空间（Workspace）与去除全局 Skill

> 状态：**设计稿（先做 spec，未实现）**。本节按用户 2026-10-24 需求编号 1–7 落地，所有落点均引现有代码先例；实现前请先过 §28.10 待确认项。

### §28.0 目标与范围

两件事：
1. **去除「全局 skill」概念**：Agent 不再在未绑定 skill 时自动继承全部 skill，改为「每个 Agent 只装配自己显式绑定的 skill」。
2. **引入「业务空间（Workspace）」**：以新表 `teapot_workspace` 承载，Agent 归属到空间；顶栏（图二红框）加空间下拉框，默认「个人空间」；支持建空间 + 拉用户查询组件加成员；跨空间隔离；系统白名单可见全部空间；存量 Agent 脚本迁移到一个新空间。

非目标（本轮不做，见 §28.10）：Skill 实体本身按空间/Agent 归属、空间级配额与审计、空间内二级角色（owner/admin/member 细分）。

### §28.1 去除全局 Skill（需求 1）

- **唯一落点**：`teapot-ai-core/.../AgentBuilder.java:177-179`
  ```java
  SkillFilter skillFilter = bound.isEmpty() ?
          SkillFilter.all() :                       // ← 全局 skill 兜底，删除此语义
          SkillFilter.only(bound.toArray(new String[0]));
  ```
  改为：
  ```java
  // 无「全局 skill」概念：未显式绑定即不装配任何 skill（每个 Agent 只用自己的）
  SkillFilter skillFilter = bound.isEmpty() ?
          SkillFilter.none() :
          SkillFilter.only(bound.toArray(new String[0]));
  ```
  `SkillFilter.none()` 已存在（`agentscope-core/.../skill/SkillFilter.java:78`），无需改框架。
- **绑定链路不变**：`t_agent_skill`（`sql/V1:45-54`，UNIQUE(agent_key, skill_name)）+ `AgentService.bindSkill/unbindSkill/replaceBindings`（`AgentService.java:219-345`）已是「按 Agent 装配」，本轮只是把「未绑定 = 全给」的兜底摘掉。
- **Skill 工坊（`/api/skill/list`，`SkillController.java:35`）保持不变**：它仍是「可装配的 skill 目录/调色板」（mysql 只读 + git + oss 三来源，见 §15），不是「自动继承」。去掉的是运行期的 all 兜底，不是目录本身。
- **行为变更与迁移影响（必读）**：现网若有 Agent 依赖「不绑定就能用全部 skill」，改后这些 Agent 将**一个 skill 都没有**。上线前需盘点 `t_agent` 中在 `t_agent_skill` 无绑定行的 Agent，为其显式补绑（脚本见 §28.5 备注）。

### §28.2 数据模型（需求 2、6）

沿用 `sql/V*__*.sql` **手工迁移**约定（无 flyway：部署用 `docker exec -i teapot-mysql mysql ... < V*.sql`，先例 `deploy/run-sql.sh`、`deploy/tmp-deploy-v23.sh`；故 V4 有重名两文件）。下一个空位 = **V13**。所有 DDL 用 `information_schema` 存在性守卫保证幂等（先例 `sql/V4__agent_feature.sql:7-16`）。

**新增 `sql/V13__workspace.sql`：**

- `teapot_workspace`（用户指定列名照搬；注意与 `t_*` 表的 `created_at/updated_at/created_by` 命名不同——用户明确要 `gmt_create/gmt_modified/creator`，本节从用户）：
  ```sql
  CREATE TABLE IF NOT EXISTS teapot_workspace (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    workspace_name VARCHAR(64)  NOT NULL COMMENT '空间显示名',
    workspace_code VARCHAR(64)  NOT NULL COMMENT '空间业务编码，全局唯一；个人空间=personal:{userId}',
    workspace_type VARCHAR(16)  NOT NULL DEFAULT 'team' COMMENT 'personal=个人空间 team=团队空间',
    description    VARCHAR(512) NULL,
    feature        JSON         NULL COMMENT '空间级扩展配置(JSON)，预留，仿 t_agent.feature',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    creator        VARCHAR(64)  NOT NULL COMMENT '创建人 userId（个人空间即归属人）',
    gmt_create     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workspace_code (workspace_code)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务空间';
  ```
- `teapot_workspace_member`（承载「加成员即授权」，需求 2-1）：
  ```sql
  CREATE TABLE IF NOT EXISTS teapot_workspace_member (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT UNSIGNED NOT NULL,
    user_id      VARCHAR(64)  NOT NULL COMMENT '成员 userId（t_user.user_id）',
    member_role  VARCHAR(16)  NOT NULL DEFAULT 'member' COMMENT 'owner=创建人/空间主 member=普通成员',
    creator      VARCHAR(64)  NOT NULL COMMENT '谁加的这条成员',
    gmt_create   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ws_user (workspace_id, user_id),
    KEY idx_user (user_id)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务空间成员';
  ```
- `t_agent` 加**可空** workspace 列（需求 6）：
  ```sql
  ALTER TABLE t_agent ADD COLUMN workspace_id BIGINT UNSIGNED NULL
    COMMENT '归属业务空间；NULL=未归属（存量/未迁移）' AFTER feature;
  ```
  幂等守卫同 `V4__agent_feature.sql`。

**实体/Mapper（仿现有 `AgentDO`+`AgentMapper`+`sqlclient/AgentMapper.xml` 三件套）：**
- `WorkspaceDO`（`model/`）：字段对齐上表；`feature` 为 String（JSON 原文），仿 `AgentDO.feature`。
- `WorkspaceMemberDO`（`model/`）。
- `WorkspaceMapper` + `sqlclient/WorkspaceMapper.xml`：`selectById`、`selectByCode`、`selectByUser(userId)`（join member 返回该用户所属空间）、`selectAll`（白名单用）、`insert`、`update`、`softDelete`；`insertMember`、`deleteMember`、`selectMembersByWorkspace`、`selectWorkspaceIdsByUser`、`isMember(workspaceId,userId)`。
- `AgentDO` 加 `private Long workspaceId;`；`AgentMapper.xml`：`resultMap`+`<sql id="cols">`（`AgentMapper.xml:6-26`）补 `workspace_id`；`insert`（`:59-64`）补列；`update`（`:67-74`）补 `workspace_id = #{workspaceId}`；`selectPage`/`count`（`:32-53`）加可选 `workspaceId` 过滤（`<if test="workspaceId != null">AND workspace_id = #{workspaceId}</if>`）。

### §28.3 权限与隔离模型（需求 2-1、2-2、7）

- **可见空间集合**（`WorkspaceService.listVisible()`）：
  1. 当前用户**个人空间**（`workspace_type=personal`，`workspace_code=personal:{userId}`）——**懒创建**：无行时前端仍显示为默认项（合成项，id=null），后端在「该用户首次建 Agent」时才 insert 行（见 §28.4）。
  2. 该用户是 member 的所有 team 空间（`selectWorkspaceIdsByUser`）。
  3. **系统白名单用户**（§28.7）：额外可见**全部**空间（`selectAll`）。
- **个人空间语义**（需求 2 括注）：个人空间**不附带任何 Agent**；只有在**第一个 Agent 创建出来时**才创建该 workspace 行，并把该 Agent 归入。个人空间只有 owner 一个成员，不开放加成员/移动他人 Agent。
- **加成员即授权**（需求 2-1）：`addMember(workspaceId, userId)` 写 `teapot_workspace_member`（`member_role=member`），该用户下次 `listVisible()` 即包含此空间，无需其它授权动作。成员来源 = 「拉起用户查询组件」（§28.6）选出的 `t_user`。
- **跨空间隔离**（需求 2-2）：所有空间作用域查询在 Service 层先过 `requireMember(workspaceId)`：
  ```java
  void requireMember(Long workspaceId) {
    String uid = ContextUtil.currentUserId();
    if (isWhitelisted(uid)) return;                    // 白名单放行
    if (!workspaceMapper.isMember(workspaceId, uid))   // 非成员
      throw new BizException(Result.CODE_FORBIDDEN, "无权限访问该空间");
  }
  ```
  「不允许查询跨空间的权限」= 非成员无法查该空间的成员列表/Agent 列表/空间详情；`requireMember` 是唯一闸口。**注意 RBAC 现状是粗粒度**：`RbacAccessFilter`（`teapot-ai-rbac/.../RbacAccessFilter.java:47-56`）只按 `roleId → URI 通配` 放行（`application.yml:30-64`），**不认识 workspace**。故空间级隔离是 **Service 层数据作用域**职责（与 `session-history` 在 Service 里 `requireAdmin` 兜底同款思路，见 `AgentController.java:100-101`），不能只靠 URI 白名单。

### §28.4 后端接口与编排

**新增 `WorkspaceController`（`/api/workspace/*`）+ `WorkspaceService`：**
- `GET /api/workspace/list` → `listVisible()`：返回当前用户可见空间（含个人空间合成项 + 白名单全量）。前端下拉框数据源。
- `POST /api/workspace/create`（`WorkspaceCreateRequest`：`workspaceName`*、`workspaceCode`*、`description`、`memberUserIds: List<String>`）→ 建 team 空间 + 批量 `addMember`（含 creator 自己为 owner）。`workspaceCode` 唯一冲突拒绝（仿 `AgentService.create` 的 agentKey 冲突校验 `AgentService.java:104-107`）。
- `GET /api/workspace/{id}/members` → `requireMember(id)` 后返回成员列表（跨空间被拒）。
- `POST /api/workspace/{id}/member`（`{userId}`）/ `DELETE /api/workspace/{id}/member/{userId}` → 加/移成员，`requireMember(id)`（仅 owner/白名单可改，见 §28.10）。
- `GET /api/workspace/{id}/agents` 或复用 `/api/agent/list?workspaceId=` → 见下。
- `PUT /api/workspace/personal/ensure`（内部）：懒创建个人空间，返回其 id。

**改造 `AgentService`（`AgentService.java`）：**
- `list(page,size,keyword,includeDisabled)` → 加 `workspaceId` 入参（`AgentController.list` 加 `@RequestParam(required=false) Long workspaceId`）。Service 内：`workspaceId` 为空 → 解析为「当前用户个人空间」（懒创建/合成）；随后 `requireMember(workspaceId)`；`agentMapper.selectPage/count` 带上 `workspaceId` 过滤。
- `create(AgentCreateRequest)`（`AgentService.java:102-136`）：`AgentCreateRequest` 加 `Long workspaceId`（可空）。
  - 传了 team 空间 id → `requireMember` 后写入 `agent.setWorkspaceId(...)`。
  - 传空 / 传「personal」→ **懒创建个人空间**（若 `personal:{uid}` 无行则 insert + 加 creator 为 owner），再 `agent.setWorkspaceId(personalId)`。这是「第一个 Agent 触发建个人空间」的落点。
  - `AgentMapper.insert` 补 `workspace_id`（§28.2）。
- **移动空间**（需求 5）：`AgentService.moveWorkspace(agentKey, targetWorkspaceId)` → `requireAgent` + `requireMember(target)` + 校验对源空间有权限 → `agent.setWorkspaceId(target)` → `agentMapper.update` → `agentRegistry.invalidate(agentKey)`（仿 `update()` 的失效逻辑 `AgentService.java:180`）→ `auditService.log("agent.moveWorkspace", ...)`。新增 `PUT /api/agent/{agentKey}/workspace`（body `{workspaceId}`）。
- `AgentDetailVO`（`vo/AgentDetailVO.java`）已内嵌 `AgentDO`，`workspaceId` 随之带出，前端详情可读。

**DTO/前端契约（`api/agent.ts`）：** `AgentCreatePayload` 加 `workspaceId?: number`；新增 `agentMoveWorkspace(agentKey, workspaceId)`；`agentList` params 加 `workspaceId?: number`。`types.ts` 的 `interface Agent`（`types.ts:35`）加 `workspaceId?: number`。

### §28.5 存量迁移脚本（需求 3）

**新增 `sql/V14__workspace_backfill.sql`**（幂等，可重跑）：
1. 建一个承载存量的 team 空间（若 `workspace_code='legacy-default'` 不存在才 insert）：`workspace_name='存量空间'`、`creator=` 首个 admin（`SELECT user_id FROM t_user WHERE roles LIKE '%admin%' ORDER BY id LIMIT 1`）。
2. 把所有 `workspace_id IS NULL` 的 `t_agent` 一次性归入该空间：`UPDATE t_agent SET workspace_id = @legacy WHERE workspace_id IS NULL`。
3. 把每个存量 Agent 的 `created_by` 补成该空间成员（`INSERT ... SELECT DISTINCT created_by ... ON DUPLICATE KEY UPDATE`），避免迁移后原创建人看不到自己的 Agent；admin 另有白名单兜底（§28.7）。
- **执行方式**：先 `V13`（建表/加列）后 `V14`（回填），经 `deploy/run-sql.sh < V13...` / `< V14...`（先例 `tmp-deploy-v23.sh:9`）。
- **备注（配合 §28.1）**：迁移同时盘点无 skill 绑定的 Agent，按需补 `t_agent_skill`，否则去全局兜底后它们将无 skill 可用。

### §28.6 前端（需求 2、4、5 + 图二红框）

- **顶栏红框落点**（`layout/AppLayout.tsx`）：header 右侧簇顺序为 `flex 占位 → #topbar-history-slot → <AgentSelector/> → #teapot-header-extra → <UserMenu/>`（`AppLayout.tsx` header 段）。在 `<AgentSelector/>` **左侧**、`#topbar-history-slot` 附近插入两个新组件（即图二红框：导航右、头像左）：
  - `<WorkspaceSwitcher/>`（新，`layout/`）：`Select`（复用 `@agentscope-ai/design`，仿 `AgentSelector.tsx:78-121`）列出 `listVisible()`，默认选「个人空间」；选项含「+ 新建空间」触发建空间弹窗。选中值存 zustand + localStorage（仿 `AgentSelector` 的 `LS_KEY='teapot:lastAgent'` 与 `store/mobileUI`/`store/auth` 用法）。
  - `<WorkspaceMenu/>`（新）：`…` 按钮（`Dropdown`，仿 `AppLayout.tsx` 移动端 `Dropdown menu` 用法），下拉项「移动空间」→ 打开空间选择弹窗，对**当前 Agent**（chat 页取 `AgentSelector` 的 `?agent=`；管理页取选中卡片）调 `agentMoveWorkspace`。
- **当前空间上下文**：新增 `store/workspace.ts`（zustand，仿 `store/auth.ts`）持有 `activeWorkspaceId`；`Agents.tsx` 的 `load()`（`Agents.tsx:42-53`）把 `workspaceId: activeWorkspaceId` 传入 `agentList`，切换空间即重查（`useEffect` 依赖加上它）。
- **建空间弹窗 + 成员选择组件**（需求 2-1）：`ResponsiveModal`（仿 `Agents.tsx:246-280`）内 `Form`：`workspaceName`*、`workspaceCode`*、`description`、成员多选。成员选择 = **拉起用户查询组件**：新增 `<UserPicker/>`，数据源 `GET /api/user/list`（`UserController.java:38-42`）；因该接口目前**只分页无关键字**（`UserMapper.selectPage` 无 keyword），需给 `/api/user/list` 加 `keyword` 检索参数（`UserMapper.selectPage`/`countAll` 加 `username/real_name LIKE`）以支持搜索添加。选中用户提交到 `workspace/create` 的 `memberUserIds`。
- **建 Agent 支持选空间**（需求 4）：`Agents.tsx` 新建弹窗（`Agents.tsx:255-279`）与 `AgentDetail.tsx` 配置页各加一个「所属空间」`Select`（默认当前空间，含「+ 新建空间」内联入口），提交进 `AgentCreatePayload.workspaceId`。
- **RBAC resource-list**（`application.yml:30-64`）：`developer` 段加 `/api/workspace/*`；`viewer` 段加只读 `/api/workspace/list`、`/api/workspace/*/members`（按需）。`/api/user/list` 若要对 developer 开放供 UserPicker，需在 developer 段补 `/api/user/list`（现仅 admin `/*` 覆盖，viewer/developer 未放行）。
- **移动端**：红框组件在 `isMobile` 下按需收窄/收进 `WorkspaceMenu`（仿 `AppLayout.tsx` 移动端下拉），避免顶栏拥挤。

### §28.7 系统白名单（需求 7，放 properties）

- `TeapotAiProperties`（`config/TeapotAiProperties.java`）加嵌套配置（仿现有 `Sandbox`/`Storage` 内部类与 `modelPresets` 顶层 List 两种先例）：
  ```java
  /** 业务空间配置（SPEC §28） */
  private Workspace workspace = new Workspace();

  @Data
  public static class Workspace {
      /** 系统白名单 userId：可见并管理全部 workspace（SPEC §28.7） */
      private List<String> whitelist = new ArrayList<>();
  }
  ```
- `application.yml`（`teapot.ai:` 段，`application.yml:70+`）加：
  ```yaml
  teapot:
    ai:
      workspace:
        whitelist: ${TEAPOT_WORKSPACE_WHITELIST:}   # 逗号分隔 userId，env 注入
  ```
- `WorkspaceService.isWhitelisted(uid)` = `properties.getWorkspace().getWhitelist().contains(uid)`；命中则 `listVisible()` 返回全部空间、`requireMember` 直接放行。

### §28.8 验收标准

1. 未绑定 skill 的 Agent 运行时装载 0 个 skill（日志 `boundSkills=[]` 且不再回落全部）；绑定 N 个则恰好 N 个。
2. 新用户首登：空间下拉默认「个人空间」，此时 `teapot_workspace` 无该用户行；创建第一个 Agent 后，`teapot_workspace` 出现 `personal:{uid}` 行且该 Agent `workspace_id` 指向它。
3. 建 team 空间：填必填项 + 经 UserPicker 加 2 名成员 → 空间行 + 3 条 member（含 creator=owner）；被加成员登录后下拉可见该空间。
4. 跨空间：非成员请求 `GET /api/workspace/{id}/members` 与该空间 Agent 列表 → 403「无权限访问该空间」。
5. 白名单用户（properties 配）下拉可见全部空间，可进入任意空间管理。
6. `V14` 迁移后：所有存量 `t_agent.workspace_id` 非空且指向「存量空间」；原创建人能在下拉看到「存量空间」并列出其 Agent。
7. 建 Agent 时选「所属空间」= 某 team 空间 → 该 Agent 直接归属它；不选 → 归个人空间。
8. 顶栏 `…` →「移动空间」把当前 Agent 移到另一空间后，源空间列表不再出现、目标空间出现，`agentRegistry` 已失效重建。

### §28.9 冒烟

- `mvn -f teapot-ai-server/pom.xml package -DskipTests` 通过；本地起服务，`/api/workspace/list` 返回个人空间合成项；建空间 + 加成员 + 建 Agent + 移动空间全链路 200；非成员越权 403。
- 前端 `npm run build` 通过；顶栏出现空间下拉与 `…`；切空间后 Agent 列表随之变化。

### §28.10 待确认项 / 风险

1. **`…`「移动空间」的作用对象**：本稿按「移动当前 Agent 到别的空间」实现（chat 页作用于 `AgentSelector` 选中 Agent）。若用户本意是「空间级操作菜单（改名/管成员/删空间）」，需调整为 workspace 动作菜单——**请确认**。
2. **Skill 是否也要按空间隔离**：本稿只去掉运行期 all 兜底，Skill 工坊仍是全局共享目录（跨空间可见可装配）。若要求「skill 也归属空间/Agent」，是更大改动（skill 实体加 workspace_id + 目录按空间过滤），本轮不含。
3. **空间内二级权限**：本稿 member 只有 owner/member 两档，且「谁能加/移成员、谁能移动 Agent」暂按 owner + 白名单。是否需要更细角色（如空间 admin）待确认。
4. **去全局 skill 的存量冲击**：现网无绑定 Agent 会立刻「裸奔」，上线顺序须「先补绑/迁移 → 再发去兜底版本」，否则可能中断线上 Agent 能力。
5. **`gmt_create/gmt_modified/creator` 命名**与既有 `t_*` 表 `created_at/updated_at/created_by` 不一致（用户显式指定，本稿从用户）；若要全站统一需另议。
6. **`/api/user/list` 无关键字检索**：UserPicker 需要按名搜索，须扩 `UserMapper.selectPage` + resource-list 放行，属对既有用户模块的小改。

### §28.11 模型入口的空间开放（2026-10-24 追加实现）

需求：模型入口（`t_model_entry`，系统配置→模型）增加「开放给哪个 workspace 使用」的配置。

**决策（用户选定）**：
- 建模方式 = **多空间授权 + 未授权即不可用（deny-by-default）**，非「未授权即全局」。
- 生效环节 = **选择 + 保存时**（不改 `ModelRegistry` 运行期热路径）。

**授权模型**：新增授权表 `teapot_workspace_model(workspace_id, model_entry_id)`（仿 `teapot_workspace_member`）。
- `workspace_id = 0` 为**全局桶**：开放到 0 = 对全部空间（含每个用户的个人空间）可用；个人空间不逐个建授权。
- `workspace_id > 0` = 对应团队空间。某模型对空间 W 可用 ⟺ 命中 `W` 或全局 `0` 桶的授权行。
- 存量补授权：`V15` 把当前全部模型入口授权到 0 桶，保持去空间化前既有行为（现有 Agent 照常可选）。

**读路径（选择时过滤）**：
- `GET /api/model/presets?workspaceId=` → `ModelService.listEnabledModelIds(Long)` → `selectEnabledForWorkspace`（EXISTS 命中 `IN(bucket,0)`）；不回落 yml（空集即「该空间无可用模型」）。`workspaceId` 省略时退回不过滤全量（向后兼容）。
- 前端 `Agents.tsx` 建 Agent 时按当前空间 `activeId ?? 0` 拉取；`AgentDetail.tsx` 暂不过滤（保存校验兜底，列为后续项）。

**写路径（保存时校验）**：
- `AgentService.create/update`：非白名单用户，按目标空间桶 `assertOpenToWorkspace(modelId, bucket)`——未登记或未授权即 `BizException`。桶由 `WorkspaceService.modelBucketForWorkspace` 映射（个人/未归属→0，团队→自身 id）。白名单（§28.7）放行。
- `ModelService.create/update`：`ModelEntryDO.workspaceIds`（非 DB 列，经授权表读写）覆盖式落库（先删后插、去重）；`update` 仅在 `workspaceIds != null` 时改动授权，保证状态开关等局部更新不清空。`delete` 连带删授权行。
- `listAll`（admin）回填每条 `workspaceIds`；`GET /api/workspace/all`（`listAllEnabledForAdmin`，仅 admin）供模型页「开放空间」多选自举团队空间。

**前端**：`Models.tsx` 新增「开放空间」列（含 0→全部空间 / 空→未开放 / 团队名标签）与建/改弹窗多选（默认 `[0]`）；`api/model.ts` `ModelEntry.workspaceIds`、`api/agent.ts` `modelPresets(workspaceId?)`、`api/workspace.ts` `workspaceAll()`。

**落点**：`sql/V15__model_workspace.sql`、`ModelEntryDO`、`ModelEntryMapper(.java/.xml)`、`ModelWorkspaceGrantDO`、`ModelService`、`ModelController`、`WorkspaceService`（`listAllEnabledForAdmin`/`modelBucketForWorkspace`/`requireAdmin`）、`WorkspaceController`、`AgentService`；前端 `Models.tsx`/`Agents.tsx`/`api/{model,agent,workspace}.ts`。

**验证**：`mvn -f teapot-ai-server/pom.xml package -DskipTests` 通过；前端 `tsc -b && vite build` 通过。

**后续项（未做）**：运行期 `ModelRegistry.resolve` 按空间硬拦截（需给缓存加空间维度）；`capabilities` 端点按空间过滤；`AgentDetail` 模型下拉按 Agent 归属空间过滤；移动 Agent 时联动校验其模型是否开放给目标空间。

### §28.12 空间编辑：改名 + 成员维护（2026-10-24 追加实现）

**需求**：顶栏空间簇的「⋯」菜单增加编辑当前空间的能力，支持改名称与维护成员。

**接口**：`PUT /api/workspace/{id}`（`WorkspaceUpdateRequest{workspaceName?, description?, memberUserIds?}`）→ `WorkspaceService.update`。
- 权限：`requireManager`（owner 或 §28.7 白名单）；**个人空间拒绝编辑**（名称由系统固定、成员只有本人，没有可编辑语义）。
- 语义：字段 `null` = 不修改；`memberUserIds` 非 null 时按**集合整体同步**——期望集里没有的成员删除，新增的按 `member` 落库，**owner 行永不删**（创建人始终保留）。空间编码与类型不可改（编码是 `personal:{userId}` 等约定的锚）。
- 复用既有全字段 `workspaceMapper.update`（DO 先由 `requireMember` 从库里载入，故 `feature/status` 不会被这次编辑覆盖）。

**前端**：`WorkspaceSwitcher` 的 ⋯ 菜单按 `canEdit`（当前空间是团队空间且 `myRole==='owner'`）追加「编辑空间」；弹窗字段=名称/描述/成员（`UserPicker` 预填现有成员）。
- `UserPicker` 新增 `initialOptions`：多选框的回填值若不在搜索结果里会显示裸 userId，故把现有成员作为展示项并入选项（搜索结果按 userId 覆盖同名项）。
- 编辑弹窗用 `forceRender`：`useForm` 实例未连接到 Form 元素时 `setFieldsValue` 会丢值，弹窗常驻渲染才能让「先取成员再回填」稳定生效。
- 数据源：`api/workspace.ts` 增 `workspaceUpdate`；成员回填走既有 `GET /api/workspace/{id}/members`。

**⚠️ 上线时踩到的迁移字符集坑**：部署脚本 `docker exec … mysql` 的客户端默认 `character_set_client=latin1`，于是 V13/V14/V15 里的**中文字面量被按 latin1 解释再转 utf8mb4 双重编码**入库——线上 `legacy-default` 的 `workspace_name` 变成「å›¢é˜Ÿå°—…」，顶栏下拉直接显示乱码（应用侧经 JDBC 的写入路径不受影响）。修法=执行 SQL 必须带 `--default-character-set=utf8mb4`（v64 脚本的 `mysql_q` 已加），存量乱码用 `deploy/tmp-fix-v64-utf8.sql` 一次性纠正（行数据 + 表/列注释，末尾自带 hex 自检）。附带发现：`mysql -e` **忽略 stdin**，跑 SQL 文件必须走无 `-e` 的 stdin 分支，否则一条语句都不执行却返回 0。

**验证**：`mvn -f teapot-ai-server/pom.xml package -DskipTests` 通过；前端 `tsc -b && vite build` 通过；线上顶栏下拉显示「存量空间」且 ⋯ 菜单出现「编辑空间」。

**后续项（未做）**：转让 owner、把「成员管理」独立成抽屉（当前与改名同弹窗）；被移出空间的成员其名下 Agent 仍留在该空间（只影响可见性，不做归属回收）。

### §28.13 对话页也要吃空间上下文（2026-10-24 线上问题修正）

§28.6 落地时只把 `workspaceId` 接进了 Agents 管理页，`Chat.tsx` 与顶栏 `AgentSelector` 的 `agentList({page:1,size:100})` 仍是裸调 → 后端按 §28.3 的默认视图解释成**个人空间**。而 V14 把全部存量 Agent 回填进了 `legacy-default`（线上实测：`general-assistant/digit-tim/teapot` 三条启用记录的 `workspace_id` 都是 1，`teapot_workspace` 里没有任何 personal 行），个人空间视图天然为空。用户顶栏明明选中「存量空间」，对话页却显示「暂无可用 Agent」，直观感受就是**升级后无法对话**。

修法（前端，无接口变更）：
- `Chat.tsx`、`AgentSelector.tsx` 接入 `useWorkspaceStore`，未 `loaded` 时先 `load()`，拉取时传 `workspaceId: activeId ?? undefined`，并把 `activeId` 列为重查依赖——与 Agents 页同一姿势。
- 对话页选中项的校正条件从「URL 没有 agent」扩成「URL 的 agent 不在本空间列表里」：切空间后若沿用旧 `?agent=`，会停在一个该空间不可见的 Agent 上。
- 空列表文案按 `activeId` 分叉：个人空间提示「可在顶栏切换到业务空间」，业务空间提示去 Agent 管理创建。

**验证**（2026-10-24 13:31 UI-only 发布，`deploy/tmp-deploy-v66-chat-ws.sh`，回滚点 `ui.bak-20261024-133058`）：
- `npm run build` 通过，入口产物 `index-CyuSF7zF.js`；线上 asset 与本地 dist **MD5 逐位相同**（`59bc640a4d8a3182fafb3423a554e1df`，4,562,234B），两道 bundle 探针都命中（新空态文案 + §28.12 的「编辑业务空间」）。
- 数据面冒烟（admin 登录态）：`/api/workspace/list` 回 `personal:admin` + `legacy-default`；裸调 `/api/agent/list`（不带 workspaceId，即修复前对话页的姿势）`total=0`；带 `workspaceId=1` 回 `general-assistant/digit-tim/teapot` 三条——与上文根因判断一致。

**遗留（仍待产品定）**：非白名单且非 `legacy-default` 成员的启用用户（`viewer / tmp-verify-0816b / cs_awp_liu / jujn / diego`）在任何空间视图下都看不到 Agent——这不是本节的 bug，是 §28.10 的数据面待办：要么给他们补 `teapot_workspace_member`，要么配 `TEAPOT_WORKSPACE_WHITELIST`。

### §28.14 空间动作收进下拉框（2026-10-24 顶栏改版）

§28.6 的顶栏空间簇是「下拉 + ＋ + 」三个并排控件，动作藏在两个独立按钮里，用户看不出它们属于当前空间。本次改为**只留一个空间下拉**，把「新建空间 / 编辑当前空间 / 移动 Agent 到空间」三项收进下拉弹层底部（分隔线下的 `Menu`，图标沿用 `@agentscope-ai/icons` 的 `SparkPlusLine / SparkEditLine / SparkMoveLine`，菜单项写法同 `UserMenu.tsx`）。

实现要点（`WorkspaceSwitcher.tsx`，纯前端，无接口变更）：
- `Select` 改受控 `open` + `onOpenChange`，用 `popupRender`（antd 5.29 起 `dropdownRender` 已 deprecated）在选项列表后追加动作区；点动作先 `setWsOpen(false)` 再开对应弹窗。
- 动作区外层 `onMouseDown={(e) => e.preventDefault()}`：不拦 mousedown 时 `Select` 会先失焦收起弹层，`click` 根本到不了 `Menu`。
- `popupMatchSelectWidth={false}`：动作文案比 140px 的选择框宽，跟随宽度会截断。
- 行为约束不变：「编辑当前空间」仍只对 `myRole === 'owner'` 且非 personal 的空间出现（§28.12）；「移动 Agent」依旧读 `teapot:lastAgent`，未选 Agent 时提示去对话页选。

**验证**（2026-10-24）：
- `npm run build`（`tsc -b` + vite）通过，4m17s，无类型错误。
- 本地无后端（9126 未监听），交互实测走 scratch 预览页：`scratch/ws-preview.{html,tsx}` 挂 `WorkspaceSwitcher`，把 `src/api/http` 的 axios 实例换成有记录的假适配器（`http.defaults.adapter`），既看点什么也看发了什么请求。逐条实测：
  - 弹层结构 = 3 个空间选项 + 分隔线 + 动作 `Menu`；动作行 40px 高带图标、文案无截断，弹层宽度自适应到 189px（选择框仍 140px）。
  - 当前空间为**个人空间**或 **`myRole=member` 的团队空间**时，动作区只有「新建空间 / 移动 Agent 到空间」；owner 团队空间才出现「编辑当前空间」——§28.12 的判定收进下拉后仍然成立。
  - 点动作后弹层进入 `ant-slide-up-leave` 收起（`setWsOpen(false)` 生效），且动作行 `mousedown` 阶段弹层仍在——`preventDefault` 那道保护是真需要的。
  - 编辑：打开即 `GET /api/workspace/1/members`，名称/描述/成员 tag 全部回填；保存发 `PUT /api/workspace/1`，body 带改名后的 `workspaceName` + 完整 `memberUserIds`。
  - 新建：`POST /api/workspace/create`；移动：`PUT /api/agent/teapot/workspace`，body `{"workspaceId":null}`（默认目标个人空间）。
- **未做**：像素级目视核对。内置浏览器该页 `visibilityState=hidden`、viewport 0×0，指针动作与截图都不可用，以上几何/布局均由 DOM 量得。

### §28.15 详情页要能点回列表（2026-10-24 顶栏分段导航修正）

用户截图反馈：在 Agent 详情页（`/agents/digit-tim`）里点顶栏已经高亮的「Agent」分段项没有任何反应，也没有别的返回入口，等于被困在详情页。

根因是 `Segmented` 的受控用法：`selectedKey` 由路径前缀归并得出（`/agents/:key` → `/agents`），所以详情页上「Agent」项本身就是选中态；antd 的 `Segmented` 只在**切换到不同 value** 时触发 `onChange`，点已选中项不回调 → `navigate` 不会被调用。技能详情页同理。

修法（`AppLayout.tsx`，纯前端）：`options` 的 `label` 由字符串改为包一层 `<span onClick>`，`stopPropagation` 后按 `location.pathname !== i.key` 判断——当前命中的是子路由就 `navigate(i.key)` 回到列表；其余逻辑（`onChange` 负责正常切换）不动。

**验证**（2026-10-24，本地无后端，走 `scratch/nav-preview.{html,tsx}` 探针页 + 内置浏览器 `evaluate_script`，读真实 `location`/状态而非截图）：

| 用例 | 结果 |
| --- | --- |
| 修复后，详情页点已选中「Agent」 | `/agents/digit-tim` → `/agents`（返回列表） |
| 修复前（不挂 label span，`B_spanGone=true`），同一路径点已选中 | 停在 `/agents/digit-tim` —— bug 复现 |
| 修复前，点其它分段 | `/chat` 正常 |
| 修复后，点其它分段 | `/skills` 正常（`onChange` 链路未被 `stopPropagation` 打断） |

探针页与 5199 端口的临时 vite 已删除（scratch 不入库）。**未做**：完整 AppLayout 下的目视核对（该改动已随 v67 上线，结论仍只由上述等价 DOM/路由实测得出，待用户在真机点一次确认）。

---

## §29 Agent 自带模型（自定义模型 + 自带密钥，2026-10-24）

### §29.1 需求与决策

系统模型下拉原本只能选 `t_model_entry` 里管理员登记、且按空间开放（§28.11）的模型。用户要求：**同一个下拉里既能选系统分配的模型，也能选「自定义」**——自定义时像系统模型配置那样，在 Agent 的 Basic Info 分区里自带一条模型记录。

两条已拍板的设计决策：
- 自定义模型**带自己的 apiKey**，AES-256-GCM 加密入库（复用 §21/沙箱记录那套 `ConfigCryptoService`，主密钥 `TEAPOT_SECRET_KEY`）。
- 自定义选项**覆盖全部五处模型下拉**：主模型 + 压缩摘要 + 意图守卫判定 + 记忆摘要 + 会话标题（各界点子模型也可指向自带模型）。

### §29.2 数据落点：feature 命名空间，无 DDL

新增 `t_agent.feature.customModels` 数组，与 `t_model_entry` 同构但凭证随 Agent 存。刻意不建新表：Agent 创建时还没有 `agentKey`，"先存凭证再存 Agent" 会有外键鸡生蛋问题，而 feature 是一次性整体写入的。

```json
{ "customModels": [
  { "provider": "dashscope", "modelName": "qwen3.8-max", "displayName": "我的key",
    "baseUrl": "", "apiKey": "v3:<base64(iv|ct|tag)>", "capabilities": "image,video" }
] }
```

`AgentFeature` 侧（`NS_CUSTOM_MODELS`）：`getCustomModels()/setCustomModels()`（空表 = 移除命名空间），`CustomModel.modelId()` 生成 `provider:modelName`，`capabilitySet()` 归一能力位。校验：provider ∈ {dashscope, openai}、modelName 必填、**apiKey 必填**、`baseUrl` 若填必须 `http` 开头、能力位 ∈ {image, video}、`provider:modelName` 不得重复、**条数 ≤ 1**（`CUSTOM_MODEL_MAX`，§29.8；存储仍是数组，按 modelId 查凭证的形状不变）。

### §29.3 密钥三原则：加密入库 / 留空沿用 / 出参永不出服

`AgentService` 链路（`validateFeature` 增加 `previousFeatureJson` 形参）：

1. **加密入库** `sealCustomModelKeys(feature, previousFeatureJson)` 在 `feature.validate()` **之前**执行：传了明文 → `crypto.encrypt`；留空 → 从上一版按 `modelId` 继承密文（与沙箱/OSS 记录「留空=不改」同语义）。`TEAPOT_SECRET_KEY` 缺失却提交新密钥 → 直接 `BizException`，不静默存明文。改 provider 或模型名视为新模型，必须重填密钥。`hasApiKey` 是出参派生值，入库前置 `null`。
2. **出参脱敏** `redactSecrets(agent)` 覆盖全部六个出口：`list` / `detail` / `create` / `update` / `updateAvatar` / `moveWorkspace`（均在 `mapper.update` 之后调用），把 `apiKey` 清空、置 `hasApiKey = 原值非空`。密文不出服务端。
3. **反回落**：`validateCustomModels` 在封印之后仍见空 apiKey = 用户从未填写且无历史密文 → 拒绝保存。装配期 `customCredentials()` 遇到主密钥缺失或密文不可解 → 点名模型抛错。两处都不允许"悄悄用 `DASHSCOPE_API_KEY`/`OPENAI_API_KEY` 顶上"，否则用户自带模型的调用会计费到平台账号。

### §29.4 空间闸门放行自带模型

`assertModelOpen(modelId, bucket, featureJson)` 收敛了原来散在 create/update 里的两处判断：白名单用户放行；**modelId 命中该 Agent 的 customModels 放行**（凭证是用户自己的，不该受空间授权约束）；其余走 `modelService.assertOpenToWorkspace`。update 里这次校验被**移到 feature 合并之后**——同一次保存可能同时带来新模型和它的密钥，顺序反了会被闸门先拒。

### §29.5 运行时：ModelRegistry 凭证槽 + 六个装配点

`ModelRegistry` 新增 `Credential(apiKey, baseUrl, capabilities)` record，缓存 key 追加 `#cred<sha256 前 12 位>`（同 modelId 不同密钥/网关必须分槽，否则会串号）；`resolve(modelId, thinking, cred)` / `resolveNoThinking(modelId, cred)` / `capabilities(modelId, cred)`（凭证声明的能力位优先于 `t_model_entry`）；`evict(modelId)` 前缀匹配把 think/nothink/cred 变体一并清掉。baseUrl 优先级：Agent 自带网关 > 入口配置 > `OPENAI_BASE_URL`。能力位含 image/video 时 DashScope 显式走 `EndpointType.MULTIMODAL`。

`AgentBuilder.assemble()` 先 `customCredentials(feature)` 建 `modelId → Credential` 表，再在每个位点用 `credOf(customCreds, modelId)` 取用（取不到 = null = 走系统入口与环境变量，存量 Agent 行为不变）：**主模型**、**压缩摘要**、**记忆摘要**（两处构建分支）、**守卫判定模型**（llm 档）、**A2UI 渲染子模型**、**会话标题模型**（含"无独立配置时沿用主模型 + thinking"那条分支）、以及**媒体模态守卫的能力位查询**。

### §29.6 前端

- Basic Info 的「模型来源」单选（`modelSource`，纯 UI 字段，保存时从 payload 里解构掉）：**空间模型 / 自定义模型 二选一**（§29.8）。选自定义才渲染那一行字段——供应商下拉 / 模型名（必填）/ 展示名 / API Key（`Input.Password`，占位符按 `hasApiKey` 在「已配置，留空不修改」与「sk-...」间切换，校验器允许"留空但已配置"）/ baseUrl / 多模态能力多选；此时 Model 下拉 `disabled` 且值随「模型名」实时同步（`useEffect` 写 `modelId`），切回空间模型则清空自带行、并在主模型正指向它时清掉 `modelId` 逼用户重选。回填时 `modelSource = customModels.length > 0 ? 'custom' : 'system'`，`customModels` 只取第一条（历史多条截断）。
- `components/modelOptions.ts`：`modelSelectOptions(systemModels, customModels)` 产出两组（「系统模型」/「本 Agent 自定义」，空组不下发）；自定义组的 label 是 `展示名（provider:model）`。主模型与压缩/守卫/记忆/标题四处子模型下拉同源（`Form.useWatch` + `useMemo`），子模型在自定义模式下仍可两边各选。
- 保存时按来源分叉：`modelSource === 'custom'` 且 provider/模型名齐全才下发 `feature.customModels = [该行]` 并把 `payload.modelId` 钉成它的 `provider:model`；否则（选空间模型，或自定义没填完）`delete feature.customModels` —— 服务端见不到该命名空间即清除密文。`hasApiKey` 不提交。
- 顺带修正：`modelPresets()` 由裸调改成 `modelPresets(d.agent.workspaceId ?? 0)`，系统模型下拉按 Agent 所属空间过滤，与 §28.11 的保存闸门同源。

### §29.7 验证与风险

**构建门禁**：`mvn -f teapot-ai-server/pom.xml clean package -DskipTests` 通过；前端 `npm run build`（`tsc -b` + vite）通过，入口 `index-CHknkqZ0.js`。

**v67 上线**（2026-10-24 17:24，`deploy/tmp-deploy-v67-custom-model.sh`，驱动 `Temp/ssh114/v67-deploy.js`；回滚点 `app.jar.bak-20261024-172434` + `ui.bak-20261024-172434`）：
- 进程环境里 `TEAPOT_SECRET_KEY` 计数为 1（只数键名不取值）——§29 的加解密在主密钥缺失时会直接拒绝保存，预检必须先确认它被 systemd 载入了。
- 构件探针：`AgentFeature$CustomModel.class` / `ModelRegistry$Credential.class` 在包内，`AgentService.sealCustomModelKeys`、`AgentBuilder.customCredentials` 字节码命中，v65 的 `WorkspaceUpdateRequest.class` 未回退。
- 前端产物与本地 dist 逐位相同（入口 md5 `35070ecc…`、4,568,731B），bundle 探针「本 Agent 自定义 / 添加自定义模型」命中，§28.12 的「编辑业务空间」仍在。
- 服务重启 11.3s 起来，`/api/agent/list`、`/api/model/presets` 无凭据应答 401（鉴权过滤器活着 = 应用在服务）。
- **数据面四条规则（无密钥拒保存 / 密文入库 / 回显脱敏 / 留空沿用）未能线上实测**：`deploy/tmp-smoke-v67-custom-model.sh` 第一步 admin 默认口令就 401，按「绝不重试口令」立即退出（失败的这一次会计入 admin 的锁定计数，若随后登录不上是 10 分钟锁而非物质问题）。冒烟只建临时 Agent 再软删，退出后复查 `t_agent where agent_key='v67-smoke'` 行数为 0、启用 Agent 仍 3 条，远端口令临时文件已 `rm`。**这四项目前只有代码审查与本地编译担保，需要拿一个能登录的账号在界面上走一遍**：填自带密钥保存 → F12 看 `/api/agent/detail` 的 `feature.customModels[0]` 是否只有 `hasApiKey` 没有 `apiKey` → 不改密钥再保存一次 → 用该模型发一轮对话确认真的用的是用户密钥而不是平台密钥。

**风险 / 后续项**：
- **baseUrl 无白名单 → SSRF**：用户可让服务端向任意 http 地址（含内网/云元数据端点 `169.254.169.254`）发带 Authorization 的请求。当前仅校验 `http` 前缀。要么加域名 allowlist，要么由网络侧限制出网。
- 模型名不做供应商侧存在性校验，填错要到运行时才报错。
- 主密钥轮换会让存量密文不可解 → 装配整轮失败（按设计点名报错，用户重填即可），但没有 v2 双密钥并存的平滑方案。
- 删除一条仍被 `runtime/guard/memory/...` 引用的自带模型：该 modelId 既不在系统开放列表也不在自带列表里，会被 §28.11 闸门拒绝保存（表现为"改不动"），提示信息是闸门的原文，暂未专门优化。
- `mcp`/`sandbox` 等命名空间与自定义模型无耦合，`AgentFeature` 的未知命名空间原样保留机制保证老 feature 不丢字段。

### §29.8 改成「模型来源二选一、只允许一条」（2026-10-24 用户看图后修订）

v67 的形态是「Model 下拉里混进一个自定义组 + Basic Info 下面一个可加无数行的 `Form.List`」，用户看着就是"能配好几个自定义模型"。当场否掉：**要么用空间分配的模型，要么自定义一个，不存在多个**。

落地（`AgentDetail.tsx` + `AgentFeature.java`，接口与存储形状不变）：
- 新增 UI 字段 `modelSource`（`Radio.Group` 空间模型 / 自定义模型），保存时从 payload 解构掉，不发后端；回显按 `customModels.length` 反推。
- 自定义模式下字段固定落在 `['customModels', 0, *]`（不再是 `Form.List`，也就没有"添加/删除"两枚按钮和那条空态提示），Model 下拉 `disabled` 且由 `useEffect` 跟着「供应商 + 模型名」实时改写。
- 切回空间模型：`customModels` 置空数组；若主模型正指向那个自带 id 就清空 `modelId`，避免留一个"本空间不可见"的残值让用户在保存时被 §28.11 闸门莫名其妙拒掉。
- 后端 `CUSTOM_MODEL_MAX` 20 → **1**，把约束真正钉住（数组形状保留：`ModelRegistry` 按 modelId 查凭证、`sealCustomModelKeys` 按 modelId 继承密文都不需要改）。

**验证**：`mvn -o -pl teapot-ai-core -am package -DskipTests` 通过；`npm run build`（`tsc -b` + vite）通过，入口 `index-CSOFXUMQ.js`。中途一次类型失败值得记：主模型 `options` 写成"自定义时给扁平单项、系统时给分组项"的三元，antd 推断成两个不兼容的数组联合 → TS2322；改成两种情况都用同一份分组 `modelOptions`（自定义时靠 `disabled` 锁住）后消失。**未做**：真机目视（尚未发布）。

### §29.9 弹窗关闭按钮钉在卡片右上角（2026-10-24 用户截图反馈）

用户拿「新建业务空间」弹窗的截图指出 ✕ 出现在标题下方左侧，要求放右上角。

先做根因探针（`scratch/modal-preview.tsx` 同时挂设计库的 `Modal` 与 `MobileModal`，导入 `src/index.css` 以带上 tailwind，用内置浏览器 `evaluate_script` 量 `getBoundingClientRect`）：**在当前依赖版本下两个分支的 ✕ 都在标题行右侧**（`.spark-modal-title-wrapper` 是 `display:flex; justify-content:space-between`，✕ 的 x 坐标在标题盒子右边之外），也就是说截图那个"掉到标题下方"没能在探针里复现。能确定的是它的成因类别：库把 ✕ 当作**标题行的 flex 子项**（`closeIcon: null` + 自己渲染 `.spark-modal-title-close`），所以它的位置跟着标题的宽度/换行漂移，而不是钉在弹窗上——标题变长、字体变大或宽度受限时都可能被挤下去。

因此修法取"让位置与标题无关"这条：`src/index.css` 末尾加三条规则——`.ant-modal-content` 设 `position: relative`，`.spark-modal-title-close` 绝对定位 `top/right: 16px`（与库给 `.ant-modal-close` 的默认偏移一致），`.spark-modal-title` 补 `padding-right: 28px` 让长标题不压在图标下。全站弹窗共用这一处，桌面/移动两条链路结果同一个位置。

**验证**（同一探针页，量相对 `.ant-modal-content` 的偏移）：两个分支均 `position: absolute`、`rightGap=17`、`topGap=17`（16px + 1px 边框），且与标题行顶边对齐（`inRowWithHeader=true`）、标题 `padding-right: 28px` 生效。探针页与 5199 端口的 vite 已删除。**未做**：真机目视（未发布）；也没拿到用户那台设备上的 DOM，若发布后位置仍不对，需要在用户浏览器里现场量一次。

**§29.9 修订（2026-10-28，用户再拿截图指出"很多地方还是错的位置"）**：这回找到机制了。原规则只钉 `right:16px`，没管 `left` —— 绝对定位盒只要从别处拿到一个 `left`（库运行时注入的样式、RTL 分支、或任何给 `left` 赋值的规则），就会**左右同时定住、被拉满整行宽度**，里面的 ✕ 字形自然贴到最左边，而盒子高度被 `top` 定住，看起来就像"掉到标题下方左侧"。实测复现：给那颗按钮强加 `left:0`，盒子从 20px 宽变成 357px 宽、起点 `dx=1`（贴左）。这也解释了为什么探针一直量不到 —— 本地环境里没有任何规则给它 `left`。

修法是把钉位写严，不给"另一侧"留口子：同时声明 `left/inset-inline-start: auto`、`width/height: auto`、`margin:0`、`transform:none`，并加 `!important`（库的样式是运行时注入的 CSS-in-JS，`<style>` 排在静态表之后，同特异性会赢）；选择器从 `.spark-modal-title-close` 扩到也覆盖 antd 自己的 `.ant-modal-close`，标题让位规则从 `.spark-modal-title` 扩到 `.ant-modal-title`。

验证（mock + vite，量相对 `.ant-modal-content` 的偏移）：绑定已有 Skill 弹窗基线 `dx=338 / w=20 / rightGap=17`；**注入高优先级 `left:0` 后位置不变**（计算值仍是 auto 的解析结果）；模拟旧写法（强行 `left:0 !important`）即复现截图故障（`dx=1 / w=357`），撤掉立刻恢复；第二个组件（工具分组「新建分组」弹窗）同样 `dx=263 / w=20 / rightGap=17`。桌面分支（临时把 `useIsPhone` 短路成 false 量的，量完已还原）也是右上角。

### §29.10 Skill 工坊下线：skill 只能从 Agent 里 Add Skill（2026-10-24）

§28.1 摘掉的是运行期「未绑定=全给」的兜底，但顶栏仍留着一个 Skill 工坊页（列表 + 新建/编辑 SKILL.md 的表单 + zip/git 导入 + 删除 + 立即同步）。用户判定这个入口不该存在：**Teapot 里不造 skill，平台上的 skill 完全通过 Agent 模式绑定**。三条已拍板的规则：

1. **工坊整页下线**：删 `pages/Skills.tsx`、`pages/SkillDetail.tsx`、顶栏 `/skills` 导航项与 `/skills`、`/skills/new`、`/skills/:name` 三条路由；旧书签 `/skills*` 重定向到 `/agents`（沿用 `/users → /system/users` 的兼容写法）。SKILL.md 的作者能力（`/api/skill/save`、`/preview`、`/detail`）就此没有前端调用方——**后端接口保留不动**（仍可用，只是不再有 UI），前端的 `skillSave/skillPreview/skillDetail` 与 `SkillDetail/SkillSaveRequest/SkillResourceItem` 类型作为孤儿删除。
2. **git 来源一起收进 Agent**：`components/AddSkill.tsx` 的 Add Skill 下拉给两项——「上传 Skill zip」（落点二选一：OSS 同名覆盖 / 平台库同名更新）与「从 Git 仓库导入」（临时 clone → 入平台库）。原工坊的「立即同步」（全局管控仓库 `skillGitSync`）挂在 git 导入弹窗内，仅当 `git/status.enabled` 时出现。权限沿用工坊判定：zip 要 `admin/developer` 且 OSS 未关，git 要 `admin/developer`；两者都不可用时整个按钮不渲染。
3. **解绑与删本体分开**：卡片上的开关只做 `bindSkill/unbindSkill`。删本体要等**没有任何 Agent 绑定**才给按钮（`SkillListVO.boundAgents` 为空，git 来源后端本就禁删故不给入口），且收紧为仅 admin（工坊原先任何能看到页面的人都能删）。后端 `SkillService.delete` 同点设闸：查到绑定行就 `BizException` 点名占用者，文案直接说"请先在对应 Agent 解绑再删除本体"。

**导入即绑定**：`AddSkillButton` 在 import 成功后逐个 `agentBindSkill(agentKey, name)`，全部成功提示"已导入并绑定"，有失败则 `message.warning` 点名哪些没绑上（不吞错），最后回调刷新列表。绑定失败不回滚导入——skill 已在库里，用户可以在列表里手动打开开关。

**验证**：`mvn -o -pl teapot-ai-core -am package -DskipTests` 通过；`npm run build` 通过，入口 `index-C6ngpBit.js`（4,526.96 kB，比上一版小约 22 kB，即两个页面下线的体积）。Add Skill 的链路用 `scratch/addskill-preview.{html,tsx}` 探针 + 假 axios adapter（`http.defaults.adapter`）实测请求序列：
- git 导入：`post /api/skill/import/git {url,branch:"",target:"mysql"}` → `post /api/agent/bindSkill/probe-agent {"skillName":"alpha"}` → 同 `beta` → `onAdded` 回调计数 +1（导入两个就绑两个）。
- 「同步全局 Git 仓库」按钮在 `git/status.enabled` 时出现在 git 弹窗内，点击发 `post /api/skill/git/sync`。
- zip 弹窗打开即渲染 `Upload.Dragger`，确认按钮文案「导入并绑定」；标题行的 ✕ 点击可关（顺带覆盖 §29.9 那颗按钮）。

探针页与 5199 端口的 vite 已删除。**未做**：`SkillListVO.boundAgents` 的真实数据面（需登录态，admin 默认口令已失效，见 §29.7）与 AgentDetail 技能分区的真机目视。

**风险 / 后续项**：
- **孤儿 skill 无人盘点**：工坊下线后没有"看全库/批量删"的地方，未被任何 Agent 绑定的 skill 只能从某个 Agent 的卡片上逐个删（正好落在"无绑定才可删"这条规则里）。日后要盘点就得补一个只读列表（本次讨论里被否掉的方案 B）。
- skill 本体仍是**跨空间共享**的全局资产（没有 `workspace_id`）：A 空间的人导入并绑定后，B 空间的用户在自己的 Agent 里也能看到并勾选它。§28 的空间隔离目前覆盖 Agent/模型，不覆盖 skill。
- 导入与绑定非原子：导入成功但绑定失败时 skill 会留在库里未绑定（有 warning 提示，不静默）。

### §29.11 工具调用与正文按发生顺序展示（2026-10-24 用户截图反馈）

用户截图：一轮里模型是「thinking → 调 `load_skill_through_path` → thinking → 调 `execute` → 出答案」交错发生的，但界面上答案文本先出现，红框里 `load_skill_through_path` / Thinking / `execute` 被挤成一坨排在后面，读起来像"先给结论再查资料"。

根因在前端桥接层 `chat/aguiBridge.ts` 的 `RUN_FINISHED` 收尾：它要把整轮的全量 output 回传给模板做最终合并，早先是**三段分组循环**（全部 reasoning → 全部 tool_call(+output) → 正文）。流式过程中顺序是对的，轮末这一次全量回传把顺序重排成了按类型分组。历史回放不受影响——`ChatSessionService.messages()` → `SessionMessageConverter` 是按存储的 `context` 消息与其内容块顺序逐条转的。

修法：解析器状态加 `timeline: string[]`，在三处"槽位首次出现"登记 id（`openReasoningBlock()` 的块 id、`TOOL_CALL_START` 的 callId、正文首段的 `ANSWER_MSG_ID`），`RUN_FINISHED` 改为**遍历 timeline** 输出对应块（tool 的 output 紧跟其 call 之后）。顺带删掉因此变成只写不读的 `state.toolOrder`。

**验证**：`scratch/order-preview.{html,tsx}` 探针直接喂 12 条合成事件（thinking×2、两次 tool_call 各带 result、正文），读回传的 `output` 数组顺序，实测为
`reasoning:asst-reasoning-0 → tool_call:callA → tool_call_output:callA-output → reasoning:asst-reasoning-1 → tool_call:callB → tool_call_output:callB-output → message:asst-answer`
与事件发生顺序一致（旧代码这段会是"两个 reasoning → 四个 tool 块 → answer"，由分组循环直接读出）。`npm run build` 通过，入口 `index-ZayGHZyr.js`。探针页与 vite 已删除。**未做**：真机看一轮真实的交错回复。

### §29.15 思考模式改三态：默认 / 开启 / 关闭（2026-10-25）

`runtime.thinkingMode` 此前在界面上是一个 Switch，只有"开/关"两档，而底层 `ModelRegistry` 早就是三态（`TRUE`=下发 `enable_thinking=true`、`FALSE`=下发 `false`、`null`=不下发，混合思考模型跟随服务端默认，多为开启）。装配处 `boolean thinking = Boolean.TRUE.equals(rt.getThinkingMode())` 把 `null` 和 `false` 折成了一个值，且布尔版 `resolve(modelId, boolean, cred)` 内部是 `thinking ? TRUE : null` —— 也就是说**旧的"关"从来不是"显式关闭"，它等于"默认"**，用户没法表达"我要显式关掉思考"。

改动：
- `ModelRegistry`：把三态版 `resolve(String, Boolean, Credential)` 由 private 提为 public（布尔版仍保留，内部委托）。
- `AgentBuilder`：`Boolean thinking = rt == null ? null : rt.getThinkingMode()`，原样透传到主模型与会话标题模型的兜底解析（其余子模型走各自的显式档位，不经这个变量）。
- `AgentFeature.Runtime.thinkingMode` 字段类型不变（本就是可空 `Boolean`），javadoc 补三态语义。
- 前端：`Thinking Mode` 由 `Switch` 换成 `Select`（默认 / 开启 / 关闭）。Select 的 `value` 放不了 boolean，表单内用字符串 `'default' | 'on' | 'off'` 承载，回显按 `undefined → 'default'` 映射，保存前还原：`'default' → undefined`（`runtime` 合并逻辑对 `undefined` 的既有处理就是删键，于是入库 JSON 里不带 `thinkingMode`）、`'on' → true`、`'off' → false`。对话页配置摘要的 KV 早已按 `!== undefined ? 标签 : '默认'` 渲染，无需改。

**验证**：`mvn -o -pl teapot-ai-core -am package -DskipTests` 通过；`npm run build` 通过，入口 `index-CVIDA6pk.js`。**未做**：三态各自的真机生效（要看 DashScope 请求参数与回复是否带 reasoning）。

**⚠️ 存量语义变更（待用户定，未擅自改库）**：线上 `t_agent` 里 `digit-tim` 与 `teapot`（两个启用中的 Agent）的 feature 存着 `"thinkingMode": false`。旧链路下它等于"不下发参数"；新链路下它等于"显式关闭思考"。qwen3 系混合思考模型不传参数时服务端默认开启，所以**这次发布后这两个 Agent 会从"会思考"变成"不思考"**。三个选项：① 发布同时跑一条幂等 SQL 把这两行的 `thinkingMode` 键删掉（等价于迁移到"默认"，行为与今天一致，之后用户想关再选"关闭"）；② 接受变更（认为当初的 off 就是想要不思考）；③ 前端回显把 `false` 也显示成"默认"（不推荐，等于把三态又压回两态且入库值仍会漂移）。
→ **已按 ① 执行**（v71，2026-10-25 15:48）：迁移前整行 `feature` 备份到 `/root/v71-feature-backup.tsv`（600），`JSON_REMOVE` 删键后 5 条 Agent 的 `runtime.thinkingMode` 全为 absent，`json_valid` 复查通过。

### §29.16 模型供应商全接入（策略模式，2026-10-25）

AgentScope 有六个模型供应商扩展模块，teapot 的 `ModelRegistry` 只 switch 了 `dashscope` / `openai`。本次把六家全接上，并把那段 switch 换成策略分发。

**形状（有先例）**：照 `core/storage` 的存储载体策略 —— 接口带字符串 key（`ImageStorageStrategy.name()` → 这里 `provider()`），实现类各自 `@Component`，按字符串分发，找不到就 `BizException("不支持的模型供应商：" + provider)`（文案同 `ChannelFactory` 的"不支持的…"）。收集方式用 `List<ModelProviderStrategy>` 构造注入收成 `Map`（List 拼装先例 = `AgentBuilder` 拼 skill repos），重复 key 直接启动失败而不是静默覆盖。新包 `core/model/provider/`（按功能域分包）。

**为什么不用 SDK 自带的 SPI**（`io.agentscope.core.model.spi.ModelProvider` + `META-INF/services` + `ModelCreationContext`，六家都已注册）：`ModelCreationContext.enableThinking` **只有 dashscope 和 openai-compat 的 provider 消费，另外四家的实现直接忽略它** —— 而 §29.15 的三态正是核心语义，交给 SPI 会变成"界面能选、运行期静默无效"。另外实例缓存分槽（`#think` / `#nothink` / `#cred<指纹>`）、`evict` 的前缀约定、能力位→`EndpointType.MULTIMODAL` 的判定都得留在 teapot。所以策略接口在 teapot 侧，实现内部各调各家 builder。

**新增/改动**：
- `ModelProviderStrategy`（`provider()` / `thinkingSupport()` / `apiKeyRequired()` / `create(Spec)` + `record Spec(modelId, modelName, apiKey, baseUrl, thinking, capabilities)`）与 `ThinkingSupport { TRISTATE, ON_ONLY, NONE }`。
- 六个实现：`DashScopeStrategy`、`OpenAIChatStrategy`、`OpenAIResponsesStrategy`、`AnthropicStrategy`、`GeminiStrategy`、`OllamaStrategy`。各家自己 `@Value` 读自己的环境变量（`DASHSCOPE_API_KEY` / `OPENAI_API_KEY`+`OPENAI_BASE_URL` / `ANTHROPIC_API_KEY` / `GEMINI_API_KEY` / `OLLAMA_BASE_URL`），"留空回落 env"的定序不再由注册表持有；密钥定序（Agent 自带 > env，缺失即按历史文案报错）收在接口的 `default resolveApiKey(spec, envName, envValue)` 里，`apiKeyRequired()` 就是它判 Ollama 这类无凭证供应商的开关。
- `ModelRegistry`：删掉三个 `@Value` 字段与整段 switch，改为策略 Map；公共签名与缓存 key 规则一字未动（`AgentBuilder` / `ModelService` 调用点零改动）。新增 `providers()` / `thinkingSupport(modelId)` / `thinkingSupportOf(provider)`，经 `ModelService` 委派给上层。
- **思考档矩阵**（各家 SDK 实测出来的表达力，界面与保存校验的唯一依据）：

| provider | 通道 | 档位 |
| --- | --- | --- |
| dashscope | `enableThinking(Boolean)` | TRISTATE |
| ollama | `OllamaOptions.thinkOption(ThinkBoolean.ENABLED/DISABLED)` | TRISTATE |
| openai-official | `GenerateOptions.reasoningEffort("medium")` | ON_ONLY（无显式关闭通道，`thinkingBudget` 在该模块是 fail-fast 抛异常） |
| openai | 无（per-request `reasoning_effort` 对非推理模型会被拒） | NONE，选档时 warn 并忽略 |
| anthropic | 只有 `additionalBodyParam("thinking", …)` 透传且需配 max_tokens | NONE（隐式契约，不猜） |
| gemini | 只有 `thinkingBudget(Integer)`，非空即"要求思考并限预算" | NONE（挑固定数值等于替用户决定成本） |

- **保存期闸门** `AgentService.assertProviderAndThinking(modelId, featureJson)`：自带模型的 provider 必须在已接入清单里；显式选了「开启/关闭」而该供应商表达不出来 → 直接拒保存并写"请把思考模式改回「默认」"。update 里按**合并后的主模型**跑（只改思考档不动模型的保存同样拦）。
- **消歧不留第二份清单**：`AgentFeature.MODEL_PROVIDERS` 常量删除（原来注释就写着"与 ModelRegistry 实际支持的一致"，属于会漂的复制品），模型层只校验 provider 非空/不含冒号；白名单唯一来源是策略 Bean，经新端点 `GET /api/model/providers` 出给前端。
- 依赖：`teapot-ai-bom` 加四条 `agentscope-extensions-model-{openai-official,anthropic,gemini,ollama}`，`teapot-ai-core` 对应四条无版本声明；三方 SDK（anthropic-java / google-genai / openai-java）由扩展模块自带传递依赖。
- 前端：`AgentDetail` 的 provider 下拉与「默认/开启/关闭」联动 —— 不支持的档位置灰且 label 写明原因，换主模型后若原档位不再可表达则自动回落「默认」并 `message.info` 说明；`Models` 管理页的 provider 下拉同源（拉不到时回落静态名单）。

**验证**：`mvn -pl teapot-ai-core -am package -DskipTests` 通过；`npm run build` 通过，入口 `index-OOJAayHp.js`。**坑（必须记）**：这四个新模块带来 `kotlin-reflect`、`jackson-module-kotlin`、`guava:33.4.0-android` 等传递依赖，本机 .m2 里没有 → **`mvn -o` 离线构建会失败**，首次必须联网（Aliyun 镜像）拉一次；另外 `mvn … | tail` 之后 `echo $?` 拿到的是 `tail` 的退出码，看构建结果要用 `${PIPESTATUS[0]}`，否则会误读成绿。**未做**：除 dashscope 外五家都没有密钥，真机调用未验证（尤其 ollama 的 `think` 字段与 openai-official 的 `reasoning_effort` 落到请求体的实际形状）；`/api/model/providers` 的响应形状只由代码推得。

**风险 / 后续项**：
- `t_model_entry.provider` 是自由文本且历史行不受新闸门约束（只在 create/update 校验）——库里若已有未接入的 provider，解析时才报"不支持的模型供应商"。
- `openai-official` 的"开启"固定落 `reasoning_effort=medium` 是产品选择，不是从 SDK 推出来的必然；要调档需要新增配置项。
- gemini / anthropic 若要真正支持思考档，需要各自加"预算/力度"配置项（`thinkingBudget` / `thinking.budget_tokens`），届时 `ThinkingSupport` 相应上调。
- Ollama 无凭证：`apiKeyRequired()=false` 只影响策略内部是否要求密钥，平台侧"必须配 key"的其它检查（若有）未逐条复核。

### §29.17 Agent 自带沙箱承载（E2B，2026-10-26）

沙箱此前只能引用系统级记录（`t_sandbox_config`，§22.2「启用沙箱必须选一条记录」）。用户要求：**沙箱也要像 skill / 自带模型一样支持 Agent 级配置**。三条已拍板的规则（都选了保守档）：

1. **二选一、不混用**：一个 Agent 的沙箱要么整个是平台记录的，要么整个是自己自带的。不做"密钥自带 + 网关用记录"的逐字段回落 —— 那种半截配置在排查时说不清是谁的沙箱。
2. **只支持 E2B**：两条链路字段不同构（E2B 三项 apiKey/控制面地址/域名，AgentRun 四项 apiKey/accountId/region/mcpServerUrl），且现网只有 e2b 记录、`region` 的判定在三处还不一致（`linkConfigured` 要、`AgentRunConnection.configured()` 不要、Controller 的 `agentrunConfigured` 不要）。自带 AgentRun 会把这些分歧一起带进来，故先不做。
3. **持久化限制为本地快照/不持久化**：NAS 挂载点、快照目录、E2B `codec` 都只能来自服务端 yml，不是 Agent 该决定的东西（E2B 链路选 NAS 现在也只是告警后降级 no-op）。

**数据与实现**（复用 §29 自带模型那套，无 DDL）：
- `AgentFeature.Sandbox` 加 `e2bApiKey`（密文入库）/ `e2bApiBaseUrl` / `e2bDomain` / `hasE2bApiKey`（派生回显，不落库），`isCustom()` = 任一 e2b 字段非空。
- `AgentService.sealSandboxKey(feature, previousFeatureJson)` 与 `sealCustomModelKeys` 同形：传明文→`crypto.encrypt`；留空→沿用上一版密文；缺 `TEAPOT_SECRET_KEY` 直接拒；`hasE2bApiKey` 入库前置 null。`redactSecrets` 从"只处理 customModels 且空表就 early-return"改成两段独立处理（否则有沙箱无模型时会漏脱敏）。
- 校验（`AgentFeature.validate` 内，仅 `enabled` 时生效）：与 `sandboxRecord` 互斥、三项齐备、**模板名必填**（模板 ID 是账号内资源，回落平台默认必然 404）、`link` 只能是 e2b/留空、`persistence=NAS` 拒绝。"启用但无承载"的存量门控报错文案补上"或改用自带 E2B 凭证"。
- 解析点唯一：`AgentBuilder.customE2bRecord()` 把自带凭证组装成一条**虚拟 `SandboxConfigDO`**（`linkType=e2b`、`name=(agent:<key>)`），于是下游的链路判定、`applyE2b`、`resolveE2bTemplate` 全部与系统记录同构、零改动。密文不可解 / 主密钥缺失 → 抛错点名 Agent，**不静默改用平台 E2B 密钥**（否则用户自带沙箱的开销会计费到系统账号）。
- 前端 Sandbox 分区加「沙箱承载」单选（系统记录 / 自带 E2B 凭证，`source` 是纯 UI 字段，保存时剥掉）；自带时隐藏记录下拉、出三项凭证输入（API Key 按 `hasE2bApiKey` 切占位符）、持久化选项去掉 NAS、模板改必填；切回系统记录时整段 e2b 字段被剥掉 = 凭证随命名空间消失。「启用沙箱」开关不再因"没有系统记录"被禁用。

**验证**：`mvn -pl teapot-ai-core -am package -DskipTests` 通过；`npm run build` 通过，入口 `index-NCmZqDoK.js`。模型层 11 条规则用一次性 main 直跑真类验证（classpath = `teapot-ai-core/target/classes` + `common/target/classes` + 从 app.jar 解出的 `BOOT-INF/lib/*`，跑完即删）：三项齐备/本地快照/只引用记录/未启用残留 → ACCEPT；缺域名、缺模板、与记录混用、NAS、`link=agentrun`、启用无承载且全局未接入 → REJECT 且文案点名原因，**11/11 通过**。**未做**：真实 E2B 账号的端到端（无第二套 E2B 凭证可测）、`customE2bRecord` 解密路径的运行时验证。

**风险 / 后续项**：
- 自带凭证与记录一样是**跨空间可见的 Agent 属性**，但沙箱没有空间隔离（同 §29.10 的 skill 情况）；A 空间成员若能看到 B 空间 Agent 的详情，会看到它的控制面地址与域名（密钥本身永不回显）。
- `templateName` 在自带模式下必填，但**不校验该模板在用户账号里真的存在**，填错要到首轮调用才报。
- 切回「系统记录」即丢弃自带密文（不是隐藏），再切回来要重填 —— 与 §29 自带模型"改 provider/模型名视为新模型"同一取向，避免旧密文被误挂到别的配置上。
- AgentRun 自带凭证、以及把 `region` 判定三处分歧对齐，是这笔的后续项。

### §29.18 系统配置从顶栏挪进头像下拉（2026-10-26）

顶栏分段此前是「对话 / Agent / 系统配置」，用户要求把系统配置收进头像下拉。它是低频管理动作，不该和对话/Agent 抢主导航位。

- `UserMenu.tsx`（顶栏与对话页左栏 UserFooter 共用）在「更换头像」与「退出登录」之间插一项「系统配置」，仅 `hasRole('admin')` 可见，点击 `navigate('/system')`（该路由本就重定向到 `/system/models`）。
- `AppLayout.tsx` 删掉 admin 分支的 `/system` 分段项与随之无用的 `SparkSettingLine` 导入；原先为"对话页不显示系统配置"而写的 `navItems = isChatPage ? items.filter(...) : items` 变成死代码，一并删掉并把四处引用改回 `items`。
- **一个连带修正**：`selectedKey` 的兜底是 `|| '/chat'`，系统配置从分段里消失后，人在 `/system/*` 会被错误高亮成「对话」。改成命中不了时返回 `''`（Segmented 收到不在 options 里的值就不高亮任何项）。

**验证**：`npm run build`（`tsc -b` + vite）通过，入口 `index-DDvA8cEY.js`。**未做**：真机点击（下拉项可见性依赖登录角色，本地无后端）。

### §29.19 界面不写解释性文字（2026-10-26 全站清理 + 立规）

用户判定：解释性冗余文本弄脏界面。先删了两处（Skills 分区副标题、系统配置页同类），随后全站清点出 28 处常驻解说文字，按"删了会不会丢信息"分档处理。

**规矩（以后照此执行）**：新增说明性文字一律进 `tooltip`（悬浮才见，不占界面）；界面上只留 label、字段名和**必要约束**（如"创建后不可修改"、"≤ 20MB"、"需公开可读"）。空态、错误 Alert、状态标签不算解说。

**A 档 删除（12 处）**：SystemConfig 四张卡片的「支持多条记录…；Agent 在各自配置页选择其一启用。凭证加密入库，界面不回显明文」（同一句式重复 4 次、"加密不回显"重复 3 次）；AgentDetail 的 sandbox/channel 分区底部「当前共 N 条…记录；启用时必选其一作为承载，链路与凭证由记录决定」（保留「前往系统配置」按钮，删解说）；Models 页表格上方的 provider:model / API Key 介绍段；Agents 页标题副标题「管理与配置你的 AI Agent」；渠道分区「渠道会话不进 Web 对话台…保存后自动重启连接」；MultiAgent 开关下「关闭后将禁用 subagent 工具…」。

**B 档 压缩（6 处）**：Agent ID 的 extra 去掉「将作为 AG-UI 路由 key」；所属空间的 extra 整条删；无 OSS 记录的 Alert 去掉「请在系统配置-存储中新建记录」；生成模型覆写 Alert 去掉「不依赖模型自觉」；Add Skill 两个弹窗的解说只留约束（bucket/prefix 落点、"其它 Agent 里打开开关"、"另有一个全局管控仓库…" 全删）。

**转 tooltip**：记忆分区「请求级开关优先于本配置」并进「启用记忆」的 tooltip。

**保留未动**：空态文案、加载失败/联系管理员 Alert、GitHub 回调地址（用户要抄走的 URL）、PlanCards「只读 · 产出计划后请求批准」（状态语义）、所有 tooltip / placeholder / 校验报错。登录页品牌行「Agent 平台 · 登录」与顶栏 `v0902b` 版本号按"可能是有意保留"处理，未删。

**验证**：`npm run build` 通过，入口 `index-Bh75sz4R.js`；对产物做字符串对拍 —— 11 条被删文案在 bundle 中全部查无（含「当前共」「支持多条记录」「界面不回显明文」「系统已有」等），4 条应保留文案（「创建后不可修改」「前往系统配置」「请求级开关优先于本配置」「需公开可读」）全部命中。**未做**：真机目视版面（删字后是否有留白/错位需刷新看）。

### §29.20 压缩策略接入：尾部保留方式 + 工具结果裁剪 + 超大输出落盘（2026-10-26）

读 AgentScope 源码发现两件事：

1. **`CompactionConfig.keepTokens` 是三态且默认 `-1`**（`ConversationCompactor.determineCutoffIndex` 只在 `keepTokens > 0` 时走 token 分支，否则走消息数；但 `CompactionMiddleware:247-259` 会先把 `-1` 换算成 `min(keepTokensMax, max(keepTokensMin, 预算 × ratio))` 再喂下去）。也就是说 **teapot 此前只配 `triggerMessages`/`keepMessages`，模型报了上下文窗口时"压缩保留轮数"根本不参与** —— 界面在说谎。
2. 框架还有两套**默认就开着、会改变模型所见内容**的机制，teapot 从没暴露过：`PruneConfig`（聚合裁剪老的工具输出，默认保护最近 40k tokens / 可裁总量过 20k 才动手 / 每条留 2k 字符预览）与 `ToolResultEvictionConfig`（单条超 80k 字符的输出写进沙箱工作区、上下文换成占位 + 提示 readFile；与压缩正交，一个管历史长度、一个管单条宽度）。

**接入方式**（`feature.compaction` 扩展，无 DDL；`t_agent.compaction_trigger/keep` 两列语义不变）：
- `keepMode`：`dynamic`（默认 = 现状不变）/ `messages`（映射 `keepTokens=0`，让 `compactionKeep` 真正生效）/ `tokens`（映射 `keepTokens=N`）。界面「尾部保留」三选一下拉，选"按条数"才出现"压缩保留轮数"、选"按 token"才出现"尾部 token 预算"，不再同时摆着两个只有一个起作用的数字框。
- `prune` / `eviction`：各一个开关。**只下发"与框架默认不同"的部分** —— 开 = 不下发该对象（跟随框架默认），关 = 下发 `{enabled:false}`（分别映射 `builder.prune(null)` 与 `builder.toolResultEviction(null)`，后者 null 即整体关闭）。阈值数值后端支持（`protectTokens`/`minimumTokens`/`maxOutputChars`/`maxResultChars`/`previewChars`）但界面先不摆，避免又铺一堆数字。
- 校验（`AgentFeature.validateCompaction`）：`keepMode` 枚举、`tokens` 模式必须给正预算、数值型阈值填了就必须为正。报错文案里枚举顺序写死（`Set.of` 的迭代顺序每次 JVM 都可能不同，不能直接进用户可见文本）。
- 回显：未配置 = `dynamic` + 两个开关都显示"开"，与框架默认一致；保存时 `keepMode=dynamic` 且无其他差异 → 整个命名空间删掉（存量 Agent 的 feature 不会因为打开过一次配置页就变胖）。

**验证**：`mvn -o -pl teapot-ai-core -am package -DskipTests` 通过；`npm run build` 通过，入口 `index-wpNYC5Al.js`。校验规则用一次性 main 直跑真类（classpath = core/common 的 target/classes + 从 app.jar 解出的 `BOOT-INF/lib/*`）13 例全过：历史形态（只 modelId）/dynamic/messages/tokens+正预算/裁剪关闭/阈值合法/空命名空间 → ACCEPT；tokens 缺预算、预算为 0、非法枚举、负阈值、`previewChars=0` → REJECT 且点名原因。跑完的探针与解包目录已删。**未做**：真机跑一轮长会话验证三种尾部保留与两个开关的实际上下文形态。

**风险**：选"按条数"时若单条工具结果极大，10 条也可能顶满窗口 —— 框架有 context overflow 兜底重试（`CompactionMiddleware:146-156` 会以 `triggerMessages(1)/keepMessages(1)/keepTokens(0)` 强制压一次再重试），但那是报错后补救，不是预防。关掉两个默认机制同理会让历史更容易涨到窗口上限。

### §29.21 触发口径按策略渲染：动态窗口预算 / 固定 token 阈值（2026-10-26 用户截图挑出）

§29.20 把**保留**侧做成了按方式渲染，**触发**侧却仍是一个常驻的「压缩触发轮数」数字框，用户当场挑出这个不对称。回框架核对语义后确认：**触发做不到与保留对称的三态**，因为两个轴的性质不同——

- `ConversationCompactor:283-294` 里条数与 token 是**两个并列触发器，先到先算**（各自 `>0` 才参与判断），不是互斥枚举；
- **token 触发关不掉**：`triggerTokens=0` 的语义是"按窗口动态算"而不是"不触发"（`CompactionMiddleware:243-246` 取 `min(阈值, 窗口−reserved)`，所以填得再大也被窗口封顶）；
- **能关的只有条数**：`triggerMessages=0` 即不按条数触发。

所以做成**两态口径 + 恒在的条数兜底**，而不是摆一个假的"仅按轮数"：

- `feature.compaction` 新增 `triggerMode`（`dynamic` 默认 / `tokens`）、`triggerTokens`（tokens 态必填正数）、`reserved`（dynamic 态留给摘要本身的缓冲，框架默认 20000）。`AgentBuilder.applyTriggerMode` 映射：tokens → `triggerTokens(N)`；dynamic → `triggerTokens(0)` + 显式填了才 `reserved(N)`；未配置 = 框架默认，行为与 §29.20 之后完全一致。
- `t_agent.compaction_trigger` 新增合法值 **0 = 不按条数触发**（-1 仍是整块关压缩，1–200 不变），`AgentService.validateCompaction` 相应放开，报错文案把三个区间都列出来。
- 界面：「触发口径」与「尾部保留」两个下拉并排；下一行**左格随口径**渲染"预缓冲 token"或"触发 token 阈值"、**右格随方式**渲染"压缩保留轮数"或"尾部 token 预算"（动态态两边都不摆数字，因为那时确实没有可填的数）；「触发轮数」单独一行恒在，tooltip 说明它是与 token 口径并列、先到先算的独立触发器。
- 顺带修掉截图里那个空白框：`compaction_trigger` 为 null 时（存量 Agent 从没存过）开关显示"开"但数字框空着，看着像"没限制"。现在 placeholder 直接写生效默认值（30），保存时的回落条件由 `<=0 → 30` 收紧为 `<0 → 30`，好让显式填的 0 能存进去；`compactionEnabled` 的回显判定同步由 `<=0` 改成 `<0`，否则 0 会被显示成"关压缩"。
- 下发仍然只写差异：`dynamic` 且未填 reserved → 不落 `triggerMode`；只有 `tokens` 才落 `triggerMode + triggerTokens`，避免存量 Agent 的 feature 白胖。

**验证**：`mvn -o clean package -DskipTests` 与 `npm run build` 通过；`AgentFeature.validateCompaction` 的用例覆盖 dynamic 缺省 / dynamic+reserved / tokens+正阈值 / tokens 缺阈值 / 阈值非正 / 非法枚举。**未做**：真机跑一轮验证四种口径组合下实际触发时机（要登录态 + 长会话）。

### §29.22 触发口径补第三个选项「按条数」（2026-10-26 用户看 v77 界面追问"条数没了？"）

§29.21 上线后用户挑出：「触发口径」下拉只有两项，而隔壁「尾部保留」有三项，看着像把条数弄丢了（条数框其实在下面一行恒在，被截图裁掉了）。给了两条路——改名叫「token 触发」以对齐选项范围，或者补第三个选项 —— 用户选后者，并要求 tooltip 写清"窗口预算仍会先触发"。

**这个选项的语义要说清楚，它不是互斥口径**：`messages` 表示"把「触发轮数」当主输入"，token 侧接线与 `dynamic` 完全相同（框架里 token 触发关不掉，见 §29.21），所以长会话里往往仍是窗口预算先到。选它时左格从"预缓冲 token"换成"触发轮数"，`reserved` 在该口径下**不再生效也被显式忽略**（`AgentBuilder.applyTriggerMode` 的 `messages` 分支只 `triggerTokens(0)`，不读 reserved），免得存量 feature 里留着的旧值静默影响触发。

- 界面：`compactionTrigger` 这一个 Form.Item 在 `messages` 口径下渲染进左格（`min=1`）、其他口径下渲染在下面那行（`min=0`，可填"不按条数"）——两处同名，靠口径互斥保证同时只挂一个。
- 闸门：`AgentService.assertMessagesTrigger` 在 create/update 两条路径上交叉校验 —— 口径为 `messages` 却把轮数填成 0（=不按条数触发）等于该口径下没有条数触发，直接拒；轮数为 null 不拦（运行时回落默认 30，仍有条数触发）。
- 下发：`messages` 只落 `{triggerMode:'messages'}` 一个键，不带 `reserved`/`triggerTokens`。

**验证**：`mvn -o clean package -DskipTests` 与 `npm run build` 通过；`AgentFeature.validateCompaction` 的枚举用例补 `messages` 转 ACCEPT、非法值报错文案含三个候选；`assertMessagesTrigger` 的拒判走真类一次性用例。**未做**：浏览器里逐口径切换看左格换字段（要登录态）。

### §29.23 feature 驱动中间件：单例 Bean + 配置外置 + 注册表（2026-10-26）

teapot 现有 13 个中间件全是「装配现场 new 一个、参数从 feature 现取」：每加一个可配中间件都要动三处 —— `AgentFeature` 加命名空间与校验、`AgentBuilder` 加挂载分支、界面加表单。本节把这条路径收敛成一套机制（落点 `core/middleware/feature/`，10 个文件）：

- **中间件本体是单例 Bean，不持 Agent 状态**。每个 Agent 的差异全部外置到 `t_agent.feature` 的 `middleware_<key>` 命名空间。`AgentFeature` 本来就「未知顶层命名空间原样保留（不拒不改）」，所以**无 DDL、无 AgentFeature 改动**：新增一个可配中间件 = 新增一个 Bean。
- **两层回调**。框架层是 `FeatureMiddlewareBinding`（每次装配一个实例，持有本次装配的 feature 快照，实现 `MiddlewareBase` 的 6 个钩子，`activePoints()`/`order()` 透传本体）；业务层是子类覆写的 `onXxx(config, agent, ctx, input, next)`，进来时 config 一定非 null。配置**首次用到才解析并记忆**（不触发的钩子不付解析代价）。
- **启动 fail-fast / 运行 fail-open**。注册表构造时逐个内省，类名不以 `Middleware` 结尾、key 重复、`description()` 空白、泛型参数缺失（含被 CGLIB 代理掉）、字段缺 `@ConfigField`、类型不支持、`options` 非枚举常量、`defaultValue` 解析不了 —— 一律抛 `IllegalStateException` 让应用起不来。运行期解析失败只记警告并**原样放行**（`next.apply(input)`），不让一个中间件的配置问题拖死整轮对话。
- **schema 与解析同源**。`@ConfigField(label/description/required/defaultValue/options)` 一份声明既产出前端 schema（`ConfigSchema`/`ConfigFieldSchema`）又产出解析规则（`ConfigModel` 带反射句柄，不外泄）。类型映射：String→STRING，int/long→INTEGER，double/float→NUMBER，boolean→BOOLEAN，枚举→ENUM（options 自动取常量），`Map<String,String>`→STRING_MAP，带 `options` 的 String→ENUM，其余类型直接失败不静默降级；配置类是 `List<T>` 时 schema 标 `array=true` 并内省 T。
- **可见性与装配解耦**。`forFrontendDisplay()=false` → 不进 schema 出参但照常装配；`appliedAgents()` 非空 → 只对这些 agentKey 出现在 schema/配置查询里，装配仍以 `accept()` 为准（默认口径 = 命名空间存在且非空）。
- **接口**：`GET /api/agent/middleware-schema?agentKey=`（admin/developer，viewer 无）→ key、featureKey、description、array、fields、该 Agent 已存的 config 原样回显、以及 `configError`（存得下但跑不起来的配置要在界面上说出来）。
- **保存校验**接在 `AgentService.validateFeature` 末尾：feature 里出现且平台认识的 `middleware_*` 必须能解析，否则 `BizException("中间件 X 的配置非法：…")`。**没有对应 Bean 的命名空间不拒** —— Bean 下线不该让存量 Agent 存不了盘（与 teapot「未知命名空间原样保留」同口径）。
- **前端入口**：Agent 详情页新增 `Middleware` 分区（`teapot-ai-web/src/components/MiddlewareConfig.tsx`，三段导出：`seedMiddleware` 回显 / `applyMiddlewareFeature` 落库 / `MiddlewareConfigSection` 渲染）。**装配制**（见 §29.26）：一开始是空的，点「装配中间件」从平台可选项下拉里挑，挑中的才出现配置卡，卡片右上角可移除。一张卡 = key + `description()` + 按 `fields` 逐个渲染的控件（STRING→Input、INTEGER/NUMBER→InputNumber、BOOLEAN→Switch、ENUM→Select(options)、STRING_MAP→`key=value` 多行框，复用 MCP 那对 `mapToLines/linesToMap`）。`required` 进 `rules`，`defaultValue` 既进 placeholder 又作回显初值 —— 界面摆的值与后端解析到的值同源。没装配的中间件保存时从 feature 里删掉命名空间（后端据此判「未配置」），装配的只写非空字段；`array=true` 的既不给装配也只说明不编辑（存量数组配置保存时原样带走）；`configError` 用 Alert 直说「已存配置不会生效」并带原因。装配位是 React state 而非表单值 —— 它表达「要不要写这个命名空间」，不是配置字段，混进表单会多出一个假字段。

解析口径的几个确定语义（探针实测）：`{}` / `[]` / 空白字符串 = 未配置（不触发必填校验、不装配）；未声明字段一律拒（Jackson 默认语义，防拼错的键静默丢失）；`options` 对 String 字段是**硬约束**，补在解析侧（Jackson 不管 String 的取值范围，枚举字段才由 Jackson 兜住）；`defaultValue` 只回填「解析后为 null」的字段，**基本类型不回填**（`boolean enabled` 带 `defaultValue="true"` 仍是 false —— 要默认值就用包装类型）。

**验证**：`mvn -o -pl teapot-ai-core -am clean package -DskipTests` 通过（core 139 个源文件）；`npm run build`（`tsc -b` + vite）通过，入口 `index-CGeD_oci.js`。一次性 main 直跑真类 59 例全过：内省的类型映射/字段顺序/数组配置、11 类启动 fail-fast（含 key 重复与缺泛型）、accept 门控（未配置/空对象/已配置/存量无关命名空间）、桥接层派发与三种 fail-open（缺必填、未声明字段、非法类型）、默认值回填与基本类型不回填、保存校验的拒与放（含数组元素级）、schema 出参的三重门控与 configError。跑完探针与解包目录已删。前端版式另用一次性页面（`middleware-preview.html` + 与接口同形状的 fixture）挂**真** `MiddlewareConfigSection` 在 dev server 里实测：必填框出红字「问候语 必填」、开关开→字段出现 / 关→字段隐藏并出提示、`configError` 出告警、数组形态出说明、以及保存映射（`runtime` 与数组命名空间原样保留、关掉的命名空间被删、存量非法字段被丢弃）。探针文件已删。

**踩坑**：`raw == long.class ? Long.valueOf(t) : Integer.valueOf(t)` 这种「两个包装类型混在一支」的三元表达式会触发数值提升，默认值统统变 `Long`，与 schema 声明的 INTEGER 对不上（探针第一跑就 FAIL 在 `expected=<2> actual=<2>`）。数值分支必须写成 if/yield。

**未做**：① 目前**没有任何生产中间件用这套机制**，它是为后续新增可配中间件铺的路（存量 13 个中间件不动，避免改在跑的东西），所以「登录态下从详情页点 Save 打到真实接口」这条整链路还没跑过 —— 界面无数据可配；② 桥接层不覆盖 `ToolProvidedMiddleware`/`ToolInstallingMiddleware`，即 feature 驱动的中间件暂时**不能自带工具**（要工具仍走 `AgentBuilder.buildToolMiddlewares` 那条手写路径）；③ 数组形态配置只有说明没有编辑器。

**风险**：单例 Bean 里写 Agent 级状态会跨 Agent 串号（机制要求状态只能来自 config 参数或 `RuntimeContext`）；Bean 被 AOP 代理后泛型参数解析不到，会在启动时直接失败（不会静默变成"无配置"）。

### §29.24 注释与文档不点名参考实现（2026-10-26 全站清理）

代码里散落着「复刻某某外部产品设计语言」「参照某某模板」这类注释：它们点名了别人家的产品，却没说清我们自己为什么这么写。按用户要求全仓清一遍（`teapot-ai-server/` 本就零命中）：

- **改掉的**：`index.css`（毛玻璃工具类、用户气泡、四角星图标、无边框选择器、呼吸光效 5 处）、`AppLayout.tsx`（框架布局 / 应用容器 / 分段导航 3 处）、`Agents.tsx`（列表头注释 + 标题行 + 底部操作 3 处）、`AgentDetail.tsx` 头注释、`aguiBridge.ts`、`sessionBridge.ts`、`Chat.tsx`、`main.tsx`、`breakpoints.ts`、`tailwind.config.js`、`package.json` 的 description。写法统一成「说清这件事本身」——例如把「（某产品：Start Chat / New Automation 风格）」改成「（对话 / 编辑 / 删除）」，而不是换个品牌名继续点名。
- **保留的**：真实标识符改不了也不该改 —— 图标组件名（`@agentscope-ai/icons` 导出的 `SparkXxxLine` 一批）、CSS 选择器（`.spark-modal-title-close` / `.spark-modal-title`，§29.9 那条弹窗修复规则靠它）、以及依赖包名（`@agentscope-ai/design`、`@agentscope-ai/chat`）。`breakpoints.ts` 里原先用产品的简称指代那条断点约束，改成包名 + 组件名的准确写法而不是删掉了事 —— 那是必须对齐的真实约束。
- **立规**：注释、javadoc、SPEC 里写技术理由，不写「参考 X」；确实依赖某个包就用包名。参考实现的名字只出现在提交说明与 PR 描述里（改动要提上游时另说）。

**验证**：全仓（排除 node_modules/target/dist）大小写不敏感扫描这两个被点名的产品名，前端源码与注释零命中（`teapot-ai-server/` 原本就零命中）；`spark` 的剩余命中逐条核对，全部是上述图标组件名、CSS 选择器或依赖包名。`npm run build` 通过。

### §29.25 三个 feature 驱动中间件：限速 / 动态提示词 / 模型回退（2026-10-26）

§29.23 那套机制的第一批使用者，也是「新增可配中间件只加一个 Bean」的第一次实证 —— **前端零改动**：Agent 详情页的 `Middleware` 分区自动长出三张卡，字段、默认值、必填、下拉候选全部由配置类内省出来。落点 `core/middleware/feature/impl/`。

框架文档给了四种中间件形态（计费 / 限速 / 动态 system prompt / 模型回退），这里做后三种。**计费没做**：teapot 没有账单或配额落点，做成"只往日志里写一行 token 数"的中间件对用户没有可操作价值，等有计量去处再补。

| 中间件 | 命名空间 | 配置字段 | 挂点 / 顺序 |
| --- | --- | --- | --- |
| `RateLimitMiddleware` | `middleware_rateLimit` | `minIntervalMs`（INTEGER，默认 0 = 不限速） | `ON_MODEL_CALL` / 默认 1 |
| `DynamicPromptMiddleware` | `middleware_dynamicPrompt` | `withDate`（BOOLEAN，默认 true）、`datePattern`（默认 `yyyy-MM-dd HH:mm EEEE`）、`timeZone`（默认 `Asia/Shanghai`）、`appendText`（可选） | `ON_SYSTEM_PROMPT` |
| `ModelFallbackMiddleware` | `middleware_modelFallback` | `fallbackModelId`（必填）、`errorContains`（逗号分隔关键字，空 = 任何错误都回退）、`backoffMs`（默认 0） | `ON_MODEL_CALL` / **5** |

各自的语义边界：

- **限速按 agentKey 分桶**记在单例的 `ConcurrentHashMap` 里（键数量与 Agent 数同阶，不随会话增长），语义是"别把这一个 Agent 的请求堆到供应商脸上"，跨会话共享同一间隔。**认不出 Agent（拿不到 name）就不限速** —— 把无名流量挤进同一个"未知桶"会让互不相干的调用互相拖住（探针第一版就是这么暴露的）。等待用 `Mono.delay(...).thenMany(next)`，时刻在真正订阅下游时才落，等待期间到达的调用排到更后；是排队不是拒绝，所以间隔填得过大只会让对话变慢，不会报错。
- **动态提示词**在 Agent 自己的 `sysPrompt` 列**之外**追加（不动那一列，就不污染用户手写的那份）：`## 当前时间` + 可选 `## 补充说明`。时间格式与时区是用户手打的字符串，`DateTimeFormatter`/`ZoneId` 解析不了时**记警告并回落默认**（`yyyy-MM-dd HH:mm` / `Asia/Shanghai`），不让一个写歪的格式把整轮对话打死。`withDate` 用包装类型 `Boolean` 才吃得到默认值（§29.23：基本类型不回填）。
- **模型回退**只重试一次，回退那次再失败就原样抛给调用方；触发条件按**整条异常链文本的小写包含**匹配关键字（`429,throttling,timeout` 这类），不命中就不换模型 —— 免得把"参数非法"这种确定性错误拖成一次注定失败的重复调用。回退目标解析不出来（模型没登记 / 供应商没密钥）时**不掩盖原始错误**，照样抛原异常。`order()=5` 刻意比限速靠外，回退的那一次同样要过限速，不会因为是重试就绕过间隔。回退目标走系统模型入口（`resolve(id, null, null)`：不带 Agent 自带凭证、不下发思考档），所以**Agent 自带模型暂不能作为回退目标**。

**验证**：`mvn -o -pl teapot-ai-core -am package -DskipTests` 通过。一次性探针直跑真注册表 + 真桥接实例 48 例全过（假 `Model` + 覆写 `resolve` 的 `ModelRegistry` 替身 + 假 `Agent`）：三张 schema 的类型/默认值/必填、accept 门控（未配置/空对象/单个/三个全配）、保存校验三种拒与放、**限速真的等**（第二次调用实测 240ms、间隔 0 不等待、别的 Agent 0ms 不受影响、无 Agent 身份不排队）、动态提示词的拼接与"关掉时间注入"、非法格式/时区回落、回退的六条路径（命中换模型且成功、不命中只发一次且原样抛、大小写不敏感、目标解析失败不掩盖、backoff 在失败路径才等、配置非法则本次不生效）、`order()`/`activePoints()` 由本体决定。探针目录跑完已删。

**踩坑**：① 限速那条"新 Agent 首调不被拖住"的断言第一版假失败 —— 我把一次**合法该被限**的有名调用混进了同一个计时窗口（实测 198ms），计时断言要一次只测一件事；② 假 `Agent` 要补齐 `CallableAgent`/`StreamableAgent`/`ObservableAgent` 共 9 个抽象方法，编译器逐个报出来才凑齐。

**未做**：真机跑一轮带限速/回退的对话（要登录态改 Agent 配置）；`middleware_*` 三张卡的实际填写与保存回路（同上）。

### §29.26 Middleware 分区改装配制（2026-10-27 用户手机截图挑出）

v80 上线后用户看手机截图第一句就是「不应该是这样，应该一开始啥都没有，然后点击 + 之后下拉选取 middleware 再装配，现在这个 mw 一多很难看」。§29.25 那版把平台认识的每个中间件都摆成一张常驻卡（各自带启用开关），三个还行，十几个就是一条滚不完的设置清单 —— 而且"这个 Agent 用了哪些中间件"这件事反而看不出来。

改成**装配制**，与 Skill 绑定、自定义模型同一套交互：

- 分区顶部一个「装配中间件」下拉按钮（`Dropdown` + `menu.items`，同 `AddSkill` 的写法），只列**尚未装配**的可选项；已装配的从菜单里消失。
- 空态一句「尚未装配中间件」+ `Empty`，不再摆一排关着的卡。
- 装配出来的卡片右上角是移除按钮（`Popconfirm`「移除该中间件？保存后这个 Agent 不再装配它」），移除即从 feature 里删掉那个命名空间。
- 卡片里直接是配置项（默认值已回填进表单），不再有"开关开了才显字段"这一层 —— 装配本身就是那个开关。
- 数组形态的配置不给进装配菜单（界面填不了就不给入口，别装了再告诉用户改不了），但存量数组配置仍照常显示一张说明卡，保存时原样带走。
- 边界：装配后**所有字段都留空且该中间件没有默认值**时，落库会是空对象 = 后端判「未配置」，卡片会在下次进入时消失 —— 这种情况在卡底出一行「一项没填且没有默认值 —— 保存后这张卡不会留下」说清楚。当前三个都有默认值，走不到这条。

**验证**：`npm run build` 通过（入口 `index-BSlqTEJF.js`）。一次性页面挂真 `MiddlewareConfigSection` + 与接口同形状的 fixture 实测整条流程：初始只显两张已装配卡（含存量数组说明卡）→ 打开下拉只列 `rateLimit`/`modelFallback` → 装配 `rateLimit` 后卡片出现、下拉里不再出现它、保存映射多出 `middleware_rateLimit:{minIntervalMs:0}` 且 `runtime`/数组命名空间/另一张卡的配置都没被动 → 点移除出确认框 → 确认后卡片消失、`middleware_rateLimit` 从映射里去掉。探针文件已删。

**未做**：登录态下从真详情页装配一个中间件、保存、再进对话页看运行效果（要登录态）。

### §29.27 装配改弹窗：选中间件 → 渲染 schema → 填值 → 保存（2026-10-27 用户截图反馈）

§29.26 那版是「点按钮出下拉菜单，选完卡片就地出现、字段在卡片里填」。用户看手机截图说不对：**要弹窗** —— 点「装配中间件」开弹窗，弹窗里下拉选一个 middleware，选中后按它的 schema 渲染配置项，填完点弹窗的保存才进列表。

改成弹窗顺带解决了一个 §29.26 版实测出来的真 bug：把配置字段挂进页面那份表单后，**同一个 `mw.<key>.<field>` 会在弹窗与卡片两处同时挂载**，弹窗关闭（`destroyOnClose`）时那份字段被注销，刚填好的值直接丢（浏览器实测：填 `dashscope:qwen-plus` → 保存 → 卡片上输入框空、落库映射里只剩 `backoffMs:0`）。与 §29.22 那条"同名 Form.Item 不能同时挂两个"是同一个坑，只是这次是弹窗与列表卡撞在一起。

现在的形状（`components/MiddlewareConfig.tsx`）：

- 弹窗用**自己**的 `Form` 实例，配置值存在调用方（页面）的 `mwValues` state 里，页面表单不再掺中间件字段 —— 弹窗是唯一编辑入口，就不存在两处同名字段。
- 装配态弹窗顶部有「中间件」下拉（只列平台提供且本 Agent 未装的），选中后才渲染该 middleware 的字段；未选中时弹窗「保存」禁用。编辑态弹窗不显下拉（目标已定），字段值预填。
- 弹窗「保存」= 校验该 middleware 的必填 → 收进 `mwValues` →（装配时）把它放进列表 → 关窗。**真正落库仍是页面右上角的 Save**，弹窗不直接调接口（否则会和页面里其他未保存的分区改动打架）。必填没填时弹窗不关，红字标在对应字段上。
- 列表卡片改成**摘要**：一行一个字段 `标签 → 值`，未填的显示「默认 x」或「未填」，右上角「编辑」重开弹窗、垃圾桶移除（`Popconfirm`）。
- 数组形态配置依旧不给装配入口，存量数组卡只说明。

**验证**：`npx tsc -b` 与 `npm run build` 通过（入口 `index-BtHNNqqK.js`）。浏览器实测整条链路（一次性页面挂真组件 + 同形状 fixture，探针已删）：点按钮出弹窗且「保存」禁用 → 下拉只列未装配项 → 选中 `modelFallback` 渲染出三个字段（必填带 *）→ 不填直接保存被拦住且弹窗不关（`回退模型 必填`）→ 填 `dashscope:qwen-plus` 保存后弹窗关闭、卡片摘要显示该值、映射里出现 `middleware_modelFallback:{fallbackModelId:"dashscope:qwen-plus",backoffMs:0}` 且 `runtime`/数组命名空间没被动 → 「编辑」重开时标题是「编辑配置」、无下拉、值预填 → 改一个字段保存后摘要与映射同步 → 移除确认后卡片消失、该命名空间从映射里去掉。

**未做**：登录态下的真实保存与运行效果（同上几笔，要登录态）。

### §29.28 shell 工具卡改终端样式（2026-10-27 用户截图挑出）

对话里 `execute` 用的是聊天 SDK 的默认工具卡：Input 摆一整坨 `{"command": "..."}` JSON、Output 把工具输出原样铺出来，于是「Exit code: 0」这行既出现在正文里又带一个红色状态点，看着像报错。

改成 teapot 自己的渲染（`src/chat/ShellCard.tsx`，经 `Chat.tsx` 的 `customToolRenderConfig.execute` 挂载 —— 与计划卡/媒体卡同一个挂载点，**不需要后端改动**）：

- **命令行不再包 JSON**：从 `arguments` 里取 `command`，渲染成终端块里的 `$ <command>`（绿色提示符 + 等宽 + 深色底），头部保留一个复制按钮；`working_directory` 有值时在命令下方单列一行。
- **退出码提成状态徽标**：`exit 0` 绿、`exit N` 红、执行中灰，正文里那行 `Exit code: N` 剥掉 —— 红色状态点随默认卡一起消失。
- **输出块终端样式**：深色底、等宽、`pre-wrap`、最高 320px 内滚动（长输出不再撑爆气泡）。

实现上两个必须处理的现实（都来自 harness，不是前端臆造）：

1. 退出码是**工具输出文本的一部分**，且两条执行路径格式不同 —— 沙箱路径 `ShellExecuteTool` 是前导 `Exit code: N\n\n<output>`，本地路径 `LocalFilesystemWithShell` 只在非 0 时追加尾随 `<output>\n\nExit code: N`。正则两种都认，剥完再判空。
2. `arguments` 是**流式拼接中的 JSON 字符串**（与 §25 计划卡同一约束），所以取字段先试 `JSON.parse`，失败就按字符串字面量截取并反转义 —— 否则命令要等模型吐完才看得见，中途整张卡是空的。

**验证**：`npm run build` 通过（入口 `index-Cp3uE5gX.js`）。一次性页面挂**真** `ShellCard` 三种形态实测（探针已删）：① 沙箱格式成功 → `$ cd … && sed -n '60,70p' kami.md` + 输出块、徽标 `exit 0`（计算样式 `rgb(23,102,63)` 绿底）、正文不再出现 "Exit code"；② 本地格式非 0 → 徽标 `exit 1` 红、尾随退出码被剥、`工作目录 docs` 单列；③ 流式中（arguments 只有半截 `{"command":"grep -rn \"exit code\" src/`）→ 命令照样显示、徽标「执行中」灰、输出位显示「等待输出…」。终端块计算样式确认为深色底 `rgba(26,26,29,.92)` + Menlo + `pre-wrap`。

**未做**：真机跑一轮 shell 调用看新会话与历史回放两条链路（挂载点对两者一致，但没实测过历史会话的 `tool_call_output` 合并形态）。

### §29.29 execute 卡改用 spark 自带折叠壳（2026-10-27 用户截图挑出）

§29.28 那张卡是我自己拼的 `CardShell` + 两块终端 `pre`，结果**没有折叠**，一条 66 行的 shell 输出把整条对话撑开；用户要求「execute 也要支持收起，块样式用 spark 自带的（同 thinking 块），只有命令内容用终端样式」。

改成直接用聊天组件的 `Accordion`（thinking 块 `DeepThinking` 内部就是它，参数同款：`status` / `title` / `rightChildren` / `defaultOpen` / `bodyStyle.maxHeight` / `inline`）：

- **折叠**：`defaultOpen={loading}` —— 执行中展开（看得到命令在跑），跑完自动收起，点头部展开。
- **状态交给 spark**：`status` 取 `generating | error | finished`，退出码 0 → `finished`（组件自带绿色对勾 `spark-checkCircle-fill`）、非 0 → `error`（`spark-errorCircle-fill`）—— 也就是**连 §29.28 那个"exit 0 挂红点"的问题一起由状态机解决了**，不再靠我自绘图标。右侧仍留 `exit N` 小徽标 + 复制按钮。
- **只有命令用终端样式**：`$ <command>` 保持深色底 + Menlo；输出退回正文观感（浅色、等宽 `pre-wrap`，保留列对齐），不再两块都是黑底。
- 复制按钮 `stopPropagation`：头部点击是折叠开关，复制不能顺手把卡关掉。
- 一个类型约束：`Accordion` 的 `children` 签名是 `string | ReactElement`（不收多子节点/数组），所以命令 + 工作目录 + 输出要包一层容器再传进去。

**验证**：`npm run build` 通过（入口 `index-C1sRkpjs.js`）。一次性页面同时挂**真** `DeepThinking` 与三种形态的 `ShellCard`，按 DOM 类名与计算样式确认：4 个 `ant-accordion-group` 中 3 个默认 `...-close`（thinking + 两条已完成的 execute）、1 个 `...-open`（执行中那条）；点已收起卡片的头部 → 变 `open` 且命令块背景 `rgba(26,26,29,.92)`、字体 Menlo；点复制按钮后仍 `open`（不触发折叠）；`exit 0` 那张的状态图标是 `spark-checkCircle-fill ... icon-success`，`exit 1` 是 `spark-errorCircle-fill ... icon-error`。探针文件已删。

**未做**：真机跑一轮 shell 调用看新会话与历史回放（同 §29.28 那条未验项）。

### §29.30 execute 卡收起样式对齐 thinking，输入/输出拆成两个可滚动的框（2026-10-27 用户截图挑出）

§29.29 选错了壳：对话里的 thinking 块根本不是 `Accordion`，而是 `OperateCard`（`OperateCard/preset/Thinking`），默认工具卡 `ToolCall` 也是它。所以 Accordion 版的 execute 收起后与 thinking 并排时明显不是一套东西；同时 §29.29 把命令和输出塞进同一个 `bodyStyle.maxHeight` 容器且没给 `overflowY`，内容被裁死、**滚不动**。

改回 `OperateCard`（`src/chat/ShellCard.tsx`），壳与 thinking 完全同源：

- **收起样式 = thinking**：`header.icon` 执行中转圈 `SparkLoadingLine`、结束 `SparkToolLine`（thinking 是 `SparkMemoryLine`，默认工具卡同款），`title='execute'`，退出码徽标挂在 `header.description`（`exit 0` 绿 / `exit N` 红，执行中不挂）。实测 thinking / execute 成功 / execute 失败 / SDK 默认工具卡四张卡的 header 类名、高度 28px、`padding 0 12px`、`gap 8px`、字号 14px 逐项相同。
- **`body.defaultOpen={loading}`**：执行中展开、跑完收起，与默认工具卡口径一致。
- **输入/输出各一个框**：`OperateCard.LineBody` 里两个 `tool-call-block`（沿用组件自带样式类：圆角边框 + 32px 灰底框头 + 内容 `max-height` 内滚动），输入框框头右侧放工作目录 + 复制按钮，输出框限高放宽到 288px（输入框用组件默认 128px）。两个框各自 `overflow-y: auto`，互不影响；框头可点，**单独收起一个框**（框头右侧箭头只作指示，点击靠框头冒泡，避免双触发）。
- 复制按钮 `stopPropagation`：框头整条是收起开关，复制不能顺手把框折起来；复制成功图标换对勾 2s，失败 `message.error` 提示。
- 命令仍是终端样式（深色底 + Menlo + `$` 提示符），输出保持正文观感；高度与滚动交给外层 `block-content`，`pre` 自己不再设 `maxHeight`（否则两层滚动条）。

**验证**：`tsc -b` 与 `npm run build` 通过。一次性页面并排挂真 `Thinking`、真 SDK `ToolCall` 与三种形态的 `ShellCard`（探针已删）：四张卡 header 签名逐项相同；展开后 2 个 `tool-call-block`，输出框 `clientHeight 288 < scrollHeight 784` 且 `overflow-y auto`（能滚）；点输入框框头只收输入框、卡片与输出框不受影响；点复制按钮框不收起、`clipboard.writeText` 收到的是纯命令文本（无 `$`、无 JSON）、图标变对勾；正文不含 "Exit code"。无头 Edge 截图目视：收起行与 thinking 同款胶囊，展开后两个框与 SDK 默认工具卡的框同款。

**未做**：真机跑一轮 shell 调用看新会话与历史回放（同 §29.28 那条未验项）。

### §29.31 顶栏业务空间选择器改无边框（2026-10-27 用户截图）

顶栏右侧两个下拉里，Agent 选择器早就去了边框（`index.css` 的 `.teapot-agent-select`），业务空间切换器还带着 antd 的描边框，并排看像两套控件。把那条规则改成共享类名 `.teapot-borderless-select`（`border/box-shadow/background` 全部透明），`WorkspaceSwitcher` 顶栏那个 `Select` 与 `AgentSelector` 同时挂它 —— 弹窗里的「移动到空间」选择器仍是有框的表单控件，不受影响。

空间选择器是 `size="small"`（24px 高）而 Agent 是 `middle`（32px），实测两者文字都按 `alignItems: center` 落在同一条水平线上（文本框中心都是 28px、字号同为 14px），差别只在点击热区高度，所以不改尺寸。

**验证**：一次性页面并排挂两个真 `Select`（小/中各一）+ 探针已删，计算样式确认 `border 0px none`、`box-shadow none`、`background rgba(0,0,0,0)`；`npm run build` 通过（入口 `index-CUr-CCVa.js`），产物 CSS 里只剩 `teapot-borderless-select`，旧类名 `teapot-agent-select` 全库不再出现。

### §29.12 新建 Agent 改为进详情页草稿态，去掉列表弹窗（2026-10-24 用户截图反馈）

移动端「新建 Agent」弹窗（6 个字段 + 键盘弹起）几乎占满视口，且建完还得再进详情页配模型/提示词，等于同一批字段填两遍。改为**复用详情页当草稿**：

- 路由新增 `/agents/new`（`App.tsx`，显式列在 `/agents/:agentKey` 之前）；`AgentDetail.tsx` 导出 `NEW_AGENT_KEY='new'`，`isNew = agentKey === NEW_AGENT_KEY`。
- 草稿态：默认落在 `basic` 分区，胶囊菜单只留 Basic Info（其余分区依赖已存在的记录：头像上传、Skill 绑定、沙箱/渠道连接）；`load()` 直接短路，不发 `agentDetail` 与任何连接记录注册表请求。
- Basic Info 分区在草稿态把只读的 `Agent ID` 换成可编辑的 `Agent 标识`（沿用原弹窗的 `^[a-z][a-z0-9-]{1,63}$` 规则）并补一个「所属空间」下拉；空间值经 `toWorkspaceId()` 映射（`'personal'`/留空 = 不下发 `workspaceId`，个人空间仍是首个 Agent 创建时懒落库）。模型清单按所选空间取 `modelPresets`（与 §28.11 后端保存闸门同源），换空间即重取。
- Save 按钮在草稿态显示「创建」，`onSave` 走 `agentCreate`（`sysPrompt` 留空仍回落默认提示词），成功后 `navigate('/agents/{key}', { replace: true })` 无缝转成正常编辑态。
- `Agents.tsx` 删掉整个创建弹窗与 `onCreate`/`createOpen`/`models`/`form` 及随之无用的 `Form`/`agentCreate`/`modelPresets` 导入，按钮改为 `navigate('/agents/new')`；「移动到空间」弹窗不受影响。

**验证**（2026-10-24 v68/v68b 上线后实测）：
- 浏览器实开 `/agents/new`：菜单只剩 Basic Info 一项、页头「新建 Agent / 尚未创建」、按钮「创建」、Agent 标识可编辑且带原校验文案、所属空间默认个人空间。
- 草稿字段契约对后端往返验证：按页面同形状 `POST /api/agent/create`（带 `feature.runtime.topP/temperature` + `feature.storage`）→ `GET /api/agent/detail` 回显 `featureKeys=runtime,storage, topP=0.9, temp=0.3` → `DELETE` 清理。`AgentService.create` 确实走 `validateFeature(request.getFeature())`，草稿里的运行参数不会静默丢。
- 入口按钮 `navigate('/agents/new')` 与详情页草稿态为同一路径，列表页不再有任何创建表单状态。

**踩坑修正（v68b）**：首版在 `App.tsx` 里显式写了 `<Route path="/agents/new">` 静态路由，结果 `useParams()` 拿不到 `agentKey`（静态段不产参数）→ `isNew` 恒 false，页面退回 Profile 并去打 `/api/agent/detail/new`，弹「系统异常」。修法是删掉那条静态路由，由 `/agents/:agentKey` 承接字面量 `new` —— 草稿态判定本来就建立在 `agentKey === 'new'` 上。**教训**：路由与"参数即状态"的判定不要各写一半。

**遗留（非本次引入，待产品定）**：个人空间当前**建不出 Agent** —— `GET /api/model/presets?workspaceId=0` 返回空数组（`?workspaceId=1` 返回全量），而 `AgentService.create` 的模型闸门按 `workspaceId == null ? 0L : workspaceId` 判定，于是个人空间任何模型都判「未开放给当前空间」（实测报错：`模型 dashscope:qwen3.7-flash 未开放给当前空间`）。旧的新建弹窗同样是 `modelPresets(activeId ?? 0)`，所以这不是 §29.12 引入的回归，但它现在是创建的唯一入口，缺口更明显。可选修法：给桶 0 播种 `teapot_workspace_model`、把草稿默认空间改成第一个团队空间，或让个人空间不受桶 0 过滤。

### §29.13 a2ui 渲染后对话历史不再累积：框架侧 acting 停止路径漏写 tool_result（2026-10-24）

线上表现（用户手机截图）：一轮里 `a2ui_render` 成功后，该轮内容不进历史，后续对话也读不到。根因不在 teapot，也不在 a2ui 扩展，而在 **agentscope-core 主循环**：

- `ReActAgent` 的 acting 阶段若中间件发了 `RequestStopEvent`（a2ui 的 `A2uiRenderStopMiddleware` 在渲染成功后正是这么停本轮的，SPEC §29 系），旧代码直接 `return Mono.just(buildStopMsg(results, reason))`，**跳过了 `notifyPostActingHook`**；而 `state.contextMutable().add(resultMsg)` 只发生在该 hook 里（`ReActAgent.java:4194`）。
- 后果：持久化的 assistant 轮留下悬空 `tool_use`（无配对 `tool_result`），`saveAgentState` 回写 MySQL 后 `ChatSessionService.mergeSlotContexts` 读到的就是残缺轮 —— 与 §28.13 那次"历史空白"同源不同触发点。
- 修法：停止分支先按正常路径的口径把**已执行且非挂起**的结果逐条过 `notifyPostActingHook`（`returnDirect` 传 false），再执行原有的 `PERMISSION_ASKING` / `buildStopMsg` 逻辑。`buildStopMsg` 的返回内容不变，只补上下文写入。

**验证**（agentscope-java `a2ui` 分支）：新增回归测试 `ReActAgentMiddlewareIntegrationTest.actingStopStillPersistsExecutedToolResults`——中间件在 `onActing` 追加 `RequestStopEvent`，断言 context 里出现 `id=tool-call-1` 的 `ToolResultBlock`；改代码前实测 `expected: <1> but was: <0>`（钉死 bug），改后转绿。相关三类 `ReActAgentMiddlewareIntegrationTest`(10) + `ReActAgentHitlTest`(19) + `ReActAgentNewLoopE2ETest`(4) 全绿；core 全量 **2593 条，0 failures**，仅 `DangerousPathBypassTest` 2 条 error 是本机 Windows 无符号链接权限的既有环境噪声。
**teapot 侧无代码改动**：`mvn -o install` 刷新本地 `agentscope-core-2.1.0-SNAPSHOT`（20:13）后重打 fat jar（20:19，嵌套 core 构件 1,019,710B 与 .m2 同尺寸）即生效。

### §29.14 撤销「渲染后中断」：扩展不再依赖框架改动（2026-10-25 决策）

§29.13 的 core 修复上线后改判：**不做停轮**，而不是把 core 修复留在分支里。理由是停轮是 a2ui 扩展唯一依赖框架行为的地方——留着它，上游 PR 就从"新增一个可选扩展"变成"新增扩展 + 改 `ReActAgent` 主循环语义"，评审面和合并耦合都不划算（且两者必须成对合并，否则功能带 bug）。

- **SDK**：删 `A2uiRenderStopMiddleware`，使用约束的 system prompt 块搬进 `A2uiMiddleware.onSystemPrompt`，并补一条"渲染成功即收尾、不要用文字复述界面"承担原先由停轮保证的语义；`A2uiConfig.stopAfterPresent`（字段/getter/builder）删除；三条停轮测试删除，模块 29 条全绿。core 侧对应 revert（`27ea5c41` 撤销 `2753a601`）。
- **teapot**：`AgentFeature.A2ui.stopAfterPresent`、`AgentBuilder` 的装配与日志字段、`types.ts` 类型、Agent 详情页「渲染后中断」开关、对话页配置摘要里的同名 KV 全部移除。
- **兼容坑（必须留着）**：`AgentFeature` 的命名空间是用 `MAPPER.convertValue(raw, A2ui.class)` 绑定的，裸 `ObjectMapper` 默认 `FAIL_ON_UNKNOWN_PROPERTIES=true`；而 `t_agent.feature` 里存量行（digit-tim、teapot）已经写了 `"stopAfterPresent":true`。直接删字段会让这些 Agent 在装配时抛异常 → `A2ui` 类加 `@JsonIgnoreProperties(ignoreUnknown = true)`，旧键读时忽略、下次保存自然消失。
- **代价**：渲染后多一次模型推理；"不复述 UI"从硬保证降级为提示词约束（实测模型仍会给一句简短结束语）。

**验证**（v69，2026-10-25 00:31 上线；回滚点 `app.jar.bak-20261025-003104` / `ui.bak-20261025-003104`）：构件闸门 `CORE_REVERTED_OK 1019499`（core 回到未修复尺寸）、`STOP_MW_GONE_OK`、`TEAPOT_STOP_FIELD_REMOVED_OK`、前端 `STOP_SWITCH_GONE_OK`，`Started in 8.9s`。digit-tim（feature 里仍带旧键）连跑两轮：第 1 轮历史 9 条含信封 1 条、模型正常给出结束语；第 2 轮 12 条、信封仍 1 条且能复述卡片第二个概念 → **不修 core、不停轮，历史也正常累积**，同时证明存量 feature 解析未被打断。

---

## §30 模型窗口与探活（2026-10-27，由线上 "Prepared model input exceeds budget" 排查引出）

线上会话 `35aab56a…`（digit-tim）从 12:01 起每问必挂：`INVALID_INPUT_ERROR / Prepared model input exceeds budget: estimated=64413, limit=64000`，且 estimated 逐次只涨十几个 token —— 会话被永久锁死。排查结论拆成三节，前两节已落地，第三节是框架侧收口方向。

### §30.1 上下文窗口进入口配置：不再靠"猜模型名"

`limit=64000` 不是任何配置值，是 `HarnessContextBuilder.inputLimit()` 在 `model.getContextWindowSize() <= 0` 时的硬编码兜底。窗口由 `ModelContextWindows.lookup()` 按**模型名前缀**查表得出，表里 DASHSCOPE 只有 `qwen3.7 / qwen-max / qwen-plus / …`，**没有 `qwen3.8-*`**（`qwen3.8-max` 也不以 `qwen-max` 开头）→ 返回 0 → 兜底 64000。同会话最后一次成功调用 provider 实测 `inputTokens=41296`，真实窗口 131072：即被一个假上限挡住。三个启用 Agent 里 digit-tim(qwen3.8-max) 与 general-assistant(qwen3.8-flash) 中招，teapot(qwen3.7-flash) 命中表项故无恙。

- **落库**：`t_model_entry.context_window_size INT NOT NULL DEFAULT 0`（`sql/V16__model_context_window.sql`，0 = 未配置，维持框架现状）。初值口径见 §30.6：取 DashScope **原生目录** `model_info.context_window` 的实测值（qwen3.8-27b / qwen-plus / qwen3.7-flash / qwen3.8-flash / qwen3.8-max = 1,000,000，qwen-max = 32,768；qwen-turbo 与 qwen3-vl-flash-* 目录查无此模型，保持 0）。本节首版曾误用 compatible-mode 的 `default_envs.max_tokens`，已在 §30.6 纠正。
- **下发链**：`ModelEntryDO.contextWindowSize` → mapper（`update` 用 `<if test="contextWindowSize != null">` 包住，表单不传即不清空；另有 `updateContextWindow` 单列语句给探活用）→ `ModelProviderStrategy.Spec` 新增末位字段 `int contextWindowSize` → 六个策略在 `builder.build()` 前统一 `if (spec.contextWindowSize() > 0) builder.contextWindowSize(...)`。`ModelRegistry.create` 是唯一装配点。
- **界面**：模型表单加「上下文窗口（tokens）」`InputNumber`，placeholder 写明留空的后果；列表加「上下文窗口」列（131072 → `128K`，未配置灰显）。
- **当时的已知缺口**：Agent 自带模型（`feature.customModels`）没有窗口字段，仍走框架查表 —— 已在 §30.6 补齐。`ModelContextWindows` 缺项属于上游问题，补表只能治一个模型名，不作为本项目的修法。

### §30.2 探活与供应商模型清单：`ModelVendorClient`

一次 GET 就能分清三件事：密钥对不对、模型在不在、窗口多大。刻意不走各家 SDK（DashScope SDK 至今没有清单类，各家 list 能力不同构），形状沿用「一家一份」的 `ModelProviderStrategy`，新增供应商在 `Vendor` 表加一行。

- **端点**（2026-10-27 实测）：dashscope `GET /compatible-mode/v1/models[/{model}]` —— 错误密钥 401 `invalid_api_key`、未知模型 404 `model_not_found`、详情 200 带 `extra_info.default_envs.max_tokens`；**清单端点不返回窗口**，所以探活必须打详情端点。openai / openai-official 走 `{base}/models[/{model}]`（兼容网关同形），anthropic `api.anthropic.com/v1/models`，gemini `generativelanguage.googleapis.com/v1beta/models`，ollama 只有 `GET {base}/api/tags` 且无鉴权 → 探活退化为「在清单里查名字」，窗口报 0。
- **口径**：`ProbeResult(ok, httpStatus, contextWindowSize, message)`。探活失败**不抛异常也不改库存**——"错在哪"正是要给界面看的内容；只有入口不存在才抛。`ok=true && contextWindowSize==0` 是合法结果（该供应商不给窗口），文案区分于失败。
- **回填**：`ModelService.probe(id)` 探到窗口且与库存不一致时，`updateContextWindow` 单列写入 + `modelRegistry.evict(modelId)` + 审计 `model.probe`，下一轮装配即生效。
- **接口**：`POST /api/model/{id}/probe`（admin）；`GET /api/model/vendor-models/{provider}` 由"仅 dashscope 硬编码"改为按 provider 分发，dashscope 保留原有 qwen 系文本模型过滤，清单缓存 10 分钟按「provider+生效网关」分槽。列表页操作列加「探活」按钮，成功/失败分别 `message.success/error`。
- **验证边界**：dashscope 三个端点与状态码为线上实测；openai / anthropic / gemini / ollama 的 URL 与窗口字段名按各家公开契约写，**未用真密钥验证**，接进来时以探活结果为准。

### §30.3 框架层收口（待办，不在本项目实现）

模型发现的正应在 agentscope-java：`CredentialBase.listModels()` 是已声明、零实现的钩子，返回的 `ModelCard` 记录本就带 `context_size` 字段（`agentscope-core/…/credential/`）。收口方向：各 model extension 实现 `listModels()` 与一个轻量 `probe()`，`t_model_entry.context_window_size` 改为从其填充、teapot 侧 `ModelVendorClient` 退化为薄调用。届时 §30.2 里"各家 HTTP 端点"的知识应一并上移到框架，避免应用层各自维护一份。

### §30.4 `agent_state` CAS 自冲突（框架侧修复，teapot 无改动）

同一次排查里，该会话每轮必报一条 `agent_state CAS conflict — OVERWRITE applied`（近 7 天 8 次 `PRE_CALL` 对 8 次冲突，100% 确定性，非竞态）。根因是 `ReActAgent` 有两条落盘轨各记各的版本：轮末 `saveLegacyState` 用 `CallExecution.loadedVersion`，而 teapot 的 `PerMessageCheckpointMiddleware` 走 `saveAgentState(ctx)` 用 `slotVersions`；checkpoint 写成功后只回写 `slotVersions`，轮末仍拿旧版本号做 CAS → 必然落空 → 走默认 `ConflictPolicy.OVERWRITE` 盲写。当前不丢消息（两个写者序列化同一个 `AgentState` 实例），**但乐观锁在该部署里等于失效**：真有两个 run 重叠跑同一会话时是 last-writer-wins 整轮互吞。

- **修法**（agentscope-java `agentscope-core`）：新增 `activeScopes`（slot → 活跃 `CallExecution`），把成功写入得到的新版本经 `publishSlotVersion(slot, version, written)` 同时投给 `slotVersions` 与活跃调用；**带 `live.state == written` 身份校验**，避免同槽并发的另一个调用借用别人的版本号，从而保住真冲突检测。`clearStateCache` 两处一并清理。
- **验证**：新增 `ReActAgentCheckpointVersionTest` 两条——① checkpoint + 轮末两次写全程无冲突、无盲写、版本从 0 连续递增（**改代码前实测 `expected: <0> but was: <1>`，钉死后转绿**）；② checkpoint 之后插入一个外部竞争写，`FAIL` 策略仍抛 `ConcurrentSessionModificationException` 且库里留下对方的状态（证明修复没有把真冲突吞掉）。core 全量 2594 条，仅 `DangerousPathBypassTest` 2 条 error 为本机 Windows 无符号链接权限的既有环境噪声。
- **teapot 侧**：无需改动；`PerMessageCheckpointMiddleware` 的类注释里「与轮末落盘共享版本缓存」这句假设正是错的，升级框架后才成立。

### §30.5 v86 上线记录（2026-10-27 16:04）

产物：`app.jar` 227,054,414B（前 227,043,185B）、前端入口 `index-CZlw17Eq.js`（md5 `5f8914fd…`）。回滚点 `app.jar.bak-20261027-160400` + `ui.bak-20261027-160400`；脚本 `deploy/tmp-deploy-v86-model-window-probe.sh`，驱动 `Temp/ssh114/deploy-v86.js` / `rerun-v86.js`。

**顺序约束（值得留着）**：DDL 必须先于换 jar 之后的重启。新 mapper 的 `<sql id="cols">` 里含 `context_window_size`，列不在就是所有模型查询 `BadSqlGrammar`；反过来旧 jar 不引用该列，所以列先加无害、且**不需要回滚 DDL**。脚本因此做成可重入：`/tmp/app.jar` 不在就只核对已就位尺寸，zip 不在就按 md5 沿用现有 ui。

闸门全绿：`DDL_OK configured_rows=6`（qwen3.8-27b=1048576，qwen-plus/3.7-flash/3.8-flash/3.8-max=131072，qwen-max=32768；qwen-turbo 与 qwen3-vl-flash 仍 0，等探活）、嵌套 `agentscope-core` 1,019,761B 且 `CORE_CAS_FIX_OK`/`CORE_ACTIVE_SCOPES_OK`（证明 §30.4 的修复真进了 fat jar，不只是源码）、`CONTEXT_WINDOW_WIRING_OK`（六家策略各自的 `contextWindowSize` 都在）、`PROBE_MAPPING_OK`/`PROBE_BACKFILL_OK`、`REGRESSION_GUARD_OK`；`Started in 12.045s`、`startup_error_lines=0`、`模型供应商已接入 6`、`/api/model/list` 与 `/api/model/probe` 均 401（路由在、鉴权门在）、UI 三处新文案在包内。

线上态：三个启用 Agent 的模型现在全部带窗口（digit-tim 与 general-assistant 走 qwen3.8-flash=131072）→ harness 上限从兜底 64000 变成约 112067，那条会话的 64413 估算量落在预算内。

**仍未确认**：① 该会话真发一轮消息后不再报 budget、且 `agent_state CAS conflict` 那条 WARN 消失——需要一次真实对话才算闭环；② 重启窗口里 6 行 `Failed to start channel 'discord-DC'` 是既有噪声（08:34/10:30/12:06/13:58 四次重启前就有，Discord 出口被重置），与本次无关。**两次构件门首跑失败都是脚本自身的门写错**（`ModelEntryDO` 漏了 `model/` 包段、`customCredentials` 误挂到 `ModelService` 而非 `AgentBuilder`），不是产物问题——构件门写错只会挡发布，不会放过问题，这个方向上的失败是安全的。

### §30.6 供应商报得出的，不让人手填（2026-10-27 用户提出）

v86/v87 把窗口做成了「入口可配 + 探活回填」，用户当场指出两点：能力位同样该从云厂商查，而且**既然查得到就不该再有手工框**。同时纠正了 §30.1 的口径错误（见下）。

- **口径纠正（重要，别再犯）**：`GET /compatible-mode/v1/models/{model}` 的 `extra_info.default_envs` 是**请求默认参数袋**（同袋有 `num_beams/seed/top_p/do_sample`），里面的 `max_tokens` 是默认**输出**上限，不是上下文窗口。铁证：qwen3.8-max 那里报 131072，而原生目录 `model_info.max_output_tokens` 恰为 131072、`model_info.context_window` 是 **1,000,000**。v86 按错值回填，把五个模型的窗口低估了 7.6 倍（qwen3.8-27b 也错：1048576=2²⁰ vs 真值十进制 1,000,000；只有 qwen-max 巧合对上）。改用原生 `GET /api/v1/models`：`model_info.context_window` 给窗口，`inference_metadata.request_modality` 给模态。V16 的回填段因此改成**按模型名无条件覆盖、可重复执行**，重跑一次即纠正存量。
- **原生目录的探测形状**：没有「按名取详情」端点（`/api/v1/models/{name}` 一律 404），用 `?name=<model>` 过滤代替；**未命中是 200 + 空列表而不是 404**，所以「模型不存在」要靠列表空判定。`qwen-turbo` 与 `qwen3-vl-flash-2026-01-22` 在目录里 `total:0` —— 这两个存量入口本身已失效，保持窗口 0。清单分页 `page_size=200`（500 会被拒），实测 total=359。
- **`supportsDiscovery(provider)` 是单一判据**：只有 DashScope 能报模态，故只有它享受自动同步。判据经 `/api/model/providers` 的 `modelDiscovery` 字段下发给前端，界面不另立名单。OpenAI 兼容网关不在此列，一半是清单不报模态，一半是它的 baseUrl 由用户自定——放开探活等于让服务端去访问任意地址（SSRF），因此 `probe-draft` 明确只服务可发现供应商。
- **保存期权威覆盖**：`ModelService.create/update` 对可发现供应商调 `syncFromVendor`——探活成功即以供应商值覆盖窗口与能力位（**空模态=供应商确认纯文本，能力位置空**，不是"留着不动"）；探活不成功则保留提交值并 WARN，不阻断保存，否则 qwen-turbo 这类目录查不到的存量入口连改名都做不到。
- **Agent 自带模型同款**（用户第 2 点）：`CustomModel.contextWindowSize` 新增并进 `ModelRegistry.Credential`（**窗口纳入 `fingerprint()`**，否则同 modelId 不同窗口会命中同一缓存槽），窗口定序 Agent 自带 &gt; 系统入口 &gt; 框架查表。配置页填完模型名即自动同步，展示为「上下文窗口 976K + 模态标签 + 重新同步」，手工框只在不可发现供应商下出现。
- **前端**：新增 `components/VendorModelMeta.tsx`（`useVendorModelMeta` + `VendorModelMetaNote`），模型入口与 Agent 配置两处共用；同步值仍写入 hidden `Form.Item`，提交形状不变。探活不成功时把供应商原文如实显示成红字，不静默退回手填。

**验证**（v88，2026-10-27 18:14 上线；回滚点 `app.jar.bak-20261027-181437` / `ui.bak-20261027-181437`）：上线前用**待上线的 jar 在服务器上直跑真类**验 `syncFromVendor` 四态，全部符合预期 —— `qwen3.8-flash -> caps=[image,video] window=1000000`（供应商权威）、`qwen-max -> caps=[] window=32768`（确认纯文本、清空能力位）、`qwen-turbo -> caps=[image] window=1`（探活失败、保留提交值）、`openai(gpt-5.2) -> caps=[image] window=7`（不可发现供应商、完全不动）。构件门 `WIRING_ALL_OK`（八处接线）+ `PRIOR_GATES_HOLD`（含反向门：`ModelVendorClient` 字节码里再出现 `compatible-mode` 即拒绝上线）；`Started in 10.001s`、`startup_error_lines=0`、四个路由均 401、前端入口 `index-DDN6zd_1.js` md5 对上。**未做**：浏览器里真机点一次同步（需 admin 登录）；`probe-draft` 的 HTTP 层未跑通，其逻辑与 `probe` 同源且已被真类覆盖。

**存量已补**：`qwen3.8-flash`、`qwen3.8-27b` 的 `capabilities` 按供应商实测值补为 `image,video`（此前为空，导致 digit-tim 换到 flash 后媒体模态守卫把图片当不支持）。六个在册入口的窗口现全部为原生目录实测值。

---

## §31 Tool Group：工具分组与模型自主装备（2026-10-28）

需求（用户原话）：支持 tool group，在 Agent 配置页新建分组，用拖拽或点击把工具归到不同组里。本节改动**尚未提交、尚未上线**。

### §31.1 框架语义与选型：META 作用域 + 初始不激活

探针实测（一次性探针，跑完即删）确认的几条口径，直接决定了配置形状：

- meta tool 叫 `reset_equipped_tools`，入参 `{to_activate: string[]}`（必填，enum = 当前 META 组名），**替换语义**（不是增量装备）。它的 parameters/description 每次调用重算，所以 `registerMetaTool()` 之后才创建的组照样出现在 enum 里 —— 这是「build 后建组」能成立的前提。
- 入组的工具随即退出默认工具表：`getToolSchemas(activeGroups)` 不含它，未激活时调用被框架拒掉。**没入组的工具不受影响**（框架里「无分组」= 始终可见，归在保留组 `basic`）。
- 组的激活状态是**会话级**的，且在 `ReActAgent` 构造时一次性播种进会话状态。

因此选型是 `ToolGroupScope.META` + `active=false`：组一律建成不激活，由模型按组说明自己决定装备哪几组。配置里**刻意不暴露 `active` 与 `scope`** —— 构造后再把 `active` 改成 true 永远播种不到会话状态，那种字段等于在界面上摆一个不生效的开关（前端组件的 javadoc 写明了这一点）。

### §31.2 存储与保存期校验

存 `feature.toolGroups: [{name, description?, tools[]}]`，走 `AgentFeature` 既有的命名空间机制（未知命名空间原样往返；空列表移除该命名空间；整个 feature 为空时 `toJson()` 返回 null，维持列为 NULL 的语义）。

`validateToolGroups()` 的规则：≤20 组；组名 `^[a-z][a-z0-9_-]{1,63}$`；不许用保留名 `basic`；组名唯一；说明 ≤500 字符；每组 ≥1 个工具（空组对模型没有意义）且 ≤60 个；工具名 `^[A-Za-z0-9_.-]{1,128}$`、组内不重复；**一个工具只能属于一个组**（框架的工具→组反向索引是一对一，重复归属时后写的覆盖先写的，界面上「两个组都有它」与运行时「只有一个组管得住它」会对不上，所以在保存期就拦掉）；`reset_equipped_tools` 与 `load_skill_through_path` 不许入组（藏掉 meta tool 等于分组再也打不开，是死锁；skill 查看器框架有意让它不分组，好让早于它存在的旧会话也能加载 skill）。

**不在保存期判**「组里的工具名是否真在注册表上」：那要装配一次才知道。装配期匹配不上的名字按框架口径跳过并告警 —— 一条拼错的配置不该让整轮对话装配失败。

### §31.3 工具目录：`GET /api/agent/tool-catalog`

`ToolCatalog.entries()` 是与 Agent 无关、只跟服务端依赖版本有关的一张候选清单。**不去构建一个真 Agent 再读它的 Toolkit**：装配会连 MCP（stdio 传输要拉起子进程）、可能创建沙箱，配置页不该付这个代价，而且新建态的 Agent 还没落库、根本没法装配。改成反射扫 `@Tool`（RUNTIME 保留，注解里的 name 就是注册名、description 就是模型看到的那句）+ 5 条无注解实现按公开常量补录（`plan_enter` / `plan_write` / `plan_exit` / `a2ui_render` / `ask_user_question`）。扫的类都是编译期引用的，SDK 改类名会编译失败，不像硬编码工具名字符串那样静默过时。

实测（拿真类跑，不是 fixture）：**41 条 = harness 26 / platform 6 / media 9**；按来源再按工具名排序、无重名、两个不可入组的工具没有泄漏进清单。

清单只作候选、不作校验，所以前端另留自由录入，用来覆盖 MCP 工具（工具名由 MCP 服务端自报、本平台不加工具名前缀，只有连上才知道叫什么 —— 口径见 §32.3）。

### §31.4 装配顺序（两处都不能挪）

- **build 前**：只要配了组就 `builder.enableMetaTool(true)`。框架在 build 里注册 meta tool，晚了就没有；而入组工具被隐藏后再没人能装备回来。
- **build 后、所有工具注册之后**：`applyToolGroups(agent, toolGroups)` 逐组 `createToolGroup(name, desc, false, META)`，再逐个 `addToolToGroup(name, tool)`。必须排在工具注册之后 —— `addToolToGroup` 要求组与工具都已在册，否则抛 `IllegalArgumentException`。建组失败跳过该组、入组失败跳过该工具，各自 warn；收尾一条 info 汇总 `组=created/total 入组工具=N`。

另外两条实测约束：`ToolFilter`（`runtime.allowedTools`）跑在 meta tool 注册**之前**，所以白名单挡不住 meta tool；`Toolkit.copy()` 会带上组，子 Agent 继承同一套分组。

### §31.5 前端：`components/ToolGroupsSection.tsx`

- **入口位置（2026-10-28 用户截图追加）**：不再是左侧独立分区，而是并进 **Tool & Advanced 的第二个 Tab**（`AgentDetail` 里 `Tabs items`，默认停在「压缩与运行参数」，左侧菜单与 `Section` 联合类型里的 `toolgroups` 一并删掉）。这里有个必须守住的实现选择：**要用带 `children` 的 Tabs，不能改成"自己按 state 条件渲染两块面板"** —— `onSave` 走 `form.validateFields()`，参数面板一卸载，`form` 实例就与任何表单元素脱钩，切到分组页再保存会静默丢字段。rc-tabs 会保留看过的面板，所以隐藏中的 Form 照样被校验和读取（实测停在分组页点保存，PUT 同时带 `runtime` 与 `toolGroups`）。
- 布局：左侧目录卡（搜索、「全部/未入组」切换、按来源分桶；chip 既可拖也可点，Dropdown 给「加入/移到/已在 X」；卡片本身是放置区 = 取消入组），右侧组卡（组名 + 计数 Tag + 编辑 + 删除 Popconfirm、说明行、空组警示、虚线放置区、可关闭可拖拽的工具 Tag、自由录入）。新建/编辑走 `ResponsiveModal`，组名即时校验。
- 拖拽用**原生 HTML5 drag events**（项目没有 DnD 依赖，不为此引入）；跨组件传递用 `useRef` 存 `{tool, from}`，`dataTransfer` 只作兜底。
- 状态不进 `Form`：与 middleware 分区同一路子（组件态 + 保存时写 feature）。保存前 `cleanToolGroups` 归一、`toolGroupsProblem` 逐条复刻后端规则，不合规直接 `message.error` 且**不发请求**；`feature` 从 `loadedFeature` 合并，避免一个分区覆盖另一个分区的命名空间。
- 一个工具只属于一个组在前端同样执行：`assign()` 先把目标组之外的同名成员摘掉。
- 目录加载失败不阻断本分区：降级成警示 Alert，自由录入照常可用（错误必须让用户看见，不静默吞掉）。

### §31.6 验证

- 后端：`mvn -o -B -pl teapot-ai-core -am -DskipTests compile` BUILD SUCCESS。一次性探针 7 条全绿：跨组重复被拒且报错里指名先占用的组、两组合法配置通过、`reset_equipped_tools` 入组被拒、`basic` 被拒、空组被拒、清空分组后 `toJson()` 回到 null、序列化往返一致（组名/说明/工具顺序都保持）。
- 前端：`npx tsc -b --force` exit 0；`npm run build` ✓ built in 1m 40s。
- 浏览器（mock 后端 9126 + vite dev 5173 + 内置浏览器 `evaluate_script`；页面处于 hidden，截图不可用，改用结构化断言）：播种已有配置、点击归组、chip 拖入组、组内 Tag 拖到另一组（= 移动）、Tag 拖回目录（= 取消入组）、放置区高亮、新建弹窗与组名校验、空组保存被拦（PUT 次数不增）、自由录入 MCP 名、不可入组工具被拒并弹原文案、搜索过滤、未入组切换、Tag 关闭、删除组 Popconfirm、组重命名，532px 窄屏无横向溢出。提交的 feature 形状与预期一致，`runtime` 命名空间未被覆盖。
- 验证脚手架（mock server、vite、临时目录、探针）已全部拆除删除。

### §31.7 遗留（已修，见 §32）

本节收尾时报的遗留问题是「前端写 `mcpServers`、后端 DTO 字段叫 `servers` → `getServers()` 恒为 null → Agent 级 MCP 从来没注册上过」。**这条诊断是错的**，真跑探针后确认它比想象的严重，修法与结论落在 §32。

---

## §32 `feature.mcp` 键名错位：不是「静默不生效」，是「一启用就把 Agent 打挂」（2026-10-28）

### §32.1 真实故障形态

前端 `types.ts`/`AgentDetail` 写的键是 `mcpServers`，后端 `AgentFeature.MCP` 的字段叫 `servers`。原以为后果是「读成 null、MCP 静默不生效」，探针实测出来的是另一回事：

- `AgentFeature.MCP` **没有** `@JsonIgnoreProperties`，而 `MAPPER` 是裸 `new ObjectMapper()`（Jackson 默认 `FAIL_ON_UNKNOWN_PROPERTIES=on`）⇒ `getMcp()` 里的 `convertValue` 对未知键 `mcpServers` 直接抛 `IllegalArgumentException: Unrecognized field "mcpServers" … (2 known properties: "servers", "enabled")`。
- 两个调用点都致命：保存期 `validateMcp()` 先调 `getMcp()` → **保存 500**（抛的不是 BizException，界面只能显示 Jackson 原文）；行一旦落库，装配期 `AgentBuilder` 每轮调 `feature.getMcp()` → **该 Agent 每一轮都装配不起来**。也就是说从 UI 打开 MCP 开关这条路从来走不通。
- 为什么一直没被发现：保存时关闭态只写 `{enabled:false}`（`AgentDetail.tsx` 的 `mcpOut.enabled ? mcpOut : { enabled: false }`），不带未知键，所以「没启用 MCP」的存量 Agent 一切正常。
- **这是同一个坑的第二回**：§29.14 撤销「渲染后中断」时踩过一次，当时记的原话是「裸 `ObjectMapper` 默认 `FAIL_ON_UNKNOWN_PROPERTIES=true`，直接删字段会让这些 Agent 在装配时抛异常」。那一轮的修法只给 `A2ui` 一个类加了注解，没有改成全局口径 —— `MCP` 就是漏掉的那一个，而且它连字段名从一开始就没跟前端对上。

### §32.2 修法：改后端键名，不动前端

两个方向里只有一个是零迁移的。`servers` 这个键**没有任何代码写过**（全库检索：`setMcp()` 除自身定义外无调用方），而 `mcpServers` 是前端一直在写、可能已经在库里的形状。所以改后端：

- 字段 `servers` → `mcpServers`（Lombok 派生 `getMcpServers()`），三处调用同步：`AgentBuilder.applyMcp`、`McpConfigTools.listMcpServers`、`AgentFeature.validateMcp`。
- **不加** `@JsonAlias("servers")`：为一个不可能存在的存量键留兼容分支，正是本次要清理的那类代码。旧键现在只会被当成「没配 server」，给出中文校验文案。
- 顺手补 `@JsonIgnoreProperties(ignoreUnknown = true)`：照 §29.14 已经记过的教训办 —— 这份 JSON 由前端写、由装配期读，而本项目又常态化只发前端（§29.30/§29.31 就是 UI-only 上线），键名漂移是会再发生的事。忽略未知键后漂移退化成「没配 server」，由 `validateMcp()` 拦住，而不是抛 Jackson 原文把 Agent 打挂。

### §32.3 顺带纠正 §31 里我自己写错的一条事实

框架侧确认：teapot 走 `McpServerRegistrar.register(toolkit, configs)`，`McpServerConfig.prefixToolNames` 默认 `false`，`Toolkit.ToolRegistration.mcpToolNamePrefix` 默认空串 ⇒ **MCP 工具按服务端自报的名字注册，不是 `mcp__{server}__{tool}`**。§31 里那句「MCP 工具名形如 mcp__server__tool」的注释与前端 placeholder 一并改掉（工具名正则本来就允许下划线，规则不动）。

### §32.4 修完之后要知道的运维后果

- MCP 现在**真的会连**：`enabled=true` 的 Agent 每次装配都会拉起/连接配置的 server。`McpServerConfig.required` 默认 `false` ⇒ 连不上只 WARN 并跳过，不会把整轮对话打挂。
- 不漏进程：`HarnessAgent.close()` 关的 `ownedMcpToolkit` 与 `getToolkit()` 是同一个实例（build 里两处传的都是 `agentToolkit`），Web 链路每轮重建 + 轮末 `SingleUseAgentCloseMiddleware` 回收。
- 但**每轮重连的代价是实打实的**：stdio server 每轮都要 spawn + initialize + listTools。`npx -y …` 这类每次还要走 npm 解析的命令，单轮额外延迟会很难看；要长驻复用请优先用 `record`（系统记录）或 http/sse 传输。
- **密钥口径不一致（未改，等你定）**：`AgentService.redactSecrets()` 只处理自定义模型 `apiKey` 与自带沙箱 `e2bApiKey`，`feature.mcp` 的 `env`/`headers` 是明文入库、明文回显给任何能看这个 Agent 的人。MCP 以前从来没生效过，这条通道形同虚设；修完之后 env 里放 token 会变成常态，建议按 §29 的做法补 AES-GCM 加密 + `hasXxx` 回显。

### §32.5 验证

- `mvn -o -B -pl teapot-ai-core -am -DskipTests compile` BUILD SUCCESS；`npx tsc -b --force` exit 0。
- 一次性探针（跑完即删）覆盖 7 个形状：`mcpServers` 读得出且 validate 通过、内联 stdio 通过、`{enabled:false}` 通过、启用但零条 server → 中文 BizException、**旧键 `servers` 现在也只给中文 BizException（修前是 Jackson 抛错）**、stdio 缺 command 被拦、`setMcp()` 往返写出的键是 `mcpServers` 且回读一致。
- **未做**：线上库里是否已有带 `mcp` 命名空间的存量行 —— 本机到 114 的 22 端口这次不通，没查成。这决定「上线后是否立刻有 Agent 开始连 MCP」，发布前建议先跑一次 `select agent_key, json_extract(feature,'$.mcp.enabled') from t_agent where feature like '%"mcp"%'`。

---

## §33 Skill 的「绑定」与「生效」拆成两层（2026-10-28 用户截图提出）

需求原话：绑定关系要在接口层面传 agentId 来查这个 Agent 到底绑了多少 skill，同时卡片要有解绑按钮（与 disable 不同）。

动手前先摆清事实：改之前 `t_agent_skill` 只有 `(agent_key, skill_name)`，**那个开关切的就是 bind/unbind**，卡片上的 "Enabled/Disabled" 是错标 —— 界面上根本没有 disable 这一层。给用户看了两种口径（只做绑定/解绑并改正文案 vs 真做两层），用户选了**真做两层**。

### §33.1 语义定稿

- **绑定**（有这一行）= 这个 skill 属于该 Agent 的可装配集合；**解绑** = 删行。
- **`enabled=0`** = 行还在（配置关系保留），但装配时不进 `SkillFilter`，模型看不到它。用途是"暂时停用，但不想丢掉绑定配置"。
- 未绑定谈不上启用/停用 ⇒ 界面在未绑定时**不给开关**，只给「绑定」按钮（不摆无效控件）。

### §33.2 迁移与默认值

- `sql/V17__agent_skill_enabled.sql`：`ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1`，幂等写法沿用 V16（`INFORMATION_SCHEMA` 探针 + `PREPARE`，MySQL 8.4 无 `ADD COLUMN IF NOT EXISTS`）。**默认 1 是刻意的**：存量行全是"绑定且生效"，加列后行为与加列前逐位一致，不存在"上线即静默停用某个 Agent 的 skill"。
- `AgentSkillBind.enabled` 字段初始化为 `true`：insert 语句直接带这一列，将来漏设也不会静默把 skill 停掉。
- **顺序约束**（同 §30.5）：DDL 必须先于换 jar —— 新 mapper 的 select 列表含 `enabled`，列不在就是所有绑定查询 `BadSqlGrammar`；反向（旧 jar 遇到新列）无害，不需要回滚 DDL。

### §33.3 接口

- `GET /api/skill/list?agentKey=x` —— agentKey 可选。给了就在每条上多下发 `agentBound`（该 Agent 绑没绑）与 `agentEnabled`（这条绑定是否生效，未绑定时不下发）；没给则两者为 `null`，与 `false` 区分开（"没问" ≠ "问了且没有"）。绑定态与 `boundAgents` 同源，都出自同一次 `selectAllBinds()`，前端不再拿两份列表自己比对。
- `POST /api/agent/skillEnabled/{agentKey}`，body `{skillName, enabled}`。`updateEnabled` 影响 0 行 = 这条绑定不存在 → `BizException("Skill x 未绑定到该 Agent，请先绑定再启用/停用")`，**不悄悄补一行**。`enabled` 用 `Boolean` + `@NotNull`：漏传必须报错，不能靠默认值把 skill 停掉。
- 权限无需改动：`/api/agent/*` 已在 developer 通配内，`/api/skill/list` 对 viewer 只读放行，agentKey 只是查询维度、不扩权。
- `AgentDetailVO.skillNames` 仍是"全部绑定行"（含停用的），Profile 卡的「绑定技能」计数语义不变。

### §33.4 装配

`AgentBuilder` 的 `SkillFilter` 只收 `isEnabled()` 的绑定；存在"绑定但停用"时补一条 info 日志（`skill 已绑定但停用 agentKey= 停用= 生效=`），便于排查"为什么模型没有这个 skill"。

### §33.5 前端

- 三态文案：`已绑定 · 生效中`（绿）/ `已绑定 · 已停用`（`token.colorWarning`）/ `未绑定`（灰），状态点同色。
- 已绑定的卡片：启用开关（`checkedChildren=启用 / unCheckedChildren=停用`）+ 「解绑」text-danger 按钮带 Popconfirm，确认文案直接点出两层的区别："绑定行会被删掉；本体保留，其它 Agent 不受影响。只是暂时不用的话，选「停用」而不是解绑。"
- 分区头计数：`本 Agent 已绑定 N 个 · 其中 M 个已停用`，数字取服务端按 agentKey 下发的绑定态。
- 三个动作成功后就地 patch 那一条（连 `boundAgents` 一起改：它决定「删除本体」是否可用，漏改会让按钮在"刚解绑了最后一个 Agent"时仍禁着），不整页重拉。

### §33.6 一个已知副作用（未处理）

`AgentUpdateRequest.skillNames` 走 `replaceBindings`（删全部再插），**会把停用位重置回启用**。当前 SPA 从不提交该字段，界面路径不受影响；但直接用 update API 带 `skillNames` 的调用方需要知道这一点。

### §33.7 验证

- `mvn -o -B -pl teapot-ai-core -am -DskipTests compile` BUILD SUCCESS；`npx tsc -b --force` exit 0；`npm run build` ✓ built in 3m 40s。
- 浏览器（mock 9126 + vite 5173；532px 视口走移动端布局，分区菜单项是 `[title=Skills]` 的 div 而非 `li`）：三态渲染正确；点「绑定」→ 卡片转生效中、头计数 3→4；拨停用 → 卡片转已停用、停用计数 1→2、toast 带"绑定关系保留"；点「解绑」→ Popconfirm 文案如上、卡片转未绑定且只剩「绑定」、页脚转"未被任何 Agent 使用"并出现「删除本体」、头计数回到 3。mock 日志确认四次调用分别是 `bindSkill` / `skillEnabled=false` / `skillEnabled=true` / `unbindSkill` 且载荷正确。
- **未做**：真库执行 V17（要上线授权）；`setSkillEnabled` 的"未绑定" BizException 分支只做了代码级确认 —— 界面上未绑定不给开关，走不到那条路径。
- 验证脚手架（mock、vite、临时目录）已拆除，端口 9126/5173 均无残留监听。

---

## §34 v89 上线记录（2026-10-28 14:10，一笔带 §31/§32/§33）

产物：`app.jar` 227,072,504B（md5 `7b332c0e…`，上一版 v88 是 227,059,291B）、前端入口 `index-Ch_wmGfH.js`（md5 `c6951ed0…`、4,586,069B）。回滚点 `app.jar.bak-20261028-141033` + `ui.bak-20261028-141033`；脚本 `deploy/tmp-deploy-v89-skill-binding-mcp.sh`，驱动 `Temp/ssh114/run.js`（ssh2，口令只从 `~/.qoder-cn/secrets/ssh114.txt` 读）。

**顺序**：V17 DDL 先行（新 mapper 的 select 列表含 `enabled`，缺列即所有绑定查询 `BadSqlGrammar`），再换 jar，最后换 ui。DDL 幂等且不需要回滚。

闸门全绿：`V17_OK enabled_rows_1=4 enabled_rows_0=0` + 列形状 `tinyint(1) / 默认 1 / NOT NULL`；构件门 `MCP_FIELD_OK`（javap 看到 `List<…Server> mcpServers`）、`MCP_NO_LEGACY_ACCESSOR`（声明里再无 `getServers()`）、`MCP_IGNORE_UNKNOWN_OK`、`TOOLGROUP_WIRING_OK`（createToolGroup/enableMetaTool/addToolToGroup/validateToolGroups/tool-catalog/SOURCE_HARNESS 六处）、`MAPPER_XML_OK`+`INSERT_CARRIES_ENABLED`、v86~v88 的门不回退；`Started in 11.775s`、`startup_error_lines=0`（额外把 `Unrecognized field` 加进启动错误模式，正是 §32 那类炸法）、六个路由 401（含新 `/api/agent/skillEnabled/*`）、`BUNDLE_MD5_OK` + 新文案在包内 + 旧的 `"Enabled":"Disabled"` 三元式已消失。

**上线后真库盘点（把 §32.5 的未决问题结掉）**：`agents_with_mcp_namespace=0`、`agents_with_tool_groups=0`、`t_agent_skill` 4 行全部 `enabled=1`（digit-tim 2 / teapot 2）。也就是说 §32 的修复**没有让任何线上 Agent 开始连 MCP**，§31 的工具分组是"带着但没人配"的暗状态；§33 的迁移对存量逐位无损。

### §34.1 一个必须记住的 SQL 坑：`JSON_CONTAINS_PATH` 返回 0/1，不是 'YES'/'NO'

第一版盘点写成 `json_contains_path(feature,'one','$.mcp')='YES'`，跑出 `agents_with_mcp_namespace=4`（=所有 feature 非空的 Agent）。真相是 MySQL 里 `JSON_CONTAINS_PATH` 返回**整数** 0/1，而整数与字符串比较时 MySQL 把 `'YES'` 转成数字 **0** ⇒ `0='YES'` 恒真 ⇒ "没有这个路径"反而全部命中。同一条 SQL 把该路径当列选出来看却是 0，所以这个谎只在 WHERE 里出现，最容易放过。

- 判存在一律写 `=1`；判不存在写 `=0`，别写 `<>1`（NULL 行会被静默放掉）。
- 盘点类查询要有反向对照：`locate('mcp', feature)` 这种不依赖 JSON 语义的原始定位，一眼就能看出 4 是假的。脚本里的注释已钉住这条。

---

### §34.2 后续（2026-10-28，未上线）：新建页的 Agent ID 口径与后端对齐

复盘创建路径发现：后端从 M0 起就要求调用方自带 `agentKey`（`AgentCreateRequest` 的 `@Pattern ^[a-z][a-z0-9-]{2,31}$`，落库列 `t_agent.agent_key VARCHAR(32)`，SPEC §7.1 同一条），新建页也确实给了输入框 —— 但**规则是另一套**：`^[a-z][a-z0-9-]{1,63}$`（2–64 位，文案照抄成"2-64 位"）。后果是填 2 位或 33–64 位的 ID 时前端放行、后端拒绝，用户只看到一句来自服务端 toast 的陌生文案；标题还两处不一致（建页「Agent 标识」/ 详情页「Agent ID」）。

- 改法：前端规则提成模块常量 `AGENT_KEY_RE`，与后端 `@Pattern` 逐字一致（3–32），`Input` 加 `maxLength={32}`，label 统一「Agent ID」，extra 写明"全局唯一、也是 AG-UI 的路由键、创建后不可修改"。
- 口径来源写进常量注释而不是散在 JSX 里：这类"前端放宽一寸、用户多跑一趟"的规则必须能一眼看到它对谁负责。

**验证**（mock 9126 + vite 5173，逐条独立加载）：`ab`(2) 拒、`abc`(3) 过、32 位过、33 位拒；label/extra/`maxlength=32` 在 DOM 上确认；`tsc -b` exit 0。
**测试坑（值得留着）**：用原生 setter + `input` 事件给 antd 受控 `Input` 灌值时，**只有每次页面加载后的第一次会被 Form store 采纳**，后续赋值只改 DOM 不改 store —— 于是"合法值仍报错"是假阳性。每条用例都要重新 `navigate`；`press_key` 在这个隐藏页里也进不去（值不变）。另：原生 setter 绕过 `maxlength`，所以"输入被截断到 32"不能用这个方法验。

---

### §33.8 分区收窄成「只显示已绑定」+ 取用口搬家（2026-10-28 用户截图追加）

用户看着 §33 上线后的截图提了一句：`hactl`、`meeting-notes` 这两个本 Agent 没绑的条目不该出现在这里。收窄后有两个动作会失去入口，必须一起搬家，否则是删功能：

- **绑定库里已有的 skill**：`AddSkill` 过去只有 zip / Git 两条导入路径（导入即绑定），没有"取用现成条目"的口 —— 列表一收窄，`hactl` 这类就再也绑不上。所以 Add Skill 下拉加第三项「绑定已有 Skill」，弹窗只列 `agentBound !== true` 的候选（带搜索、来源、"N 个 Agent 在用"），点「绑定」即绑、**弹窗不关**（连着绑多个），父级 `load()` 后该行自动消失。
- **删除 skill 本体**：工坊早已整页下线（§29.10），本体删除只有这一屏的「删除本体」；而它的前提是 `boundAgents` 为空 —— 已绑定的条目必然含本 Agent，永远不满足。所以这个按钮从分区卡片搬进上述弹窗（仍仅 admin、git 来源不给）。
- 分区卡片随之简化：只剩「启用/停用」开关 + 「解绑」，状态文案不再需要"未绑定"分支；`boundSkills` 名称数组换成 `boundSkillItems` 对象数组（同源、少一次 map）。Profile 的「绑定技能」计数与分区头计数同用这一份。
- 空态文案跟着改口径：从"还没有可绑定的 Skill"改成"本 Agent 还没绑定任何 Skill：可以绑定已有的，或导入新的"—— 前者在收窄后会误导（库里明明有）。

**验证**（mock + vite，532px 移动端布局）：分区只列已绑定的 2 条、页面无"未绑定"字样；下拉三项；弹窗只列 2 个未绑定候选，`hactl`（别人在用）无删除入口、`meeting-notes`（孤儿）有；点 `hactl` 的「绑定」→ 弹窗内该行消失、分区变 3 张卡、头计数变 3；搜索 `meet` 只剩一条；「删除本体」的 Popconfirm 文案确认后条目消失；MobileModal 的关闭控件（`spark-modal-title-close`，不是桌面版的 `.ant-modal-close`）实测可关。`tsc -b` exit 0、`npm run build` ✓（入口 `index-Dox1jvta.js`）。**未做**：真库端到端（要上线）。

---

### §34.3 v90 上线记录（2026-10-28 16:30，仅前端）

带 §34.2（Agent ID 口径同步）与 §33.8（Skills 分区只显示已绑定 + 「绑定已有 Skill」弹窗）。入口 `index-Dox1jvta.js`（md5 `989543c3…`、4,587,939B），回滚点 `ui.bak-20261028-163028`，脚本 `deploy/tmp-deploy-v90-skill-list-ui.sh`。**后端 jar 未动**（仍是 14:09 的 v89 产物，`systemctl is-active` 通过、`/api/skill/list` 401）。

门的设计沿用 §30.5 那条顺序约束：**先在 `/tmp/ui-new` 里验完包再换目录** —— 入口 md5/尺寸、`index.html` 指向同一入口，全对才 `mv`。换完再核一次落盘 md5。文案门是双向的：新串必须在（`绑定已有 Skill`、`本 Agent 还没绑定任何 Skill`、`如 digit-tim`、`3-32 位`、`自己起一个全局唯一的`），被替换的旧串必须不在（`还没有可绑定的 Skill`、`2-64 位`、`Agent 标识`，以及 §33 的 `"Enabled":"Disabled"` 三元式）——反向门命中即拒绝，这类门写错只会挡发布，方向上是安全的。站点侧用域名回源（裸 IP 命中默认站点）：`index.html` 引用的入口与落盘一致、`/assets/<entry>` 200 且字节数等于 `ENTRY_SIZE`。

**备份清理（2026-10-28 16:35，用户要求"不需要备份了"）**：`/main/apps/teapot-ai` 下 `ui.bak-*` 27 个目录（291M）+ `app.jar.bak-*` 32 个文件（4.1G）全部删除，磁盘 73%→62%（可用 11G→15G）。删后复核在线产物完好（`app.jar` 227,072,504B、`ui/index.html` 指向 `index-Dox1jvta.js`）、站点与 `/api/skill/list` 应答如常、`teapot-ai` active。**自此没有 ui/jar 的历史回滚点**，再出问题只能靠重新构建上一版提交，因此发布脚本里的 `BAK`/`UIBAK` 步骤保留（下次发布仍会生成当次回滚点）。

- 未动的三类：`app.env.bak*` 四个（含配置与密钥，删它们的风险等级不同）、`app.jar.bak.20260818`（点号命名，不在 `app.jar.bak-*` 口径内，74M）、`dist.zip.bak-20260817`（3.5M）；`/main/backup` 数据库备份目录明确不在范围内。
- **两个自己的检查脚本缺陷值得记住**：① 用 `case "$f" in *app.jar.bak-*)` 判"越界名单"时，前导 `*` 可以匹配空串，于是合法文件名被判越界、脚本在删除前中止（挡发布方向的失败，安全但白跑）——判名单要用锚定正则 `^(ui\.bak-|app\.jar\.bak-)`。② `shopt -s nullglob` 之下，`ls -1 app.jar.bak-* | wc -l` 在删干净后会退化成**无参 `ls`**，把整个目录条目数当剩余数（报出 52）。删后计数用 `find . -maxdepth 1 -name '<glob>' | wc -l`。

---

## §35 Agent 批量移动空间（2026-10-28 用户截图提出）

需求：Agents 列表在「显示已停用」与 New Agent 之间加一个按钮，支持多选，把 Agent 批量移动到别的空间。

### §35.1 接口

`PUT /api/agent/batchMoveWorkspace`，body `{agentKeys:[], workspaceId}`（`workspaceId=null` = 移回个人空间，与单条移动同口径）。

- **整批一个事务**：`AgentService.moveWorkspaceBatch` 循环复用 `moveWorkspace`，任一条不通过（不存在 / 已停用 / 对源空间无成员权限）就全批回滚。刻意不做"尽力而为逐条移动"——界面无法向用户解释"移了一半"的状态，而失败原因逐条弹 toast 只会变成一屏噪音。
- 校验与审计仍逐条走 `moveWorkspace`，所以报错文案能指名是哪一条、为什么（实测 `Agent 已停用，不可移动：v68-draft-check`）。
- `@NotEmpty` 挡空选；`workspaceId` **不加** `@NotNull` —— null 是合法语义（个人空间）。
- 路由与 `PUT /{agentKey}/workspace` 不冲突（路径段数不同）；权限沿用 `/api/agent/*` 的 developer 通配，未改 resource-list。

### §35.2 前端

- 工具栏两态：常态「批量移动」+「New Agent」；进入选择态后整排换成「已选 N 个」+「移动到空间」（0 选时禁用）+「取消」。列表为空时「批量移动」直接禁用（没得选）。
- 选择态下卡片点击从"进详情"改成"切换勾选"；勾选框是 `pointer-events: none` 的 Checkbox，只当指示器（否则和卡片点击双触发），选中描边 `#5bb98b`；底部单卡操作排（对话/编辑/移动/删除）整排收起。
- **勾选只在当前页有效**：`load()` 一律清空 `selected` —— 翻页/搜索/换空间都会重载列表，留着旧勾选会让屏幕上的卡片和选中状态错位。
- 弹窗列出被选的 `name（agentKey）`（超 6 个折叠成"等 N 个"）+ 目标空间下拉 + 一句原子性说明。成功后 toast 带条数与空间名，退出选择态，并重拉列表与空间清单（顶栏的空间计数会跟着变）。

### §35.3 验证

`mvn compile` BUILD SUCCESS、`tsc -b` exit 0、`npm run build` ✓（入口 `index-DYKZo7pM.js`）。浏览器（mock + vite）实测：进入选择态 → 4 张卡出勾选框、操作排收起；勾 2 张 → 「已选 2 个」+ 两张描边 + 「移动到空间」解禁；弹窗列出两条与三个空间选项；选 Rising Sun 确认 → mock 收到 `BATCH ["general-assistant","digit-tim"] -> workspaceId=2`、toast「已移动 2 个 Agent 到「Rising Sun」」、退出选择态、弹窗 wrap `display:none`。**失败路径**：勾上已停用的 `v68-draft-check` 再提交 → 后端原文 toast、勾选保留（仍 2 个选中）、列表没被本地改坏。**未做**：真库端到端（要上线授权）。

---

### §35.4 v93 上线记录（2026-10-28 21:39，后端 + 前端，无 DDL）

一笔带 §35 批量移动与 §29.9 修订的弹窗钉位。产物：`app.jar` 227,074,425B（md5 `0fbc0538…`）、JS `index-VRdv8LtJ.js`（md5 `6c44904d…`）、CSS `index-Csk9ZuZv.css`（md5 `2364336d…`）。回滚点 `app.jar.bak-20261028-213929` / `ui.bak-20261028-213929`；脚本 `deploy/tmp-deploy-v93-batchmove-closepin.sh`。

闸门：`BATCH_MOVE_WIRING_OK`（路由 + 服务方法 + 请求 DTO 三处）、`MCP_FIELD_HOLD` 与 §31/§33 的门不回退、`Started in 10.108s`、`startup_error_lines=0`、五个路由 401（含新的 `PUT /api/agent/batchMoveWorkspace`）。**这次新增了两类门**：① CSS 也进 md5/尺寸核对（以往只 gate JS，改样式等于没门）；② 钉位门直接查压缩后的 CSS 里必须同时出现 `position:absolute!important` 与 `left:auto!important`、`inset-inline-start:auto!important` —— 缺任何一侧就是回到会被拉满整行的旧写法。站点侧另用域名回源核了 JS/CSS 的 200 与字节数。

---

## 附录 A：术语

- **HarnessAgent**：AgentScope 2.0 推荐的长时运行 agent 入口，整合 workspace / 记忆 / 持久化 / subagent / 沙箱。
- **AG-UI**：agent ↔ 前端 UI 的开放事件协议，经 SSE 传输，支持流式文本、工具调用、HITL 中断。
- **多模态块**：AgentScope 消息模型中的 ImageBlock/AudioBlock/VideoBlock，源为 URL 或 Base64+mediaType（§19）。
- **Skill**：`SKILL.md`（frontmatter + 指令）+ 可选 references/scripts 的打包能力单元；Agent 推理时按 name+description 决策加载。
- **Tool Group / meta tool**：把部分工具收进命名分组、默认对模型隐藏，由模型调 `reset_equipped_tools`（meta tool）按组名自主装备（§31）。
- **RuntimeContext**：每次调用的上下文（`userId`/`sessionId` 等），是无状态引擎多用户隔离的钥匙。
