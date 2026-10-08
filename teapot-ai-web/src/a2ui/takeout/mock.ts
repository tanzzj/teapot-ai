import type { TakeoutOrderCardProps } from './TakeoutOrderCard';
import type { TakeoutStore } from './TakeoutStoreList';

/**
 * 演示用假数据：外卖链路（店铺、菜品、地址、优惠）在本平台没有真实数据源，
 * 组件在调用方什么都没给时整卡回落到这里，便于单独挂载与 catalog 试渲染。
 * 只要给了数据（见各组件的 hasData 判定）就全量按真实数据渲染，绝不与 mock 混用。
 */

export const MOCK_STORES: TakeoutStore[] = [
  {
    name: '川香居·麻辣香锅（天河店）',
    logo: '🌶',
    badges: ['可堂食'],
    rating: 4.5,
    minOrder: 15,
    eta: 28,
    desc: '重口味首选，香锅现炒分量足，小龙虾拌面是招牌，午市常排队。',
    items: [
      { name: '香辣小龙虾拌面', sales: '已售2000+', rank: '近30天该店热销第2名', price: 28 },
      { name: '招牌麻辣香锅（中辣）', sales: '已售3500+', rank: '本月好评第1名', price: 41.8 },
    ],
  },
  {
    name: '粤味煲仔饭专家',
    logo: '🍲',
    badges: ['可堂食', '24小时'],
    rating: 4.8,
    minOrder: 20,
    eta: 35,
    desc: '锅巴香脆、腊味可选，配例汤，适合一个人吃饭。',
    items: [
      { name: '腊味煲仔饭', sales: '已售5000+', price: 32 },
      { name: '滑蛋牛肉煲仔饭', sales: '已售1200+', rank: '近30天热销第5名', price: 38 },
    ],
    actionText: '看菜单',
  },
  {
    name: '老北京炸酱面馆',
    logo: '🍜',
    rating: 4.2,
    minOrder: 18,
    eta: '约40分钟',
    desc: '手擀面色码齐全，炸酱偏咸，面条可免费加。',
    items: [{ name: '肉丁炸酱面', sales: '已售900+', price: 22 }],
  },
];

export const MOCK_ORDER: TakeoutOrderCardProps = {
  shop: { name: '川香居·麻辣香锅（天河店）', logo: '🌶' },
  items: [
    { name: '招牌麻辣香锅（中辣）', spec: '中辣 / 常温 / 加米饭', price: 41.8, qty: 1 },
    { name: '香辣小龙虾拌面', spec: '不加葱', price: 28, qty: 1 },
  ],
  rows: [
    { label: '送达时间', value: '立即配送', clickable: true },
    { label: '配送至', value: '天河区珠江新城兴盛广场 B 座 1208', clickable: true },
    { label: '备注', hint: '点击说明对商家的要求' },
    { label: '其他费用', value: '¥5.0' },
    { label: '总优惠', value: '-¥22.0', notes: ['千问每日必减 ¥2.2', '店铺券 ¥19.8'], tone: 'discount' },
    { label: '总计', value: '¥47.6', tone: 'total' },
  ],
  actions: [
    { text: '进店选购', kind: 'ghost' },
    { text: '支付宝付款', kind: 'primary', icon: 'alipay' },
  ],
};
