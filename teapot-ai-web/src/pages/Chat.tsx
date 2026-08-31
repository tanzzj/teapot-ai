import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { createPortal } from 'react-dom';
import { useSearchParams } from 'react-router-dom';
import { Empty, message, Popover, Switch, Upload } from 'antd';
import { IconButton, Select } from '@agentscope-ai/design';
import { SparkAddFileLine, SparkEnlargeLine, SparkGuardrailLine, SparkLeftArrowLine, SparkMemoryLine, SparkPlusLine, SparkShrinkLine, SparkTextBoxLine } from '@agentscope-ai/icons';
import {
  AgentScopeRuntimeWebUI,
  useChatAnywhereInput,
  useChatAnywhereSessions,
  useChatAnywhereSessionsState,
} from '@agentscope-ai/chat';
import type { IAgentScopeRuntimeWebUIOptions, IAgentScopeRuntimeWebUIRef } from '@agentscope-ai/chat';
import type { SessionItem } from '../chat/sessionBridge';
import { agentList } from '../api/agent';
import { modelCapabilities } from '../api/model';
import { aguiResponseParser, createAguiFetch } from '../chat/aguiBridge';
import { AskUserCard } from '../chat/AskUserCard';
import { registerSubmit } from '../chat/askUserStore';
import { PlanEnterCard, PlanExitCard, PlanWriteCard, TodoWriteCard } from '../chat/PlanCards';
import MediaGenCard from '../chat/MediaGenCard';
import { createSessionBridge, revealHiddenSessions } from '../chat/sessionBridge';
import SessionPanel from '../chat/SessionPanel';
import AgentConfigPanel from '../chat/AgentConfigPanel';
import UserFooter from '../layout/UserFooter';
import { newChatCoordinator } from '../chat/newChatCoordinator';
import { useMobileUIStore } from '../store/mobileUI';
import { PHONE_BP, NARROW_BP } from '../theme/breakpoints';
import {
  ACCEPTED_IMAGE_MIME,
  ACCEPTED_VIDEO_MIME,
  MAX_IMAGE_BYTES,
  MAX_IMAGES_PER_MESSAGE,
  MAX_VIDEO_BYTES,
  imageCustomRequestFor,
} from '../chat/imageUpload';
import type { Agent } from '../types';

// 双断点设计（常量源：src/theme/breakpoints.ts，SPEC-mobile M5）：
// - PHONE_BP(768)：手机双态（全屏会话首页 ↔ 全屏聊天），与 AppLayout 断点一致；
// - NARROW_BP(992)：必须与模板内置 narrowMode 断点一致（ahooks useResponsive 的 lg=992px），
//   低于 992 时模板不渲染内置左栏（改渲染窄屏头部）。768–991 区间（平板 / 手机浏览器
//   桌面模式）由本页自渲染左栏槽位补齐双栏，经 ChatBridge Portal 填充。

/** 消息时间展示（气泡下方小字）：created_at 实时链路用户卡为秒、响应流为毫秒，统一归一 */
function msgTimeSlot(ts?: number, alignRight?: boolean) {
  if (!ts) return null;
  const d = new Date(ts > 1e12 ? ts : ts * 1000);
  if (Number.isNaN(d.getTime())) return null;
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const text = d.toDateString() === now.toDateString() ? hm : `${d.getMonth() + 1}/${d.getDate()} ${hm}`;
  return (
    <div
      style={{
        fontSize: 11,
        color: 'rgba(26, 26, 29, 0.35)',
        marginTop: 2,
        textAlign: alignRight ? 'right' : 'left',
      }}
    >
      {text}
    </div>
  );
}

/** 权限模式可选项（「+」配置弹层）：跟随配置 = 不覆盖，回落 Agent 管理页配置 */
const PERMISSION_OPTIONS: readonly { value: string; label: string }[] = [
  { value: '', label: '跟随配置' },
  { value: 'EXPLORE', label: '只读探索' },
  { value: 'BLOCK_DANGEROUS', label: '阻止危险命令' },
  { value: 'BYPASS', label: '全部放行' },
];

