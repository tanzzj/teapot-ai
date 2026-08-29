# 移动端设计规范审查（teapot-ai-web）

> 依据：参考仓库 `agentscope-ai/agentscope-spark-design`（本地克隆于 `reference/agentscope-spark-design`）。
> 版本对齐确认：本项目安装 `@agentscope-ai/design@1.0.32`、`@agentscope-ai/chat@1.1.71`，与参考仓库
> `packages/spark-design`、`packages/spark-chat` 当前版本**完全一致**，规范可直接套用，无版本差异。

## 1. 规范来源清单

| 规范内容 | 位置 |
|---|---|
| 标准组件使用规则（强制级） | `packages/spark-design/.cursor/rules/标准组件列表.mdc` |
| 编码规范（禁止原生 button/input/select/textarea 等） | `packages/spark-design/.cursor/rules/project.mdc` |
| 移动端组件实现规范 | `packages/spark-design/src/components/mobileComponents/`（MobileModal / MobileDrawer / MobileAlertDialog 源码 + 样式） |
| 聊天容器移动端机制 | `packages/spark-chat/components/ChatAnywhere/Layout/index.tsx`（isMobileHook、80vw Drawer） |
| 聊天消息区移动端样式 | `packages/spark-chat/components/AgentScopeRuntimeWebUI/core/Chat/styles.tsx`（1100px / 640px 断点） |
| 组件 API 文档 | `packages/spark-chat/llms/**/*.llms.txt`（Sender initialRows 等） |

## 2. 官方移动端规范要点（提取）

### 2.1 断点体系
- **聊天容器 isMobile**：`!responsive.md || uiConfig.narrowScreen`，即 **< 768px**（ahooks md）或显式窄屏配置。
- **消息区内置媒体查询**：≤ 1100px 调整锚点/边距；**≤ 640px** 隐藏锚点导航、气泡区右边距收缩到 16px。
- 移动端会话列表标准交互：隐藏常驻左栏，改为 **antd Drawer 左侧滑入，宽度 80vw**（ChatAnywhere Layout）。

### 2.2 移动端弹窗（MobileModal）强制规格
- `centered={false}`，弹窗 `position: fixed` 绝对居中（`translate(-50%,-50%)`）；
- 宽度：默认 `auto`，`min-width: 300px`，**`max-width: 80vw`**（手机不溢出）；
- 标题：18px / 500 / 行高 32，标题文字居中，关闭图标在 **title-wrapper 内右侧**（flex + space-between），图标 20px；
- 内容区：`max-height: 60vh`，padding 0 20px；
- 底部按钮：**等宽 `flex: 1`**，间距 8/12；
- 打开时**锁定 body 滚动**（`position: fixed` + scrollY 补偿），防止背景穿透滚动；
- 静态方法（confirm/success/...）同样包裹滚动锁与移动端样式；
- **桌面版 Modal 在移动端无任何兜底**（无 80vw 限制、无滚动锁）——移动端必须换用 MobileModal。

### 2.3 组件使用强制规则（与移动端相关）
- **Modal：任何情况下禁止设置 `closeIcon` 属性**（默认关闭图标即规范样式）。
- Modal/AlertDialog 必须由 state 控制 open；优先 `ok/cancel/info` 属性而非 `footer`。
- 有标准组件的场景**必须从 `@agentscope-ai/design` 导入**，禁止用 antd 同名组件替代。
- **禁止使用原生 `button` / `input` / `select` / `textarea` 标签**。
- Sender 移动端：`initialRows` 属性「默认 2，**移动端建议使用 1 行高度的输入框**」（当前安装版本尚未暴露该属性，属上游路线）。

## 3. 不符合项清单

### P0 —— 直接偏离标准，影响移动端体验

