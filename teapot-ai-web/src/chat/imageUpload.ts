/**
 * 对话台图片/视频附件处理（SPEC §19/§20/§22.1 多模态）：限额拦截 + 本地压缩（仅图片）+ antd Upload customRequest。
 * 双链路按 Agent 选择（§22.1）：上传前探测 /api/chat/image/strategy?agentKey=——
 *   base64（默认）：产物转 data URL 随 AG-UI message parts 直传（零往返）；
 *   oss：产物 multipart 直传上传端点（图片 /api/chat/image/upload、视频 /api/chat/video/upload），
 *        response.url = OSS 公网直链，aguiBridge 既有 url 源分支自动转 {type:'url'}（后端 state 只存 URL）；
 *        历史消息中的 base64 媒体不受影响，照常渲染。
 * 视频不做浏览器端压缩（无 ffmpeg 依赖），原样上传，服务端限额 30MB。
 * webm 特例：MediaRecorder 产出的 webm 缺 Duration 元数据会被 DashScope 拒收（Invalid video file），
 * 上传前用 fix-webm-duration 补写真实时长（先用 video seek 技巧探测，探测失败则原样上传不阻断）。
 */

import fixWebmDuration from 'fix-webm-duration';
import { http } from '../api/http';
import type { Result } from '../types';

export const MAX_IMAGES_PER_MESSAGE = 4;
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
export const ACCEPTED_IMAGE_MIME = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];
export const ACCEPT_ATTR = ACCEPTED_IMAGE_MIME.join(',');

/** 视频附件：单条消息 1 个（base64 模式下体积大，避免多视频叠加撑爆请求体） */
export const MAX_VIDEOS_PER_MESSAGE = 1;
export const MAX_VIDEO_BYTES = 30 * 1024 * 1024;
export const ACCEPTED_VIDEO_MIME = ['video/mp4', 'video/webm', 'video/quicktime', 'video/x-matroska'];
/** 图片+视频混合 accept（Chat 页按能力位裁剪） */
export const MEDIA_ACCEPT_ATTR = [...ACCEPTED_IMAGE_MIME, ...ACCEPTED_VIDEO_MIME].join(',');

/** 压缩目标：长边 ≤ 2048px、JPEG quality 0.85（GIF 保留原图避免丢动画） */
const MAX_EDGE = 2048;
const JPEG_QUALITY = 0.85;

function readFileAsDataURL(file: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error('图片读取失败'));
    reader.readAsDataURL(file);
  });
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('图片解码失败'));
    img.src = src;
  });
}

function canvasToBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (b) => (b ? resolve(b) : reject(new Error('图片编码失败'))),
      'image/jpeg',
      JPEG_QUALITY,
    );
  });
}

/**
 * 压缩为 Blob + mediaType：GIF 原样返回；其余按长边缩放后 canvas 重编码 JPEG。
 * PNG 透明信息在 JPEG 重编码后丢失，故统一白底填充。
 */
async function compressToBlob(file: File): Promise<{ blob: Blob; mediaType: string }> {
  if (file.type === 'image/gif') {
    return { blob: file, mediaType: 'image/gif' };
  }
  const origin = await readFileAsDataURL(file);
  const img = await loadImage(origin);
  const scale = Math.min(1, MAX_EDGE / Math.max(img.width, img.height));
  const width = Math.max(1, Math.round(img.width * scale));
  const height = Math.max(1, Math.round(img.height * scale));
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    return { blob: file, mediaType: file.type || 'image/jpeg' };
  }
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, width, height);
  ctx.drawImage(img, 0, 0, width, height);
  return { blob: await canvasToBlob(canvas), mediaType: 'image/jpeg' };
}

/**
 * webm 补 Duration：fix-webm-duration 1.x 只把传入值原样写入 EBML（不会自动推算），
 * 故先用 video 元素探测真实时长——Infinity-duration 的 MediaRecorder 产物靠 seek 到极大值触发 durationchange。
 * 探测失败时原样返回不阻断上传（已含 Duration 的文件库内会跳过）。
 */
