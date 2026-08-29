/**
 * ask_user_question 中断/恢复状态机。
 *
 * 时序：
 * 1. 后端工具挂起 → RUN_FINISHED outcome={type:'interrupt'}，aguiBridge 解析后调
 *    registerInterrupts 登记全部未决中断（AguiResumeCoordinator 要求下一次请求的
 *    resume[] 精确覆盖所有未决中断，否则合约拒绝，故全量登记、全量回传）。
 * 2. 用户点击问题卡片选项 → answerInterrupt 把「被点中的」置 resolved（payload 带所选），
 *    其余置 cancelled，随后经程序化 submit 发送一条消息触发下一次 run。
 * 3. aguiBridge 组装请求时调 consumeResume：有 armed 答案则携带；若用户无视卡片
 *    直接打字发送，则把所有未决中断置 cancelled 兜底，规避合约冲突。
 * 4. 新 run 的 RUN_FINISHED 再次到达时 resetForRun 清空，重新登记。
 */

export type InterruptStatus = 'resolved' | 'cancelled';

export interface PendingInterrupt {
  interruptId: string;
  toolCallId: string;
  toolName: string;
}

export interface ResumeEntry {
  interruptId: string;
  status: InterruptStatus;
  payload?: Record<string, unknown>;
}

let pending: PendingInterrupt[] = [];
let armed: ResumeEntry[] = [];
/** 卡片点击后展示在气泡里的已选文本（toolCallId → 选项），供卡片渲染「已回答」态 */
const answeredSelection = new Map<string, string>();

let submitFn: ((query: string) => void) | null = null;

/** Chat.tsx 挂载：注册程序化提交入口（WebUI ref.input.submit） */
export function registerSubmit(fn: ((query: string) => void) | null) {
  submitFn = fn;
}

/** 新一轮 RUN_FINISHED 到达时清空上一轮状态 */
export function resetForRun() {
  pending = [];
  armed = [];
  answeredSelection.clear();
}

/** 登记未决中断（interruptId 去重） */
export function registerInterrupts(list: PendingInterrupt[]) {
  for (const item of list) {
    if (!item.interruptId || pending.some((p) => p.interruptId === item.interruptId)) continue;
    pending.push(item);
  }
}

export function hasPending(): boolean {
  return pending.length > 0;
}

/** 某个 toolCall 的中断是否仍未决（卡片据此渲染可点选/已回答态） */
export function isPendingToolCall(toolCallId: string): boolean {
  return pending.some((p) => p.toolCallId === toolCallId);
}

export function getAnsweredSelection(toolCallId: string): string | undefined {
  return answeredSelection.get(toolCallId);
}

/**
 * 用户点选某选项：被点中的中断 resolved（payload={selected}），其余 cancelled，
 * 随后以所选文本程序化提交，触发带 resume[] 的下一次 run。
 */
export function answerInterrupt(toolCallId: string, selected: string) {
  if (!pending.some((p) => p.toolCallId === toolCallId)) return;
  armed = pending.map((p) =>
    p.toolCallId === toolCallId
      ? { interruptId: p.interruptId, status: 'resolved' as const, payload: { selected } }
      : { interruptId: p.interruptId, status: 'cancelled' as const },
  );
  answeredSelection.set(toolCallId, selected);
  submitFn?.(selected);
}

/**
 * 组装请求时消费：返回需要携带的 resume[]（无未决中断返回 undefined）。
 * 消费即清空登记；用户未作答（直接打字）时全部置 cancelled。
 */
export function consumeResume(): ResumeEntry[] | undefined {
  if (pending.length === 0) return undefined;
  const entries: ResumeEntry[] = armed.length
    ? armed
    : pending.map((p) => ({ interruptId: p.interruptId, status: 'cancelled' as const }));
  pending = [];
  armed = [];
  return entries;
}
