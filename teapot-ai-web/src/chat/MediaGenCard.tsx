import React, { useMemo } from 'react';
import { DefaultCards, ImageGenerator } from '@agentscope-ai/chat';
import { Audio } from '@agentscope-ai/design';

/**
 * 媒体生成自定义工具渲染（SPEC-media-gen 修订）：
 * 经 AgentScopeRuntimeWebUI options.customToolRenderConfig 按工具名挂载到 dashscope_* 媒体生成工具，
 * 替代默认 ToolCall 折叠面板（默认面板只把结果当代码文本展示，图片/视频不可见）。
 *
 * 数据来源：aguiBridge 把 AG-UI TOOL_CALL_RESULT 翻译为 tool_call_output，
 * 渲染期 mergeToolMessages 把 output 合并进 content[1].data.output；
 * output 与后端 AguiStreamContext.serialize 形态一致：文本块原样、媒体块（image/video/audio）
 * 逐块 JSON 一行（{"type":"image","source":{"type":"url","url":...}}）。
 * 历史回放经 SessionMessageConverter 同形态序列化，本卡片实时/历史通用。
 */

interface ToolData {
  name?: string;
  call_id?: string;
  arguments?: string;
  output?: string;
}

interface RuntimeMessageLike {
  status?: string;
  content?: { data?: ToolData }[];
}

type MediaType = 'image' | 'video' | 'audio';

interface MediaItem {
  type: MediaType;
  url: string;
}

/** source → 可展示 URL（url 直链或 base64 data URL）；无法识别返回 null */
function sourceToUrl(source: unknown): string | null {
  if (!source || typeof source !== 'object') return null;
  const s = source as { type?: string; url?: string; media_type?: string; data?: string };
  if (s.type === 'url' && s.url) return s.url;
  if (s.type === 'base64' && s.data) {
    return `data:${s.media_type ?? 'application/octet-stream'};base64,${s.data}`;
  }
  return null;
}

/** 逐行解析 output 中的媒体块（容错：非 JSON / 不完整行直接跳过） */
function parseMedia(output?: string): MediaItem[] {
  if (!output) return [];
  const items: MediaItem[] = [];
  for (const line of output.split('\n')) {
    const t = line.trim();
    if (!t.startsWith('{')) continue;
    try {
      const obj = JSON.parse(t) as { type?: string; source?: unknown };
      if (obj.type !== 'image' && obj.type !== 'video' && obj.type !== 'audio') continue;
      const url = sourceToUrl(obj.source);
      if (url) items.push({ type: obj.type, url });
    } catch {
      // 流式或异常行：跳过
    }
  }
  return items;
}

/** 非 JSON 行（工具错误文本等）原样保留，无媒体时兜底展示 */
function parseText(output?: string): string {
  if (!output) return '';
  return output
    .split('\n')
    .filter((l) => l.trim() && !l.trim().startsWith('{'))
    .join('\n');
}

// 音频播放器边界修复样式统一收敛在 index.css（.teapot-audio-card / -audio-container /
// -media-player-controller 段）：库组件 MediaPlayerController 硬编码 height:40px + overflow:hidden，
// 此处不再运行时注入，避免与静态样式重复。

const MediaGenCard = React.memo(function ({ data }: { data: RuntimeMessageLike }) {
  const output = data.content?.[1]?.data?.output;
  const loading = data.status === 'in_progress' && !output;
  const medias = useMemo(() => parseMedia(output), [output]);
  const text = useMemo(() => parseText(output), [output]);

  // 生成中：骨架屏 + 提示（视频分钟级同步阻塞期间同样停留在此态）
  if (loading) {
    return <ImageGenerator loadingText="媒体生成中，请耐心等待…" doneText="" />;
  }

  if (!medias.length) {
    return text ? (
      <div style={{ color: 'rgba(0,0,0,0.45)', fontSize: 12, whiteSpace: 'pre-wrap' }}>{text}</div>
    ) : null;
  }

  const images = medias.filter((m) => m.type === 'image');
  const videos = medias.filter((m) => m.type === 'video');
  const audios = medias.filter((m) => m.type === 'audio');

  // 根容器自身也是气泡 flex 容器（align-items:flex-start）的 item，不撑宽就会收缩到内容宽度，
  // 音频播放器因此变成窄条、进度条被挤成 0 宽；此处显式撑满（子项仍由 alignItems 保持左对齐不拉伸）
  return (
    <div
      style={{ display: 'flex', flexDirection: 'column', gap: 8, alignItems: 'flex-start', width: '100%' }}
    >
      {images.map((m, i) => (
        <ImageGenerator
          key={`img-${i}`}
          src={m.url}
          width={256}
          height={256}
          loadingText="图片生成中…"
          doneText="图片生成完成"
        />
      ))}
      {videos.length > 0 && <DefaultCards.Videos data={videos.map((m) => ({ src: m.url }))} />}
      {audios.length > 0 && (
        <div style={{ alignSelf: 'stretch', display: 'flex', flexDirection: 'column', gap: 8 }}>
          {audios.map((m, i) => (
            <div key={`aud-${i}`} className="teapot-audio-card">
              <Audio src={m.url} />
            </div>
          ))}
        </div>
      )}
    </div>
  );
});

export default MediaGenCard;
