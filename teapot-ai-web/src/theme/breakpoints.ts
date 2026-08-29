/**
 * 响应式断点单一常量源（SPEC-mobile-design-audit §2.1 / M5）：
 * - PHONE_BP 与 spark-chat ChatAnywhere isMobileHook 的 ahooks md 断点一致（<768 判手机）；
 * - NARROW_BP 与模板内置 narrowMode 断点一致（ahooks lg=992px，<992 模板不渲染内置左栏）；
 * - JS 侧判定统一用 `window.innerWidth < PHONE_BP`（即 ≤767），
 *   CSS 媒体查询统一用 `max-width: 767px`，两者同边界不打架。
 */
/** 手机断点：< 768px 为手机端（官方 md） */
export const PHONE_BP = 768;
/** 窄屏断点：< 992px 为窄屏（官方 lg，模板窄屏模式阈值） */
export const NARROW_BP = 992;
