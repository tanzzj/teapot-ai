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
  - `AgentAssembler.applySandbox` 改返 boolean；非沙箱（含降级）路径且路由工厂在场 → `builder.abstractFilesystem(...)`。
- **命名空间**：`teapot:memory:item:agents\0<agentKey>\0users\0<uid>\0<path>`（RedisStore 以 NUL 拼接命名空间）；uid 缺失回落 sessionId，对齐 IsolationScope.USER 降级语义。键前缀 `teapot:memory:`（`memory-key-prefix`）。
- **存量迁移**（`redis.migrate-legacy-memory=true` + `memory-store=true` 时，启动 `ApplicationRunner`）：`RedisMemoryFilesystems.migrateLegacyMemory(workspaceRoot)` 扫 `workspaceRoot/<agentKey>/<uid>/` 布局，把 `MEMORY.md` → `/MEMORY.md`、`memory/*` → `/<name>`（含 `.consolidation_state`），用运行时同款 `RemoteFilesystem.write`（create-if-absent）导入 Redis，**幂等可重跑**，磁盘文件保留作只读归档。生产已执行：`migrated=13 skipped=0`（teapot/admin 5 + digit-tim/discord 3 + digit-tim/admin 1 + general-assistant/anonymous 4），完成后已置回 `false`。
- **适用范围**：仅非沙箱 Agent（teapot）。**沙箱 Agent（general-assistant/digit-tim）无法路由**：2.0.1 沙箱模式文件系统固定为 `SandboxBackedFilesystem`，无任何注入点，记忆留在沙箱内（如实声明，升级 2.0.3 的 `filesystemRoute` 才可解）。
- **回切**：`TEAPOT_REDIS_MEMORY_STORE=false` 重启即回纯本地（但迁移后新写的记忆仅在 Redis、未回写磁盘，回切后这部分不可见；磁盘存量仍在）。
- **子代理**：2.0.1 父代理的 `abstractFilesystem` 会自动传给子代理（`HarnessAgentBuilderSupport` capturedBackend），子代理记忆同走 Redis。
- **冒烟**：teapot 对话（含 shell `echo` 执行✓）后：Redis 出现 `agents\0teapot\0users\0tmp-verify-0816b` 命名空间下 `/MEMORY.md`（consolidator 合并产物）、`/2026-08-28.md`（每日台账）、`/.consolidation_state`（watermark）✓，冒烟暗号写入并可在 Redis 值中命中✓；日志“记忆文件系统已路由到 Redis agentKey=teapot”✓。
- **迁移后复验**（Redis-only）：tmpverify 登录后，Agent 从 Redis 读回旧暗号 `teapot-redis-mem-ok`（OLD_MARKER_RECITED✓）、shell `echo` 正常、新暗号 `migrate-mem-ok-8888` 落入 `/2026-08-28.md`（MARKER_FOUND✓）；`users\admin` 命名空间下 5 个迁移键完整（`/MEMORY.md` ver=1 内容为存量服务器架构记忆）。
- **坑记录**：RedisStore 键含 NUL 字节，bash 变量/`redis-cli --scan` 管道会截断键名（显示为 `…:item:agents`），检查需用 Lua 脚本服务端内联或 `od` 直接读管道。

---

## 附录 A：术语

- **HarnessAgent**：AgentScope 2.0 推荐的长时运行 agent 入口，整合 workspace / 记忆 / 持久化 / subagent / 沙箱。
- **AG-UI**：agent ↔ 前端 UI 的开放事件协议，经 SSE 传输，支持流式文本、工具调用、HITL 中断。
- **多模态块**：AgentScope 消息模型中的 ImageBlock/AudioBlock/VideoBlock，源为 URL 或 Base64+mediaType（§19）。
- **Skill**：`SKILL.md`（frontmatter + 指令）+ 可选 references/scripts 的打包能力单元；Agent 推理时按 name+description 决策加载。
- **RuntimeContext**：每次调用的上下文（`userId`/`sessionId` 等），是无状态引擎多用户隔离的钥匙。
