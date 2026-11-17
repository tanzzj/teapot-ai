import type { CSSProperties } from 'react';
import {
  AppstoreOutlined,
  ArrowLeftOutlined,
  ArrowRightOutlined,
  AudioMutedOutlined,
  BellOutlined,
  BorderOutlined,
  CalendarOutlined,
  CameraOutlined,
  CaretRightFilled,
  CheckOutlined,
  CloseOutlined,
  CreditCardOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  EllipsisOutlined,
  EnvironmentOutlined,
  ExclamationCircleOutlined,
  EyeInvisibleOutlined,
  EyeOutlined,
  FastBackwardFilled,
  FastForwardFilled,
  FolderOutlined,
  HeartFilled,
  HeartOutlined,
  HomeOutlined,
  InfoCircleOutlined,
  LockOutlined,
  MailOutlined,
  MenuOutlined,
  MoreOutlined,
  PaperClipOutlined,
  PauseOutlined,
  PhoneOutlined,
  PictureOutlined,
  PlusOutlined,
  PrinterOutlined,
  QuestionCircleOutlined,
  ReloadOutlined,
  ScheduleOutlined,
  SearchOutlined,
  SendOutlined,
  SettingOutlined,
  ShareAltOutlined,
  ShoppingCartOutlined,
  SoundOutlined,
  StarFilled,
  StarOutlined,
  StepBackwardFilled,
  StepForwardFilled,
  UnlockOutlined,
  UploadOutlined,
  UserOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { Catalog, IconApi, basicCatalog } from '@a2ui/web_core/v1_0';
import { createComponentImplementation, type ReactCatalogComponent } from '@a2ui/react';

/**
 * 官方 basic catalog 的 Icon 是 lit 元素 `<i class="material-icons">名字</i>`，靠宿主提供
 * Material Icons 字体把连字变图形；字体缺失时它就是一个 24px 见方、overflow:auto 的盒子，
 * 里面躺着被裁掉的字母（还带滚动条）。这里用 withEntries 只换掉 Icon 的实现，
 * schema 仍用官方 IconApi（59 个枚举名 + svgPath），catalogId / 校验 / 文档字段全不变，
 * 图形改走应用已有的 antd SVG：不引字体、不依赖外网 CDN，且与宿主图标语言一致。
 */

const ICONS: Record<string, typeof UserOutlined> = {
  accountCircle: UserOutlined,
  add: PlusOutlined,
  arrowBack: ArrowLeftOutlined,
  arrowForward: ArrowRightOutlined,
  attachFile: PaperClipOutlined,
  calendarToday: CalendarOutlined,
  call: PhoneOutlined,
  camera: CameraOutlined,
  check: CheckOutlined,
  close: CloseOutlined,
  delete: DeleteOutlined,
  download: DownloadOutlined,
  edit: EditOutlined,
  event: ScheduleOutlined,
  error: ExclamationCircleOutlined,
  fastForward: FastForwardFilled,
  favorite: HeartFilled,
  favoriteOff: HeartOutlined,
  folder: FolderOutlined,
  help: QuestionCircleOutlined,
  home: HomeOutlined,
  info: InfoCircleOutlined,
  locationOn: EnvironmentOutlined,
  lock: LockOutlined,
  lockOpen: UnlockOutlined,
  mail: MailOutlined,
  menu: MenuOutlined,
  moreVert: MoreOutlined,
  moreHoriz: EllipsisOutlined,
  notifications: BellOutlined,
  pause: PauseOutlined,
  payment: CreditCardOutlined,
  person: UserOutlined,
  phone: PhoneOutlined,
  photo: PictureOutlined,
  play: CaretRightFilled,
  print: PrinterOutlined,
  refresh: ReloadOutlined,
  rewind: FastBackwardFilled,
  search: SearchOutlined,
  send: SendOutlined,
  settings: SettingOutlined,
  share: ShareAltOutlined,
  shoppingCart: ShoppingCartOutlined,
  skipNext: StepForwardFilled,
  skipPrevious: StepBackwardFilled,
  star: StarFilled,
  starOff: StarOutlined,
  stop: BorderOutlined,
  upload: UploadOutlined,
  visibility: EyeOutlined,
  visibilityOff: EyeInvisibleOutlined,
  volumeDown: SoundOutlined,
  volumeMute: AudioMutedOutlined,
  volumeOff: AudioMutedOutlined,
  volumeUp: SoundOutlined,
  warning: WarningOutlined,
};

const boxStyle: CSSProperties = {
  position: 'relative',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 'var(--a2ui-icon-size, 18px)',
  lineHeight: 1,
  color: 'var(--a2ui-icon-color, currentColor)',
};

/** 半星：实心星裁掉右半边叠在空心星上 */
function HalfStar() {
  return (
    <span style={boxStyle}>
      <StarOutlined />
      <span
        style={{
          position: 'absolute',
          inset: 0,
          width: '50%',
          overflow: 'hidden',
          display: 'inline-flex',
        }}
      >
        <StarFilled />
      </span>
    </span>
  );
}

/** antd 没有「铃铛加斜杠」，用一条对角线补出 off 语义 */
function SlashedBell() {
  return (
    <span style={boxStyle}>
      <BellOutlined />
      <span
        style={{
          position: 'absolute',
          left: '6%',
          top: '50%',
          width: '88%',
          height: 'max(1.5px, 0.08em)',
          background: 'currentColor',
          borderRadius: 2,
          transform: 'rotate(-45deg)',
        }}
      />
    </span>
  );
}

const Icon = createComponentImplementation(IconApi, ({ props }) => {
  const name = props.name;
  const flex = props.weight === undefined ? undefined : String(props.weight);

  if (name && typeof name === 'object' && 'svgPath' in name) {
    const path = String((name as { svgPath?: unknown }).svgPath ?? '');
    return (
      <span style={{ ...boxStyle, flex }} role="img" aria-label="icon">
        <svg viewBox="0 0 24 24" width="1em" height="1em" fill="currentColor" aria-hidden="true">
          <path d={path} />
        </svg>
      </span>
    );
  }

  const key = String(name ?? '');
  const glyph =
    key === 'starHalf' ? <HalfStar /> : key === 'notificationsOff' ? <SlashedBell /> : null;
  const Glyph = ICONS[key] ?? AppstoreOutlined;

  return (
    <span style={{ ...boxStyle, flex }} role="img" aria-label={key}>
      {glyph ?? <Glyph />}
    </span>
  );
});

/**
 * 与 basicCatalog 同 id / 同版本 / 同文档，只有 Icon 的实现换成 SVG。
 * 官方 basicCatalog 的类型只收 lit 元素，换成 React 实现前先把条目类型放宽到渲染器认的联合类型。
 */
const base: Catalog<ReactCatalogComponent> = basicCatalog;

export const basicCatalogWithIcons = base.withEntries(
  [...base.components.values()].map((c) => (c.name === 'Icon' ? Icon : c)),
  [...base.functions.values()],
);