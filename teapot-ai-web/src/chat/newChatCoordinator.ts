/**
 * 延迟建会话协调器（New Chat 懒创建）：
 *
 * 需求：New Chat 不立即创建后端会话（列表不出现空会话），发送第一条消息时才创建。
 * 模板自带的 ensureSession 懒创建存在时序竞争（undefined→newId 的会话切换 effect
 * 会 bump activeRequestId，导致在途请求被静默丢弃），因此不依赖它：
 *
 * 1. New Chat → changeCurrentSessionId(undefined) → 回到欢迎页；
 * 2. 提交时 sender.beforeSubmit 检查无会话 → 通知 SessionPanel（位于模板 context 内，
 *    可调用 createSession）先建会话；
 * 3. 等待模板 loader 冲刷完成后再放行提交 —— 此时 currentSessionId 已是新会话，
 *    模板按「已有会话」的正常路径发送，规避全部竞态。
 */

let currentSessionId: string | undefined;
let ensure: (() => Promise<void>) | undefined;

export const newChatCoordinator = {
  /** SessionPanel 每次会话变化时上报当前会话 id */
  reportSession(id: string | undefined) {
    currentSessionId = id;
  },
  /** SessionPanel 挂载时注册建会话能力（卸载时注销） */
  registerEnsure(fn: (() => Promise<void>) | undefined) {
    ensure = fn;
  },
  /** 提交前确保会话存在；已有会话或无注册能力（移动端）时直接放行 */
  async ensureSessionBeforeSubmit() {
    if (currentSessionId || !ensure) return;
    await ensure();
  },
};
