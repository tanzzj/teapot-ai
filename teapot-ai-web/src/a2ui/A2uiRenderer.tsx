import { useEffect, useRef, useState, type ComponentType, type CSSProperties } from 'react';
import { theme } from 'antd';
import {
  MessageProcessor,
  STRICT_VALIDATION,
  injectBasicCatalogStyles,
  type ActionPayload,
  type RendererErrorMessage,
  type SurfaceModel,
} from '@a2ui/web_core/v1_0';
import {
  A2uiSurface,
  MarkdownComponentContext,
  type MarkdownComponentProps,
  type ReactCatalogComponent,
} from '@a2ui/react';
import { injectStyles } from '@a2ui/react/styles';
import { A2uiHostContext, teapotCatalog } from './teapotCatalog';
import { basicCatalogWithIcons } from './basicIcons';

/**
 * 官方 A2UI v1.0 渲染器（懒加载：只有出现信封卡片时才拉 @a2ui/* 这一坨依赖）。
 * 一张卡一个 MessageProcessor：surfaceId 只在卡内唯一，卸载即 dispose，
 * 信封内容变化就整体重建（新信封 = 新 surface，不做增量合并）。
 * 双 catalog：basic 18 个组件走官方实现（Icon 换成 antd SVG，见 basicIcons），
 * 7 个业务组件走 teapotCatalog，由信封里逐组件盖的 catalogId 分流。
 */

injectStyles();
injectBasicCatalogStyles();

// CardShell 已提供卡片外壳，只剥根 Card 的盒子；嵌套 Card 保留自己的分组外观
const rootCardStyle = document.createElement('style');
rootCardStyle.textContent =
  '.a2ui-surface > a2ui-card > .a2ui-card{border:none;box-shadow:none;margin:0;padding:0;background:transparent}';
document.head.appendChild(rootCardStyle);

const errorText = (e: RendererErrorMessage): string =>
  `${e.error.code}${e.error.path ? ` @ ${e.error.path}` : ''}: ${e.error.message}`;

export interface A2uiRendererProps {
  messages: Record<string, unknown>[];
  /** Markdown 渲染器由主 chunk 注入：懒加载 chunk 不引 chat 包，避免跨 chunk 循环依赖 */
  markdown: ComponentType<MarkdownComponentProps>;
  disabled?: boolean;
  /** 官方组件派发的 action 事件（表单提交、Button.action） */
  onAction?: (action: ActionPayload) => void;
  /** 业务卡按钮的「发送这句话」：不是 A2UI action，没有 surface/action 语义 */
  onMessage?: (text: string) => void;
}

export default function A2uiRenderer({
  messages,
  markdown,
  disabled,
  onAction,
  onMessage,
}: A2uiRendererProps) {
  const { token } = theme.useToken();
  const [surfaces, setSurfaces] = useState<SurfaceModel<ReactCatalogComponent>[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const actionRef = useRef(onAction);
  actionRef.current = onAction;

  const payload = JSON.stringify(messages);

  useEffect(() => {
    const processor = new MessageProcessor<ReactCatalogComponent>(
      [basicCatalogWithIcons, teapotCatalog],
      (action) => actionRef.current?.(action),
      {
        version: '1.0',
        // 拓扑三项放宽：官方增量流里组件可以后一条消息才到（root 引用模板、占位卡先于父节点），
        // 逐条严判会把规范合法的流整批拒掉。图规则的权威校验在后端提交期，这里只判组件本身。
        validationConfig: {
          ...STRICT_VALIDATION,
          targetVersion: '1.0',
          allowMissingRoot: true,
          allowDanglingReferences: true,
          allowOrphanComponents: true,
        },
        reportErrorsOnly: true,
        errorHandler: (e) => setErrors((prev) => [...prev, errorText(e)]),
      },
    );
    const sync = () => setSurfaces([...processor.model.surfacesMap.values()]);
    // 先处理再订阅：createSurface 事件在订阅前就发完了，处理完主动同步一次
    const produced = processor.processMessagesWithErrors(JSON.parse(payload) as unknown);
    setErrors(produced.map(errorText));
    sync();
    const created = processor.onSurfaceCreated(sync);
    const deleted = processor.onSurfaceDeleted(sync);
    return () => {
      created.unsubscribe();
      deleted.unsubscribe();
      processor.dispose();
    };
  }, [payload]);

  // 官方组件的配色全走 --a2ui-* 变量，:where() 零特异性，宿主行内样式即可覆盖并透进 shadow DOM
  const themeVars = {
    '--a2ui-color-primary': token.colorPrimary,
    '--a2ui-color-on-primary': token.colorWhite,
    '--a2ui-color-background': token.colorBgContainer,
    '--a2ui-color-on-background': token.colorText,
    '--a2ui-color-surface': token.colorBgContainer,
    '--a2ui-color-on-surface': token.colorText,
    '--a2ui-color-border': token.colorBorder,
    '--a2ui-color-input': token.colorBgContainer,
    '--a2ui-color-on-input': token.colorText,
    '--a2ui-border-radius': `${token.borderRadius}px`,
    // 卡片正文 13px，官方组件默认 1rem 会明显大一号
    '--a2ui-font-size': '13px',
    '--a2ui-icon-size': '18px',
  } as CSSProperties;

  return (
    <A2uiHostContext.Provider value={{ disabled: !!disabled, onAction: onMessage }}>
      <div
        className="a2ui-light"
        style={{
          ...themeVars,
          pointerEvents: disabled ? 'none' : undefined,
          opacity: disabled ? 0.65 : 1,
        }}
      >
        <MarkdownComponentContext.Provider value={markdown}>
          {surfaces.map((surface) => (
            <div key={surface.id} className="a2ui-surface">
              <A2uiSurface surface={surface} />
            </div>
          ))}
        </MarkdownComponentContext.Provider>
        {errors.length ? (
          <div style={{ fontSize: 12, color: '#ff4d4f', marginTop: 6, wordBreak: 'break-word' }}>
            {errors.map((e, i) => (
              <div key={i}>{e}</div>
            ))}
          </div>
        ) : null}
      </div>
    </A2uiHostContext.Provider>
  );
}