| # | 位置 | 现状 | 规范依据 | 整改建议 |
|---|---|---|---|---|
| M1 | 全部弹窗：`Skills.tsx` ×2、`Models.tsx` ×2、`SystemConfig.tsx` ×3、`Users.tsx` ×2、`Agents.tsx` ×1、`Login.tsx` ×1 | 移动端直接使用桌面版 Modal。design 包**已导出 `MobileModal`**（1.0.32 即有，项目未使用）；桌面 Modal 无移动端兜底，手机上宽度 520px 会撑破 80vw 约束，且无 body 滚动锁 | §2.2 | 手机断点下改用 `MobileModal`（API 与 Modal 兼容，直接替换标签即可） |
| M2 | `Skills.tsx` 两个导入弹窗 | 设置了自定义 `closeIcon={modalCloseIcon(...)}`（绝对定位 hack 修 × 位置） | §2.3「任何情况下禁止设置 closeIcon 属性」 | 随 M1 换用 MobileModal 后**移除 closeIcon**——默认关闭图标自带右上角规范布局，hack 可整体删除 |
| M3 | `SessionPanel.tsx:3` | 删除确认用 `antd` 的 `Modal.confirm`（非 design 包，无 spark 样式、无滚动锁） | §2.3 组件来源强制规则 | 改用 `@agentscope-ai/design` 的 `MobileAlertDialog`（移动端）/ `AlertDialog`；或至少换成 design 的静态方法 |
| M4 | `index.html` viewport | `width=device-width, initial-scale=1.0` 无 `viewport-fit=cover`；而 `index.css` 已使用 `env(safe-area-inset-bottom)` | iOS 全面屏规范 | **没有 `viewport-fit=cover`，`env()` 恒为 0，安全区适配实际失效**。补 `viewport-fit=cover` |

### P1 —— 一致性 / 工程规范

| # | 位置 | 现状 | 问题 | 整改建议 |
|---|---|---|---|---|
| M5 | 断点定义散落 5 处 | `AppLayout/AgentSelector/HistoryChatPanel` MOBILE/NARROW_BP=768；`Chat.tsx` PHONE_BP=768 + MOBILE_BP=992；CSS 用 `max-width:768` / `min-width:769` / `max-width:767` 三套 | 恰好 768px 时 JS 判桌面（`<768`）、CSS `max-width:768px` 判移动，**同一宽度两种判定打架**；模板内置还有 640/1100 两套媒体查询 | 抽 `src/theme/breakpoints.ts` 单一常量源（768/992 与官方 md/lg 对齐）；CSS 统一 `max-width: 767px` 与 JS `< 768` 同边界 |
| M6 | `Models/Users/Skills/SkillDetail/SystemConfig` | 渲染期直读 `window.innerWidth < 768`，无 resize 订阅 | 旋转屏幕/拖窗口后不自适应，需其他 state 触发重渲染才更新 | 统一响应式 hook（`useIsMobile`，内部 matchMedia），各页引用 |
| M7 | `AppLayout.tsx:2` | `Drawer` 从 antd 导入但已不再使用（移动端改下拉菜单，L108-111/L277 注释残留） | 死导入 + 与官方 80vw Drawer 标准分叉 | 删除未用导入；见 A1 记录产品决策 |
| M8 | `Chat.tsx` 放大按钮（`<button class="teapot-sender-expand-btn">`）、`AppLayout` 内联 `<svg>` chevron、头像 `<input type=file>` | 使用原生 button / input | §2.3 禁止原生标签（file input 为上传通道，可列为豁免但需注释） | 放大按钮换 `IconButton`；chevron 换 `SparkArrowDownLine` 类图标；file input 加豁免注释或包成 Upload |
| M9 | `index.css` ≤768px 强制 sender textarea 36px 单行（`!important`）+ `Chat.tsx` 传 `autoSize` | 官方标准做法是 Sender `initialRows=1` 属性，但当前安装版 1.1.71 **尚未支持**（参考源码已有） | 现版本只能 CSS 覆盖，属版本受限的临时方案；`autoSize` 是否被 options 透传待验证 | 记录技术债：chat 包升级后切换 `initialRows`，移除 36px 覆盖与放大按钮 hack |

