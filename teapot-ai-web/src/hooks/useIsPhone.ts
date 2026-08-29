import { useEffect, useState } from 'react';
import { PHONE_BP } from '../theme/breakpoints';

/**
 * 响应式手机端判定（SPEC-mobile-design-audit M6）：
 * matchMedia 订阅窗口尺寸变化，旋转屏幕 / 拖动窗口实时切换；
 * 边界与 JS 惯例 `< 768` 一致（max-width: 767px）。
 */
export function useIsPhone(): boolean {
  const [isPhone, setIsPhone] = useState(() => window.innerWidth < PHONE_BP);

  useEffect(() => {
    const mql = window.matchMedia(`(max-width: ${PHONE_BP - 1}px)`);
    const onChange = (e: MediaQueryListEvent) => setIsPhone(e.matches);
    setIsPhone(mql.matches);
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  }, []);

  return isPhone;
}
