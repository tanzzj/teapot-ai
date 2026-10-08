import { str, strList } from '../envelope';
import { C, money, numOrStr, objs, PillButton, Thumb } from './parts';
import { MOCK_STORES } from './mock';

/**
 * 店铺推荐卡（复刻千问外卖）：一店一卡横向滑动，卡内店铺头部（徽标 + 评分/起送/时长）
 * + 代表商品行（图 + 已售/榜单 + 价格）+ 一句话推荐 + 「进店逛逛」。
 * 独立使用直接传 props；a2ui 渲染走 catalog 组件 TakeoutStoreList（props 见 teapot-catalog.json，
 * 嵌套结构为 array 对象，由 takeoutStoreListFromProps 容错转换）。
 * 平台没有真实外卖数据源：完全不给 stores 时整卡回落 MOCK_STORES。
 */

export interface TakeoutStoreItem {
  name?: string;
  image?: string;
  sales?: string;
  rank?: string;
  price?: number | string;
}

export interface TakeoutStore {
  name?: string;
  logo?: string;
  /** 店铺标签，首枚按「可堂食」同款绿底渲染 */
  badges?: string[];
  rating?: number | string;
  minOrder?: number | string;
  /** 送达时长；数字按分钟处理 */
  eta?: number | string;
  desc?: string;
  items?: TakeoutStoreItem[];
  actionText?: string;
  /** 点击按钮派发的消息文本，缺省用 actionText */
  actionValue?: string;
}

export interface TakeoutStoreListProps {
  title?: string;
  stores?: TakeoutStore[];
  /** 历史回放/只读挂载：禁按钮 */
  disabled?: boolean;
  /** 按钮点击 → 由外层决定派发方式（a2ui 走普通消息发送） */
  onAction?: (text: string) => void;
}

const etaText = (v?: string | number): string | undefined => {
  const t = typeof v === 'number' ? String(v) : v;
  return t && /^\d+(\.\d+)?$/.test(t) ? `${t}分钟` : t;
};

function StoreCard({ store, disabled, onAction }: {
  store: TakeoutStore;
  disabled?: boolean;
  onAction?: (text: string) => void;
}) {
  const meta = [
    store.rating ? `${store.rating}分` : undefined,
    store.minOrder ? `起送${money(store.minOrder)}` : undefined,
    etaText(store.eta),
  ].filter((x): x is string => !!x);
  const actionText = store.actionText ?? '进店逛逛';

  return (
    <div
      style={{
        flex: '0 0 auto',
        width: 252,
        scrollSnapAlign: 'start',
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        padding: 12,
        borderRadius: 12,
        border: `1px solid ${C.line}`,
        background: '#fff',
      }}
    >
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <Thumb src={store.logo} size={36} />
        <div style={{ minWidth: 0, flex: 1 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 5, minWidth: 0 }}>
            <span
              style={{
                fontSize: 13.5,
                fontWeight: 600,
                color: C.fg,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {store.name ?? ''}
            </span>
            {(store.badges ?? []).map((b, i) => (
              <span
                key={i}
                style={{
                  flex: '0 0 auto',
                  padding: '1px 6px',
                  borderRadius: 5,
                  fontSize: 11,
                  color: C.green,
                  background: C.greenBg,
                }}
              >
                {b}
              </span>
            ))}
          </div>
          {meta.length ? (
            <div style={{ fontSize: 11.5, color: C.fg3, marginTop: 2 }}>{meta.join(' · ')}</div>
          ) : null}
        </div>
      </div>

      {(store.items ?? []).map((item, i) => (
        <div key={i} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <Thumb src={item.image} size={52} />
          <div style={{ minWidth: 0, flex: 1 }}>
            <div
              style={{
                fontSize: 13,
                color: C.fg,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {item.name ?? ''}
            </div>
            <div
              style={{
                fontSize: 11.5,
                color: C.fg3,
                marginTop: 2,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {[item.sales, item.rank].filter(Boolean).join(' · ')}
            </div>
          </div>
          {item.price !== undefined && item.price !== '' ? (
            <div style={{ flex: '0 0 auto', fontSize: 13, fontWeight: 600, color: C.price }}>
              {money(item.price)}
            </div>
          ) : null}
        </div>
      ))}

      {store.desc ? (
        <div
          style={{
            fontSize: 12,
            color: C.fg2,
            lineHeight: 1.5,
            display: '-webkit-box',
            WebkitBoxOrient: 'vertical',
            WebkitLineClamp: 2,
            overflow: 'hidden',
          }}
        >
          {store.desc}
        </div>
      ) : null}

      <div style={{ marginTop: 'auto' }}>
        <PillButton
          block
          kind="primary"
          text={actionText}
          disabled={disabled}
          onClick={() => onAction?.(store.actionValue || actionText)}
        />
      </div>
    </div>
  );
}

export function TakeoutStoreList({ title, stores, disabled, onAction }: TakeoutStoreListProps) {
  // 演示回落：完全没给 stores 时整卡渲染 mock；给了就只按真实数据渲染，不与 mock 混用
  const mocked = !stores?.length;
  const list = mocked ? MOCK_STORES : stores;
  const heading = title ?? (mocked ? '附近高分外卖' : undefined);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {heading ? (
        <div style={{ fontSize: 13.5, fontWeight: 600, color: C.fg }}>{heading}</div>
      ) : null}
      <div
        style={{
          display: 'flex',
          gap: 10,
          overflowX: 'auto',
          scrollSnapType: 'x mandatory',
          paddingBottom: 4,
        }}
      >
        {list.map((s, i) => (
          <StoreCard key={i} store={s} disabled={disabled} onAction={onAction} />
        ))}
      </div>
    </div>
  );
}

/** catalog props（JSON 宽松结构）→ 类型化 props；stores 缺失/为空时由组件回落 mock */
export function takeoutStoreListFromProps(raw: Record<string, unknown>): TakeoutStoreListProps {
  return {
    title: str(raw.title),
    stores: objs(raw.stores).map((s) => ({
      name: str(s.name),
      logo: str(s.logo),
      badges: strList(s.badges),
      rating: numOrStr(s.rating),
      minOrder: numOrStr(s.minOrder),
      eta: numOrStr(s.eta),
      desc: str(s.desc),
      items: objs(s.items).map((it) => ({
        name: str(it.name),
        image: str(it.image),
        sales: str(it.sales),
        rank: str(it.rank),
        price: numOrStr(it.price),
      })),
      actionText: str(s.actionText),
      actionValue: str(s.actionValue),
    })),
  };
}
