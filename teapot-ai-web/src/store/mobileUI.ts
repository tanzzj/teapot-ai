import { create } from 'zustand';

/** 移动端 UI 状态（跨组件共享：AppLayout 顶栏 ↔ Chat 双态） */
interface MobileUIState {
  /** 移动端 Chat 双态：'home' 会话列表首页 / 'chat' 聊天界面 */
  mobileView: 'home' | 'chat';
  /** 当前会话标题（聊天态顶栏显示） */
  sessionTitle: string;
  setMobileView: (view: 'home' | 'chat') => void;
  setSessionTitle: (title: string) => void;
}

export const useMobileUIStore = create<MobileUIState>((set) => ({
  mobileView: 'home',
  sessionTitle: '',
  setMobileView: (view) => set({ mobileView: view }),
  setSessionTitle: (title) => set({ sessionTitle: title }),
}));
