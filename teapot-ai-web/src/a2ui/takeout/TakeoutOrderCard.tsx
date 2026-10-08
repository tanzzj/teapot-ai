import { AlipayCircleFilled, EditOutlined, RightOutlined } from '@ant-design/icons';
import { str, strList } from '../envelope';
import { C, money, numOrStr, objs, PillButton, Thumb } from './parts';
import { MOCK_ORDER } from './mock';

/**
 * 订单确认卡（复刻千问外卖）：店铺行 + 商品行（规格可改）+ 履约/费用行 + 合计 + 双按钮。
 * 独立使用直接传 props；a2ui 渲染走 catalog 组件 TakeoutOrderCard
 * （rows/actions 为 array 对象，由 takeoutOrderCardFromProps 容错转换）。
 * 平台没有真实外卖数据源：整卡无数据时回落 MOCK_ORDER，有数据时不与 mock 混用。
 */

export interface TakeoutOrderShop {
  name?: string;
  logo?: string;
}

export interface TakeoutOrderItem {
  name?: string;
  image?: string;
  /** 规格，如「中辣 / 常温」 */
  spec?: string;
  price?: number | string;
  qty?: number | string;
}

export interface TakeoutOrderRow {
  label?: string;
  value?: string;
  /** 值位置的次要提示（如「点击说明对商家的要求」） */
  hint?: string;
  /** 值下方的明细行（总优惠的减免构成） */
  notes?: string[];
  tone?: 'normal' | 'discount' | 'total';
  /** 右箭头：该行可点开重选（送达时间、配送地址） */
  clickable?: boolean;
}

export interface TakeoutOrderAction {
  text?: string;
  /** 点击派发的消息文本，缺省用 text */
  value?: string;
  kind?: 'primary' | 'ghost';
  icon?: 'alipay';
}

export interface TakeoutOrderCardProps {
  shop?: TakeoutOrderShop;
  items?: TakeoutOrderItem[];
  rows?: TakeoutOrderRow[];
  actions?: TakeoutOrderAction[];
  disabled?: boolean;
  onAction?: (text: string) => void;
}

function RowLine({ row }: { row: TakeoutOrderRow }) {
  const total = row.tone === 'total';
  const discount = row.tone === 'discount';
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10, fontSize: 12.5 }}>
      <span style={{ flex: '0 0 auto', color: C.fg2, paddingTop: total ? 2 : 1 }}>{row.label ?? ''}</span>
      <span style={{ flex: 1, minWidth: 0, textAlign: 'right' }}>
        {row.value ? (
          <span
            style={{
              color: total || discount ? C.price : C.fg,
              fontWeight: total ? 600 : 400,
              fontSize: total ? 15 : 12.5,
              wordBreak: 'break-word',
            }}
          >
            {row.value}
          </span>
        ) : null}
        {row.hint ? <span style={{ color: C.fg3 }}>{row.hint}</span> : null}
        {(row.notes ?? []).map((n, i) => (
          <span key={i} style={{ color: C.price, fontSize: 11.5, marginLeft: 6 }}>
            {n}
          </span>
        ))}
        {row.clickable ? (
          <RightOutlined style={{ color: C.fg3, fontSize: 10, marginLeft: 5 }} />
        ) : null}
      </span>
    </div>
  );
}

export function TakeoutOrderCard(props: TakeoutOrderCardProps) {
  const { disabled, onAction } = props;
  // 演示回落：整卡没给任何数据时渲染 MOCK_ORDER；给了数据就全按真实数据渲染，不与 mock 混用
  const hasData = !!(props.shop?.name || props.items?.length || props.rows?.length);
  const { shop, items = [], rows = [], actions = [] } = hasData ? props : MOCK_ORDER;
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 9,
        padding: 12,
        borderRadius: 12,
        border: `1px solid ${C.line}`,
        background: '#fff',
      }}
    >
      {shop?.name ? (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <Thumb src={shop.logo} size={22} />
          <span style={{ flex: 1, minWidth: 0, fontSize: 13, fontWeight: 600, color: C.fg }}>
            {shop.name}
          </span>
          <RightOutlined style={{ color: C.fg3, fontSize: 10 }} />
        </div>
      ) : null}

      {items.map((item, i) => (
        <div key={i} style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
          <Thumb src={item.image} size={72} />
          <div style={{ minWidth: 0, flex: 1 }}>
            <div
              style={{
                fontSize: 13.5,
                fontWeight: 600,
                color: C.fg,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {item.name ?? ''}
            </div>
            {item.spec ? (
              <span
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 4,
                  marginTop: 4,
                  padding: '2px 7px',
                  borderRadius: 6,
                  fontSize: 11.5,
                  color: C.fg2,
                  background: C.chipBg,
                }}
              >
                {item.spec}
                <EditOutlined style={{ fontSize: 10, color: C.fg3 }} />
              </span>
            ) : null}
          </div>
          <div style={{ flex: '0 0 auto', textAlign: 'right' }}>
            <div style={{ fontSize: 13, color: C.fg }}>{money(item.price)}</div>
            {item.qty ? <div style={{ fontSize: 11.5, color: C.fg3, marginTop: 2 }}>x{item.qty}</div> : null}
          </div>
        </div>
      ))}

      {rows.length ? <hr style={{ border: 'none', borderTop: `1px solid ${C.line}`, margin: '1px 0' }} /> : null}
      {rows.map((row, i) => (
        <RowLine key={i} row={row} />
      ))}

      {actions.length ? (
        <div style={{ display: 'flex', gap: 8, marginTop: 2 }}>
          {actions.map((a, i) => (
            <div key={i} style={{ flex: '1 1 0', minWidth: 0 }}>
              <PillButton
                block
                kind={a.kind === 'ghost' ? 'ghost' : 'primary'}
                text={a.text ?? ''}
                disabled={disabled}
                icon={a.icon === 'alipay' ? <AlipayCircleFilled /> : undefined}
                onClick={() => onAction?.(a.value || a.text || '')}
              />
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}

/** catalog props（JSON 宽松结构）→ 类型化 props */
export function takeoutOrderCardFromProps(raw: Record<string, unknown>): TakeoutOrderCardProps {
  const shops = objs(raw.shop);
  const shopObj = typeof raw.shop === 'string' ? { name: raw.shop } : shops[0];
  return {
    shop: shopObj ? { name: str(shopObj.name), logo: str(shopObj.logo) } : undefined,
    items: objs(raw.items).map((it) => ({
      name: str(it.name),
      image: str(it.image),
      spec: str(it.spec),
      price: numOrStr(it.price),
      qty: numOrStr(it.qty),
    })),
    rows: objs(raw.rows).map((r) => ({
      label: str(r.label),
      value: numOrStr(r.value),
      hint: str(r.hint),
      notes: strList(r.notes),
      tone: (str(r.tone) as TakeoutOrderRow['tone']) ?? undefined,
      clickable: r.clickable === true,
    })),
    actions: objs(raw.actions).map((a) => ({
      text: str(a.text),
      value: str(a.value),
      kind: (str(a.kind) as TakeoutOrderAction['kind']) ?? undefined,
      icon: (str(a.icon) as TakeoutOrderAction['icon']) ?? undefined,
    })),
  };
}
