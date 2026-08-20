package com.teamer.teapot.ai.core.storage;

/**
 * 图片存储产物（SPEC §20.3）：url 可直接作为 AG-UI image part 的 url 源
 * （data URL 或 OSS 公网直链）。
 */
public record StoredImage(String strategy, String url) {
}