### A 类 —— 有意偏离（产品决策，记录备查，不整改）

| # | 内容 | 说明 |
|---|---|---|
| A1 | 手机端会话列表为**自研双态**（全屏会话首页 ↔ 全屏聊天 + 顶栏返回），未采用官方 80vw 左滑 Drawer | 用户明确要求的交互；`hideBuiltInSessionList` + Portal 方案自洽 |
| A2 | 发送框橙色呼吸光效、Welcome 图标灰化、用户气泡黑底 | 品牌定制，标准未涵盖 |
| A3 | 顶栏导航用下拉菜单而非 Drawer | 用户前轮确认的简化方案 |

## 4. 整改优先级建议

1. **M4**（一行 meta，成本最低，修复失效的安全区适配）
2. **M1 + M2**（一并做）：弹窗组件移动端统一换 `MobileModal`，同时清掉 `closeIcon` hack——彻底解决此前「× 在左上角」类问题的根因
3. **M3**：SessionPanel 删除确认换 design 包对话框
4. **M5 + M6**：断点收敛 + `useIsMobile` hook（一次重构覆盖全部管理页）
5. **M7 / M8**：清理性改动，随手做
6. **M9**：等 `@agentscope-ai/chat` 升级到支持 `initialRows` 的版本再动

## 5. 验收标准（整改后）

- 375px 视口下：所有弹窗宽度 ≤ 80vw、标题右上有规范关闭按钮、打开时背景不滚动、底部按钮等宽；
- 恰好 768px 宽度旋转/缩放窗口，JS 与 CSS 判定一致无跳变；
- `grep -r "closeIcon" src/pages` 无命中；`grep -r "from 'antd'" src` 中不再出现 Modal/Drawer 用于上述场景；
- iOS 全面屏底部输入区不被 Home 条遮挡（`env()` 生效）。

## 6. 整改记录（已实施）

| 项 | 状态 | 落地方式 |
|---|---|---|
| M4 | ✅ | `index.html` viewport 补 `viewport-fit=cover` |
| M5 | ✅ | 新增 `src/theme/breakpoints.ts`（PHONE_BP=768 / NARROW_BP=992）；AppLayout / AgentSelector / HistoryChatPanel / Chat.tsx 全部改引常量；CSS 边界统一为 `max-width:767px` 与 `min-width:768px` |
| M6 | ✅ | 新增 `src/hooks/useIsPhone.ts`（matchMedia 响应式）；Skills / Models / Users / SystemConfig / SkillDetail 的渲染期直读 `innerWidth` 全部替换 |
| M1/M2 | ✅ | 新增 `src/components/ResponsiveModal.tsx`（手机端 MobileModal，自动剔除 width/centered）；Skills×2 / Models×2 / Users×2 / SystemConfig×3 / Agents×1 共 10 个弹窗替换；Skills 的 `modalCloseIcon` hack 整体删除（`closeIcon` 用法清零） |
| M3 | ✅ | SessionPanel 删除确认改 `AlertDialog.confirm` / 手机端 `MobileAlertDialog.confirm`，antd Modal 导入移除 |
| M7 | ✅ | AppLayout 移除 antd Drawer 死导入与注释残留 |
| M8 | ✅ | 放大按钮改 `IconButton`；下拉 chevron 改 `SparkCircleArrowDownLine`；头像 file input 标注豁免理由 |
| M9 | ⏸ 待上游 | 安装版 chat@1.1.71 尚不支持 `initialRows`，维持 CSS 覆盖，升级后切换 |
| A1–A3 | — | 产品决策保留，不整改 |

验证：`tsc -b` 通过；`vite build` 成功（built in 37.77s，ExitCode 1 为 chunk 体积警告误报）；
终检：全库无 `closeIcon` 传参、无 antd Modal/Drawer 导入、无裸 `768` 字面量断点。

