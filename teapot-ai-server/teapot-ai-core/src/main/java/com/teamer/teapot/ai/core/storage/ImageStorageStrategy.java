package com.teamer.teapot.ai.core.storage;

/**
 * 图片存储策略（SPEC §20.3 策略模式）：store 返回可直接作为 AG-UI url 源的引用。
 * 实现：{@link InlineBase64StorageStrategy}（base64 内联）/ {@link OssImageStorageStrategy}（阿里云 OSS）。
 */
public interface ImageStorageStrategy {

    /** 策略名：base64 | oss */
    String name();

    /** 存储图片并返回引用；mediaType 形如 image/jpeg */
    StoredImage store(byte[] data, String mediaType);
}