async function fixWebmDurationIfNeeded(file: File): Promise<Blob> {
  if (file.type !== 'video/webm') {
    return file;
  }
  try {
    const seconds = await probeDurationSeconds(file);
    if (!seconds || !Number.isFinite(seconds) || seconds <= 0) {
      return file;
    }
    return await fixWebmDuration(file, Math.round(seconds * 1000), { logger: false });
  } catch {
    return file;
  }
}

/** 探测媒体时长（秒）：Infinity 时 seek 到 1e9 等 durationchange，超时放弃 */
function probeDurationSeconds(blob: Blob): Promise<number | null> {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(blob);
    const video = document.createElement('video');
    video.preload = 'metadata';
    video.muted = true;
    const timer = window.setTimeout(() => finish(null), 5000);
    const finish = (v: number | null) => {
      window.clearTimeout(timer);
      video.removeAttribute('src');
      URL.revokeObjectURL(url);
      resolve(v);
    };
    video.onloadedmetadata = () => {
      if (Number.isFinite(video.duration) && video.duration > 0) {
        finish(video.duration);
      } else {
        // MediaRecorder 产物 duration=Infinity：seek 到极大值触发 duration 修正
        video.currentTime = 1e9;
        video.ondurationchange = () => {
          if (Number.isFinite(video.duration) && video.duration > 0) {
            finish(video.duration);
          }
        };
      }
    };
    video.onerror = () => finish(null);
    video.src = url;
  });
}

/** 生效载体探测（§22.1）：每次上传前实时探测（请求极小），避免管理端切换载体后需刷新页面才生效 */
function effectiveStrategy(agentKey: string): Promise<string> {
  return http.get<Result<{ strategy: string }>>(
    `/api/chat/image/strategy?agentKey=${encodeURIComponent(agentKey)}`,
  )
    .then((resp) => resp.data?.data?.strategy || 'base64')
    .catch(() => 'base64');
}

/** oss 链路：multipart 直传上传端点（携带 agentKey 按记录路由），返回 OSS 公网直链 */
async function uploadToServer(blob: Blob, mediaType: string, fileName: string, agentKey: string): Promise<string> {
  const isVideo = mediaType.startsWith('video/');
  const fd = new FormData();
  fd.append('file', blob, fileName);
  const resp = await http.post<Result<{ url: string; strategy: string }>>(
    `${isVideo ? '/api/chat/video/upload' : '/api/chat/image/upload'}?agentKey=${encodeURIComponent(agentKey)}`,
    fd,
    { headers: { 'Content-Type': 'multipart/form-data' } },
  );
  const url = resp.data?.data?.url;
  if (!url) {
    throw new Error(isVideo ? '视频上传失败：服务端未返回 URL' : '图片上传失败：服务端未返回 URL');
  }
  return url;
}

interface CustomRequestOptions {
  file: File;
  onSuccess?: (body: { url: string }) => void;
  onError?: (error: Error) => void;
  onProgress?: (event: { percent: number }) => void;
}

/**
 * antd Upload customRequest 工厂（§22.1 按 Agent）：图片本地压缩、视频原样，
 * 按该 Agent 生效载体产出 response.url——base64 → data URL；oss → 服务端上传后的 OSS 直链。
 * 模板提交时按 response.url 产出消息 image/video part（aguiBridge 双源分支）。
 */
export function imageCustomRequestFor(agentKey: string) {
  return function imageCustomRequest(options: CustomRequestOptions) {
    options.onProgress?.({ percent: 30 });
    (async () => {
      const isVideo = (options.file.type || '').startsWith('video/');
      const strategy = await effectiveStrategy(agentKey);
      // 视频无浏览器端压缩管线，webm 仅补 Duration 元数据后原样；图片走 canvas 压缩
      const compressed = isVideo
        ? { blob: await fixWebmDurationIfNeeded(options.file), mediaType: options.file.type }
        : await compressToBlob(options.file);
      if (strategy === 'oss') {
        options.onProgress?.({ percent: 60 });
        const url = await uploadToServer(compressed.blob, compressed.mediaType, options.file.name, agentKey);
        options.onProgress?.({ percent: 100 });
        options.onSuccess?.({ url });
        return;
      }
      const url = await readFileAsDataURL(compressed.blob);
      options.onProgress?.({ percent: 100 });
      options.onSuccess?.({ url });
    })().catch((e) => options.onError?.(e instanceof Error ? e : new Error(String(e))));
  };
}