/**
 * 桥接组件：渲染在 ChatAnywhere Provider 内部（rightHeader 插槽，桌面/移动均常驻挂载）。
 * 1) 把模板当前的 sessionId getter 暴露给外层 fetch 闭包（作 AG-UI threadId）；
 * 2) 懒创建会话接线：上报当前会话、注册建会话能力；
 * 3) 揭示时机：AI 首条回复完成（loading true→false）后把隐藏会话放入列表；
 * 4) 移动端双态：首页态把 SessionPanel 全屏 Portal 到外层容器；
 *    聊天态在顶栏 Portal 返回箭头（回会话首页）。
 *    注意：Portal 必须从本组件（Provider 内部）发起——SessionPanel 依赖
 *    ChatAnywhere 的 React Context，若放到 Provider 外部渲染会拿不到会话状态。
 */
function ChatBridge(props: {
  register: (getter: () => string | undefined) => void;
  isPhone: boolean;
  isTablet: boolean;
  mobileHome: boolean;
  homeSlot: HTMLElement | null;
  tabletSlot: HTMLElement | null;
  slot: HTMLElement | null;
  onEnterChat: () => void;
  onBackHome: () => void;
  agentName: string;
}) {
  const { getCurrentSessionId, createSession } = useChatAnywhereSessions();
  const { sessions, currentSessionId, setSessions } = useChatAnywhereSessionsState();
  const loading = useChatAnywhereInput((v) => ({ loading: v.loading })).loading;
  const setSessionTitle = useMobileUIStore((v) => v.setSessionTitle);

  // 同步会话标题到全局 store（手机聊天态顶栏显示）。
  // 必须在 Provider 内部做：Chat 组件在 Provider 外调 useChatAnywhereSessionsState
  // 只能拿到 Context 默认值（sessions 恒空），标题会永远退化成 Agent 名称。
  useEffect(() => {
    if (props.isPhone && !props.mobileHome) {
      const list = (sessions || []) as SessionItem[];
      const session = list.find((s) => s.id === currentSessionId);
      setSessionTitle(session?.name?.trim() || props.agentName);
    }
  }, [props.isPhone, props.mobileHome, sessions, currentSessionId, props.agentName, setSessionTitle]);

  useEffect(() => {
    props.register(getCurrentSessionId);
  }, [props.register, getCurrentSessionId]);

  // 上报当前会话 id，供 beforeSubmit 判断是否需要懒创建
  useEffect(() => {
    newChatCoordinator.reportSession(currentSessionId);
  }, [currentSessionId]);

  // 注册懒创建能力：提交前无会话时先建会话，再等模板 loader 冲刷完成
  useEffect(() => {
    newChatCoordinator.registerEnsure(async () => {
      await createSession({ name: '' });
      await new Promise((r) => setTimeout(r, 250));
    });
    return () => newChatCoordinator.registerEnsure(undefined);
  }, [createSession]);

  // 懒创建会话的揭示：AI 首条回复完成（loading true→false）后才进列表
  const prevLoadingRef = useRef(loading);
  useEffect(() => {
    if (prevLoadingRef.current && !loading) {
      const revealed = revealHiddenSessions();
      if (revealed) setSessions(revealed);
    }
    prevLoadingRef.current = loading;
  }, [loading, setSessions]);

  // 揭示兜底：重新挂载（如切换 agent 后回来）时若仍有隐藏会话，直接展示
  useEffect(() => {
    const revealed = revealHiddenSessions();
    if (revealed) setSessions(revealed);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 手机首页：全屏会话面板（点会话/新建后由 onNavigate 进聊天态）
  if (props.isPhone && props.mobileHome && props.homeSlot) {
    return createPortal(
      <SessionPanel title="" onNavigate={props.onEnterChat} flat footer={<UserFooter compact />} />,
      props.homeSlot,
    );
  }
  // 平板 / 手机浏览器桌面模式（768–991）：模板处于窄屏模式不渲染左栏，
  // 由外层自渲染的左栏槽位补位，这里 Portal 填充（保持会话 Context 可达）
  if (props.isTablet && props.tabletSlot) {
    return createPortal(
      <SessionPanel title={props.agentName} footer={<UserFooter />} />,
      props.tabletSlot,
    );
  }
  // 手机聊天态：顶栏返回箭头回会话首页（会话切换就在首页列表里）
  if (props.isPhone && !props.mobileHome && props.slot) {
    return createPortal(
      <IconButton bordered={false} icon={<SparkLeftArrowLine />} onClick={props.onBackHome} />,
      props.slot,
    );
  }
  return null;
}

/**
 * 对话页（Spark Design chat template：AgentScopeRuntimeWebUI，SPEC §12.1）。
 * - 会话列表 / 消息气泡 / 工具调用 / 思考过程 / Welcome 屏 / 窄屏 Drawer 均由模板内置
 * - 后端协议经 aguiBridge（AG-UI ↔ Runtime SSE）与 sessionBridge（会话索引）适配
 */
export default function Chat() {
  const [searchParams, setSearchParams] = useSearchParams();
  const currentAgent = searchParams.get('agent') || '';

  const [agents, setAgents] = useState<Agent[]>([]);
  const [loadingAgents, setLoadingAgents] = useState(true);
  const [isPhone, setIsPhone] = useState(window.innerWidth < PHONE_BP);
  const [isTablet, setIsTablet] = useState(
    window.innerWidth >= PHONE_BP && window.innerWidth < NARROW_BP,
  );
  /** 手机双态：首页（全屏会话面板）↔ 聊天（chat pane 全屏）——同步到全局 store 供 AppLayout 顶栏使用 */
  const { mobileView, setMobileView } = useMobileUIStore();
  /** 手机首页容器（SessionPanel Portal 挂载点，由 ChatBridge 填充） */
  const [homeSlot, setHomeSlot] = useState<HTMLElement | null>(null);
  /** 平板 / 手机桌面模式（768–991）自渲染左栏槽位（模板窄屏无左栏，补位双栏） */
  const [tabletSlot, setTabletSlot] = useState<HTMLElement | null>(null);
  /** 顶栏返回入口 Portal 挂载点（AppLayout header 内的空 div） */
  const [historySlot, setHistorySlot] = useState<HTMLElement | null>(null);
  /** 启用模型的能力位 modelId → capabilities（SPEC §19 多模态 gating） */
  const [modelCaps, setModelCaps] = useState<Record<string, string>>({});
  /** 移动端输入框焦点（focus 后显示放大按钮）与放大态（单行 ↔ 多行） */
  const [senderFocused, setSenderFocused] = useState(false);
  const [senderExpanded, setSenderExpanded] = useState(false);
  /** 请求级记忆模式覆盖（SPEC §25）：'' = 跟随 Agent 配置；'on'/'off' 强制，按 Agent 持久化 */
  const [memoryOverride, setMemoryOverride] = useState('');
  /** 请求级计划模式覆盖（SPEC §25）：'' = 跟随 Agent 配置；'on'/'off' 强制，按 Agent 持久化 */
  const [planModeOverride, setPlanModeOverride] = useState('');
  /** 请求级权限模式覆盖：'' = 跟随 Agent 配置；EXPLORE/BLOCK_DANGEROUS/BYPASS 强制，按 Agent 持久化 */
  const [permissionOverride, setPermissionOverride] = useState('');
  /** 「+」配置弹层开关：记忆 / 计划 / 权限三项请求级配置收纳 */
  const [configOpen, setConfigOpen] = useState(false);

  /** 模板内部 sessionId 的 getter（由 ChatBridge 注入） */
  const sessionGetterRef = useRef<(() => string | undefined) | null>(null);
  const registerSessionGetter = useCallback((getter: () => string | undefined) => {
    sessionGetterRef.current = getter;
  }, []);

  /** WebUI ref：ask_user_question 卡片点选后经 input.submit 程序化提交触发恢复 */
  const webUIRef = useRef<IAgentScopeRuntimeWebUIRef>(null);
  useEffect(() => {
    registerSubmit((query) => webUIRef.current?.input.submit({ query }));
    return () => registerSubmit(null);
  }, []);

  useEffect(() => {
    const onResize = () => {
      setIsPhone(window.innerWidth < PHONE_BP);
      setIsTablet(window.innerWidth >= PHONE_BP && window.innerWidth < NARROW_BP);
    };
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  useEffect(() => {
    // 根据 mobileView 选择正确的 portal 挂载点（仅手机聊天态用 chat-slot）
    const slotId = isPhone && mobileView === 'chat' ? 'topbar-chat-slot' : 'topbar-history-slot';
    setHistorySlot(document.getElementById(slotId));
  }, [isPhone, mobileView]);

  // 加载可用 Agent 列表；未指定时优先 localStorage 上次选择，否则选第一个
  useEffect(() => {
    (async () => {
      try {
        const page = await agentList({ page: 1, size: 100 });
        const list = page.list || [];
        setAgents(list);
        if (!currentAgent && list.length > 0) {
          const lastAgent = localStorage.getItem('teapot:lastAgent') || '';
          const defaultKey =
            lastAgent && list.some((a) => a.agentKey === lastAgent) ? lastAgent : list[0].agentKey;
          setSearchParams({ agent: defaultKey }, { replace: true });
        }
      } finally {
        setLoadingAgents(false);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const activeAgent = useMemo(
    () => agents.find((a) => a.agentKey === currentAgent),
    [agents, currentAgent],
  );

  // 切换 Agent 时回读各自的记忆/计划模式选择（无记录 = 跟随 Agent 配置）
  useEffect(() => {
    setMemoryOverride(
      currentAgent ? localStorage.getItem(`teapot.memoryMode.${currentAgent}`) || '' : '',
    );
    setPlanModeOverride(
      currentAgent ? localStorage.getItem(`teapot.planMode.${currentAgent}`) || '' : '',
    );
    setPermissionOverride(
      currentAgent ? localStorage.getItem(`teapot.permissionMode.${currentAgent}`) || '' : '',
    );
  }, [currentAgent]);

  const onMemoryOverride = useCallback((v: string) => {
    setMemoryOverride(v);
    if (!currentAgent) return;
    if (v) localStorage.setItem(`teapot.memoryMode.${currentAgent}`, v);
    else localStorage.removeItem(`teapot.memoryMode.${currentAgent}`);
  }, [currentAgent]);

  const onPlanModeOverride = useCallback((v: string) => {
    setPlanModeOverride(v);
    if (!currentAgent) return;
    if (v) localStorage.setItem(`teapot.planMode.${currentAgent}`, v);
    else localStorage.removeItem(`teapot.planMode.${currentAgent}`);
  }, [currentAgent]);

  const onPermissionOverride = useCallback((v: string) => {
    setPermissionOverride(v);
    if (!currentAgent) return;
    if (v) localStorage.setItem(`teapot.permissionMode.${currentAgent}`, v);
    else localStorage.removeItem(`teapot.permissionMode.${currentAgent}`);
  }, [currentAgent]);

  /** 「+」配置入口：任一项被覆盖（非跟随配置）时按钮高亮提示 */
  const configOverridden = memoryOverride !== '' || planModeOverride !== '' || permissionOverride !== '';
  const openConfig = useCallback(() => setConfigOpen(true), []);
  const closeConfig = useCallback(() => setConfigOpen(false), []);
  /** 附件上传：隐藏 file input 的 ref */
  const fileInputRef = useRef<HTMLInputElement>(null);
  const triggerAttachment = useCallback(() => {
    fileInputRef.current?.click();
  }, []);

  // 移动端监听发送框 textarea 焦点：focus 后浮出放大按钮，失焦收起（放大态常驻）
  useEffect(() => {
    if (!isPhone) return;
    const isSenderTextarea = (el: EventTarget | null) =>
      el instanceof HTMLElement &&
      el.tagName === 'TEXTAREA' &&
      !!el.closest('.agentscope-runtime-webui-sender');
    const onFocusIn = (e: FocusEvent) => {
      if (isSenderTextarea(e.target)) setSenderFocused(true);
    };
    const onFocusOut = (e: FocusEvent) => {
      if (isSenderTextarea(e.target) && !isSenderTextarea(e.relatedTarget)) {
        setSenderFocused(false);
      }
    };
    document.addEventListener('focusin', onFocusIn);
    document.addEventListener('focusout', onFocusOut);
    return () => {
      document.removeEventListener('focusin', onFocusIn);
      document.removeEventListener('focusout', onFocusOut);
    };
  }, [isPhone]);

  // 拉取启用模型的能力位（失败不阻断，退化为纯文本）
  useEffect(() => {
    modelCapabilities()
      .then((list) => {
        const caps: Record<string, string> = {};
        for (const e of list) {
          caps[`${e.provider}:${e.modelName}`] = e.capabilities || '';
        }
        setModelCaps(caps);
      })
      .catch(() => {
        // 已统一提示
      });
  }, []);

  // 当前 Agent 的模型多模态能力（capabilities 含 image/video；无配置/拉取失败则隐藏入口）
  const mediaCaps = useMemo(
    () => (activeAgent ? (modelCaps[activeAgent.modelId] || '').split(',') : []),
    [activeAgent, modelCaps],
  );
  const imageCapable = mediaCaps.includes('image');
  const videoCapable = mediaCaps.includes('video');

  /** 处理文件选择：验证 + 上传 */
  const onFileSelected = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files || []);
    if (files.length === 0) return;
    // 验证
    for (const file of files) {
      if (file.type.startsWith('image/')) {
        if (!imageCapable || !ACCEPTED_IMAGE_MIME.includes(file.type)) {
          message.error('仅支持 JPEG/PNG/WebP/GIF 图片');
          return;
        }
        if (file.size > MAX_IMAGE_BYTES) {
          message.error('单张图片不超过 5MB');
          return;
        }
      } else if (file.type.startsWith('video/')) {
        if (!videoCapable || !ACCEPTED_VIDEO_MIME.includes(file.type)) {
          message.error('仅支持 MP4/WebM/MOV/MKV 视频');
          return;
        }
        if (file.size > MAX_VIDEO_BYTES) {
          message.error('单个视频不超过 30MB');
          return;
        }
      } else {
        message.error('仅支持图片或视频');
        return;
      }
    }
    // 上传（调用与模板相同的 customRequest）
    const customRequest = imageCustomRequestFor(currentAgent);
    for (const file of files) {
      await customRequest({
        file,
        onProgress: () => {},
        onSuccess: () => {},
        onError: (err: Error | string) => message.error(`上传失败：${typeof err === 'string' ? err : err.message || err}`),
      } as never);
    }
    // 清空 input 以便重复选择同一文件
    if (fileInputRef.current) fileInputRef.current.value = '';
  }, [currentAgent, imageCapable, videoCapable]);

  const options = useMemo<IAgentScopeRuntimeWebUIOptions | null>(() => {
    if (!currentAgent) return null;

    const rightHeader = (
      <ChatBridge
        register={registerSessionGetter}
        isPhone={isPhone}
        isTablet={isTablet}
        mobileHome={isPhone && mobileView === 'home'}
        homeSlot={homeSlot}
        tabletSlot={tabletSlot}
        slot={historySlot}
        onEnterChat={() => setMobileView('chat')}
        onBackHome={() => setMobileView('home')}
        agentName={activeAgent?.name || 'Teapot AI'}
      />
    );

    return {
      api: {
        fetch: createAguiFetch({
          agentKey: currentAgent,
          getSessionId: () => sessionGetterRef.current?.(),
          // 请求级记忆开关（SPEC §25）：经 forwardedProps.memoryMode 传后端，未选择则跟随 Agent 配置
          getMemoryMode: () => (memoryOverride === '' ? undefined : memoryOverride === 'on'),
          // 请求级计划开关（SPEC §25）：经 forwardedProps.planMode 传后端，未选择则跟随 Agent 配置
          getPlanMode: () => (planModeOverride === '' ? undefined : planModeOverride === 'on'),
          // 请求级权限开关：经 forwardedProps.permissionMode 传后端（优先级高于 Agent 配置）
          getPermissionMode: () => (permissionOverride === '' ? undefined : permissionOverride),
        }),
        // 模板实际按「每行 SSE data 字符串」调用（类型标注为 Response 是上游笔误）
        responseParser: aguiResponseParser as never,
      },
      // 计划模式自定义工具渲染（SPEC §25）：plan_write 计划卡片 / todo_write 进度清单，
      // 替代默认 ToolCall 折叠面板；数据全部来自既有 TOOL_CALL_* 事件流，无需后端改动
      customToolRenderConfig: {
        plan_enter: PlanEnterCard,
        plan_write: PlanWriteCard,
        plan_exit: PlanExitCard,
        todo_write: TodoWriteCard,
        ask_user_question: AskUserCard,
        // 媒体生成卡片（SPEC-media-gen 修订）：dashscope_* 工具结果中的 image/video/audio 块
        // 经 ImageGenerator / DefaultCards.Videos / Audios 可视化，替代默认折叠面板
        dashscope_text_to_image: MediaGenCard,
        dashscope_text_to_video: MediaGenCard,
        dashscope_image_to_video: MediaGenCard,
        dashscope_first_and_last_frame_image_to_video: MediaGenCard,
        dashscope_text_to_audio: MediaGenCard,
      },
      session: {
        multiple: true,
        api: createSessionBridge(currentAgent),
        // 窄屏（<992）隐藏模板内置会话列表（其头部带 "Runtime WebUI" 品牌）：
        // 手机端由全屏首页面板提供，平板/桌面模式由自渲染左栏提供；
        // 宽屏（≥992）模板原生左栏不受影响（leftHeader 插槽接管）
        hideBuiltInSessionList: isPhone || isTablet,
      },
      theme: {
        locale: 'cn',
        // Carbon 黑色主题（与全局 carbonTheme 一致）
        colorPrimary: '#1a1a1d',
        // leftHeader 插槽接管为自定义会话面板（可点击切换 + 时间展示），底部挂用户信息 + 系统配置入口
        leftHeader: <SessionPanel title={activeAgent?.name || 'Teapot AI'} footer={<UserFooter />} />,
        rightHeader,
      },
      welcome: {
        greeting: '你好，有什么可以帮你？',
        nick: activeAgent?.name || 'AI 助手',
        description: activeAgent?.description || '基于 AgentScope 的智能体，支持工具调用与多轮对话。',
        prompts: [
          { value: '请介绍一下你自己' },
          { value: '你能做什么？' },
          { value: '帮我写一段 Python 快排' },
        ],
      },
      // 消息时间展示：用户/助手气泡下方追加小字时间（历史回放由 toTemplateMessages 回填真实时间）
      request: {
        append: [{
          id: 'teapot-msg-time',
          order: 50,
          render: ({ data }) => msgTimeSlot(data.created_at, true),
        }],
      },
      response: {
        append: [{
          id: 'teapot-msg-time',
          order: 50,
          render: ({ data }) => msgTimeSlot(data.created_at),
        }],
      },
      sender: {
        placeholder: '输入消息，Enter 发送',
        maxLength: 10000,
        // 手机端单行输入框（SPEC：chat 模式收件到一行）
        autoSize: isPhone ? { minRows: 1, maxRows: 1 } : undefined,
        // 附件入口已收纳进「+」弹层，模板不渲染默认按钮
        // 懒创建会话：提交前无会话则先创建（含等待 loader 冲刷），规避模板内部竞态
        beforeSubmit: () =>
          newChatCoordinator.ensureSessionBeforeSubmit().then(() => true),
        // 底部操作栏前置：「+」配置弹层入口——记忆 / 计划 / 权限三项请求级配置统一收纳（SPEC §25）
        prefix: (
          <Popover
            open={configOpen}
            onOpenChange={setConfigOpen}
            trigger="click"
            placement="topLeft"
            arrow={false}
            overlayInnerStyle={{ padding: 6, borderRadius: 14 }}
            content={(
              <div style={{ width: 264 }}>
                {([
                  { icon: <SparkAddFileLine />, label: '附件', control: null, onClick: triggerAttachment },
                  { icon: <SparkMemoryLine />, label: '记忆模式', control: (
                    <Select
                      size="small"
                      variant="borderless"
                      value={memoryOverride}
                      onChange={onMemoryOverride}
                      style={{ width: 104 }}
                      options={[
                        { value: '', label: '跟随配置' },
                        { value: 'on', label: '开启记忆' },
                        { value: 'off', label: '关闭记忆' },
                      ]}
                    />
                  ) },
                  { icon: <SparkTextBoxLine />, label: 'Plan Mode', control: (
                    <Switch
                      size="small"
                      checked={planModeOverride === 'on'}
                      onChange={(v) => onPlanModeOverride(v ? 'on' : '')}
                    />
                  ) },
                  { icon: <SparkGuardrailLine />, label: '权限模式', control: (
                    <Select
                      size="small"
                      variant="borderless"
                      value={permissionOverride}
                      onChange={onPermissionOverride}
                      style={{ width: 118 }}
                      options={PERMISSION_OPTIONS as unknown as { value: string; label: string }[]}
                    />
                  ) },
                ]).map((row, i) => (
                  <div
                    key={row.label}
                    onClick={row.onClick}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      gap: 8,
                      padding: '9px 10px',
                      borderRadius: 10,
                      background: i % 2 ? 'transparent' : 'rgba(0,0,0,0.02)',
                      cursor: row.onClick ? 'pointer' : undefined,
                    }}
                  >
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'rgba(26,26,29,0.85)' }}>
                      <span style={{ display: 'inline-flex', color: 'rgba(26,26,29,0.55)' }}>{row.icon}</span>
                      {row.label}
                    </span>
                    {row.control}
                  </div>
                ))}
              </div>
            )}
          >
            <span
              onClick={openConfig}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                cursor: 'pointer',
                color: 'rgba(26,26,29,0.55)',
              }}
            >
              <SparkPlusLine size={16} />
            </span>
          </Popover>
        ),
      },
    };
  }, [currentAgent, activeAgent, agents, isPhone, isTablet, historySlot, homeSlot, tabletSlot, mobileView, registerSessionGetter, setSearchParams, imageCapable, videoCapable, memoryOverride, onMemoryOverride, planModeOverride, onPlanModeOverride, permissionOverride, onPermissionOverride, configOpen, configOverridden, openConfig, triggerAttachment]);

  if (loadingAgents) {
    return null;
  }

  if (agents.length === 0) {
    return (
      <div
        style={{
          height: '100%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <Empty description="暂无可用 Agent，请先在「Agent 管理」中创建" />
      </div>
    );
  }

  if (!options) {
    return null;
  }

  // 手机首页态：WebUI 隐藏但保持挂载（会话 Context/Portal 均在其 Provider 内）
  const showHome = isPhone && mobileView === 'home';

  return (
    <>
      {/* 隐藏文件输入：由「+」弹层的附件行触发 */}
      <input
        ref={fileInputRef}
        type="file"
        style={{ display: 'none' }}
        accept={[
          ...(imageCapable ? ACCEPTED_IMAGE_MIME : []),
          ...(videoCapable ? ACCEPTED_VIDEO_MIME : []),
        ].join(',')}
        multiple={imageCapable && !videoCapable}
        onChange={onFileSelected}
      />
      {/* 附件按钮已收纳进「+」弹层，模板不渲染默认附件按钮 */}
      <div
        className={isPhone && senderExpanded ? 'teapot-chat-expanded' : undefined}
        style={{ height: '100%', position: 'relative', display: 'flex', minWidth: 0 }}
      >
      {showHome && (
        <div
          ref={(el) => setHomeSlot(el)}
          style={{ height: '100%', flex: 1, overflow: 'hidden' }}
        />
      )}
      {/* 平板 / 手机桌面模式（768–991）：模板窄屏无左栏，自渲染左栏槽位补位双栏，
          内容由 ChatBridge Portal 填充（SessionPanel 需会话 Context） */}
      {isTablet && (
        <div
          ref={(el) => setTabletSlot(el)}
          style={{
            width: 300,
            flexShrink: 0,
            height: '100%',
            overflow: 'hidden',
            borderRight: '1px solid rgba(0, 0, 0, 0.05)',
          }}
        />
      )}
      <div style={{ height: '100%', flex: 1, minWidth: 0, display: showHome ? 'none' : undefined }}>
        {/* key=agentKey：切换 Agent 时整体重建，会话列表随之按新 Agent 重载 */}
        <AgentScopeRuntimeWebUI key={currentAgent} options={options} ref={webUIRef} />
      </div>
      {/* 桌面端右侧边栏：当前 Agent 配置总览（Basic Info / Skill / Tool & Advanced / MultiAgent / Channel / Sandbox / MCP） */}
      {!isPhone && !isTablet && <AgentConfigPanel agentKey={currentAgent} />}
      {/* 手机端输入框放大按钮（IconButton，SPEC-mobile M8）：聚焦发送框后浮出，点击切换单行/多行。
          用 Portal 挂载到 sender 元素内部，使 position:absolute 相对输入框定位 */}
      {isPhone && !showHome && (senderFocused || senderExpanded)
        ? (() => {
            const container = document.querySelector<HTMLElement>('.agentscope-runtime-webui-sender');
            return container
              ? createPortal(
                  <IconButton
                    className="teapot-sender-expand-btn"
                    aria-label={senderExpanded ? '收起输入框' : '放大输入框'}
                    icon={senderExpanded ? <SparkShrinkLine size={14} /> : <SparkEnlargeLine size={14} />}
                    onMouseDown={(e: ReactMouseEvent) => e.preventDefault()}
                    onClick={() => setSenderExpanded((v) => !v)}
                  />,
                  container,
                )
              : null;
          })()
        : null}
    </div>
    </>
  );
}
