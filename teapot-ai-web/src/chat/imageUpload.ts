/**
 * 对话台图片附件处理（SPEC §19/§20/§22.1 多模态）：限额拦截 + 本地压缩 + antd Upload customRequest。
 * 双链路按 Agent 选择（§22.1）：上传前探测 /api/chat/image/strategy?agentKey=——
 *   base64（默认）：压缩产物转 data URL 随 AG-UI message parts 直传（零往返）；
 *   oss：压缩产物 multipart 直传 /api/chat/image/upload?agentKey=，response.url = OSS 公网直链，
 *        aguiBridge 既有 url 源分支自动转 {type:'url'}（后端 state 只存 URL）；
 *        历史消息中的 base64 图不受影响，照常渲染。
 */

import { http } from '../api/http';
import type { Result } from '../types';

export const MAX_IMAGES_PER_MESSAGE = 4;
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
export const ACCEPTED_IMAGE_MIME = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];
export const ACCEPT_ATTR = ACCEPTED_IMAGE_MIME.join(',');

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
  const fd = new FormData();
  fd.append('file', blob, fileName);
  const resp = await http.post<Result<{ url: string; strategy: string }>>(
    `/api/chat/image/upload?agentKey=${encodeURIComponent(agentKey)}`,
    fd,
    { headers: { 'Content-Type': 'multipart/form-data' } },
  );
  const url = resp.data?.data?.url;
  if (!url) {
    throw new Error('图片上传失败：服务端未返回 URL');
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
 * antd Upload customRequest 工厂（§22.1 按 Agent）：本地压缩后按该 Agent 生效载体产出 response.url——
 * base64 → data URL；oss → 服务端上传后的 OSS 直链。
 * 模板提交时按 response.url 过滤并转成消息 image part（aguiBridge 双源分支）。
 */
export function imageCustomRequestFor(agentKey: string) {
  return function imageCustomRequest(options: CustomRequestOptions) {
    options.onProgress?.({ percent: 30 });
    (async () => {
      const [strategy, compressed] = await Promise.all([
        effectiveStrategy(agentKey),
        compressToBlob(options.file),
      ]);
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
