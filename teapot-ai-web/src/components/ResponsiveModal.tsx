import type { ReactNode } from 'react';
import { Modal, MobileModal } from '@agentscope-ai/design';
import type { ModalProps } from '@agentscope-ai/design';
import { useIsPhone } from '../hooks/useIsPhone';

/**
 * 响应式弹窗（SPEC-mobile-design-audit M1/M2）：
 * - 手机端渲染 MobileModal：max-width 80vw、body 滚动锁、标题居右关闭按钮（规范自带，
 *   禁止再传 closeIcon）、底部按钮等宽；桌面端保持 Modal 原样。
 * - 手机端剔除调用方传入的 width / centered：MobileModal 宽度默认 auto，
 *   由 80vw 上限约束，避免桌面固定宽度在窄屏溢出。
 */
export function ResponsiveModal({ children, ...props }: ModalProps & { children?: ReactNode }) {
  const isPhone = useIsPhone();

  if (isPhone) {
    const { width, centered, ...mobileProps } = props;
    return <MobileModal {...mobileProps}>{children}</MobileModal>;
  }
  return <Modal {...props}>{children}</Modal>;
}
