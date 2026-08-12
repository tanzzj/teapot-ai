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
- 不引入 K8s / Sandbox 容器化执行（`DockerFilesystemSpec` 沙箱留作二期，见 §15 演进路线）。
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
    run-timeout: 10m
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
  "skillNames": ["code-reviewer", "sql-helper"]
}
```

### 7.2 关键流程

1. 新增 Agent：校验 `agentKey` 唯一（`^[a-z][a-z0-9-]{2,31}$`）→ 写 `t_agent` + `t_agent_skill`
   → 在 workspace 根目录下生成该 agent 的 `AGENTS.md`（内容 = sysPrompt，作为 workspace 人设种子）。
2. 修改/删除：写库 → `AgentRegistry.invalidate(agentKey)`。
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

### 8.2 配置化 Skill 工坊（核心产品能力）

"通过配置建设一切 skill" = 用户**不写 SKILL.md 文件**，用表单 + 编辑器生产 skill：

| 表单字段 | 映射到 SKILL.md |
|----------|-----------------|
| 名称 `name` | frontmatter `name:`（全局唯一） |
| 触发描述 `description` | frontmatter `description:`（决定 Agent 何时加载该 skill，界面给出写作指引） |
| 指令正文 `instructions` | markdown body（Monaco 编辑器，支持变量提示） |
| 参考文档列表 `references[]` | `references/<文件名>` → `agentscope_skill_resources` |
| 脚本列表 `scripts[]` | `scripts/<文件名>` → `agentscope_skill_resources`（提示：脚本执行依赖 shell/sandbox，一期仅随 skill 分发，执行能力二期沙箱提供） |

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
| L2 市场（Marketplace） | **MysqlSkillRepository（平台主战场，配置化 CRUD）** |
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

1. 密钥零落盘：DB 密码 / JWT secret / 模型 API Key 仅通过环境变量注入。
2. BCrypt 存密；登录失败统一文案，不区分"用户不存在/密码错误"；登录增加失败计数锁定
   （5 次锁 10 分钟，内存计数一期即可）。
3. JWT 校验在过滤器链最前；`/agui/**` 与 `/api/**` 同等鉴权；`RuntimeContext.userId` 服务端强制。
4. CORS：仅允许前端来源（配置化白名单）。
5. 输入校验：`agentKey`/`skill name` 正则约束；skill 内容大小上限（SKILL.md ≤ 256KB，单资源 ≤ 1MB）。
6. 审计：Agent/Skill/User 的写操作记录操作日志表 `t_audit_log`（二期建表，一期先用应用日志）。
7. 二期强化项：Redis 黑名单吊销 token、OtelTracingMiddleware 观测、
   `enableSkillPromotionGate`（若开放 agent 自产 skill，**必须**配审核门，禁止 autoPromote）。

---

## 15. 里程碑与验收标准

| 阶段 | 内容 | 验收标准 |
|------|------|----------|
| **M0 基础设施** | 停原生 5.7 → Docker MySQL 8.4 LTS 建库建号、AgentScope-on-8.4 最小兼容性验证、备份脚本、nginx 站点、systemd 部署单元 | 两库可连；框架自动建表成功；`https://teapot.teamer.com.cn` 200；SSE 经 nginx 1 小时不断流 |
| **M1 登录-用户体系** | rbac 模块、JWT、用户管理 API + 登录页 | admin 登录→拿 token→访问受控 API；无 token 401；权限不足 403；老 rbac 语义用例全部通过 |
| **M2 Agent 运行时** | AgentRegistry + AG-UI starter + 对话台 | 新建 `general-assistant`→前端流式对话→重启进程后同会话续聊记忆保留 |
| **M3 Skill 平台** | Skill 工坊 CRUD + Agent 绑定 | 表单创建 `meeting-notes`→绑定 agent→下一轮对话 agent 的 `<available_skills>` 出现该 skill 并可被调用；删除后消失 |
| **M4 前端收口** | Agent 配置页、用户管理、权限路由守卫 | 三角色权限矩阵手工验收通过 |
| **M5 生产收口** | Dockerfile、部署脚本、监控日志、备份演练 | 服务器一键部署跑通；备份可恢复 |

二期路线（不在本 spec 范围）：沙箱执行（`DockerFilesystemSpec`）、Agent 自学习 skill 闭环、
Redis 状态存储/多副本、角色权限入库动态化、Plan Mode HITL 审批 UI、subagent 编排。

---

## 16. 风险与待确认项

| # | 事项 | 状态 |
|---|------|------|
| 1 | 服务器盘点（server-ops skill）：CentOS7 / 2C3.7G；磁盘已清理至 49%（可用 20G）/ 本机 MySQL 5.7.25 / JDK21 已装 | **已完成**，结论落 §11 |
| 2 | 本机 MySQL root 凭证未知（空密码被拒）；方案 A 改用 Docker 自建实例，root 密码自主生成，**此风险解除** | 已解除 |
| 3 | AgentScope mysql 扩展在 MySQL **8.4 LTS** 下的兼容性（官方示例基于 8.x 验证，风险低） | M0 最小验证；失败降级原生 5.7.25（方案 B） |
| 4 | ~~磁盘水位 88%~~ → 已清理至 49%（可用 20G）；workspace 增长、备份、日志仍需限额与滚动策略 | 已缓解，§11.6 持续观察 |
| 5 | 模型供应商：**已确认一期接入 DashScope + OpenAI**（`OPENAI_BASE_URL` 支持代理端点）；GLM/Kimi 二期 | 已确认，落 §6.4 |
| 6 | `t_agent_skill` 视图过滤方案依赖 AgentScope 2.0.1 实际 API 形状 | 实施阶段验证，降级方案见 §6.1 注 |
| 7 | skill 内 `scripts/` 一期只分发不执行（无 shell 沙箱），需在 UI 明确提示 | 已知取舍 |
| 8 | 老平台 `t_portal_user` 存量用户是否迁移到新平台 | 待确认（迁移脚本可后补） |
| 9 | 域名已改用 `teapot.teamer.com.cn`（老平台迁至 `old-teapot.teamer.com.cn`）；HTTPS 证书来源 | 待确认（一期可先 80 上线） |
| 10 | 停原生 MySQL 5.7 前需最终确认无残留业务依赖（datadir 仅 204M，老 teapot 指向远程库） | M0 首日核查 |
| 11 | Spark Design（`@agentscope-ai/design` / `chat`）npm 发布状态、版本稳定性及 AGUI 组件自定义鉴权头支持度 | M2 启动前验证；降级方案见 §12.1 注 |
| 12 | Spring Boot 4.x（Framework 7）破坏性变更较多，AgentScope agui starter 基于 SB 3.x 生态构建；一期选 3.5.x 兼顾新与稳 | 二期待 AgentScope 官方适配 SB 4.x 后升级（连带 MyBatis Starter 切 4.0.x） |

---

## 附录 A：术语

- **HarnessAgent**：AgentScope 2.0 推荐的长时运行 agent 入口，整合 workspace / 记忆 / 持久化 / subagent / 沙箱。
- **AG-UI**：agent ↔ 前端 UI 的开放事件协议，经 SSE 传输，支持流式文本、工具调用、HITL 中断。
- **Skill**：`SKILL.md`（frontmatter + 指令）+ 可选 references/scripts 的打包能力单元；Agent 推理时按 name+description 决策加载。
- **RuntimeContext**：每次调用的上下文（`userId`/`sessionId` 等），是无状态引擎多用户隔离的钥匙。
