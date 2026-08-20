package com.teamer.teapot.ai.core.storage;

import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.config.OssConnection;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.model.StorageConfigDO;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * OSS 策略（SPEC §20.3/§22.1）：putObject + 对象级 public-read + UUID key，
 * 返回可永久访问的公网直链（多轮追问重放历史 URL，不能用预签名，§20.2 决策 2）。
 * 双入口：全局 OssConnection（存量）与指定记录（§22.1 按 Agent 选载体）。
 */
@Slf4j
@Component
public class OssImageStorageStrategy implements ImageStorageStrategy {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Map<String, String> EXT = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp", "image/gif", "gif");

    private final OssConnection ossConnection;
    private final OssClientManager ossClientManager;
    private final TeapotAiProperties properties;

    public OssImageStorageStrategy(OssConnection ossConnection,
                                   OssClientManager ossClientManager,
                                   TeapotAiProperties properties) {
        this.ossConnection = ossConnection;
        this.ossClientManager = ossClientManager;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "oss";
    }

    @Override
    public StoredImage store(byte[] data, String mediaType) {
        String key = buildKey(ossConnection.getKeyPrefix(properties.getStorage().getOss().getKeyPrefix()), mediaType);
        PutObjectRequest request = buildRequest(ossConnection.getBucket(), key, mediaType, data);
        try {
            ossClientManager.get().putObject(request);
        } catch (BizException e) {
            throw e;                                            // OSS 未接入，原样透传
        } catch (Exception e) {
            // 不静默回落 base64（§20.7）：明确报错由用户重试或管理员切策略
            log.error("OSS 上传失败 key={}", key, e);
            throw new BizException("OSS 上传失败：" + e.getMessage());
        }
        return new StoredImage(name(), publicUrl(key, ossConnection.getCustomDomain(),
                ossConnection.getEndpoint(), ossConnection.getBucket(), ossConnection.getRegion()));
    }

    /** 按指定记录上传（§22.1：Agent feature.storage.storageRecord） */
    public StoredImage store(byte[] data, String mediaType, StorageConfigDO record) {
        String key = buildKey(record.getKeyPrefix() != null && !record.getKeyPrefix().isBlank()
                ? record.getKeyPrefix() : properties.getStorage().getOss().getKeyPrefix(), mediaType);
        PutObjectRequest request = buildRequest(record.getBucket(), key, mediaType, data);
        try {
            ossClientManager.getFor(record).putObject(request);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("OSS 上传失败 record={} key={}", record.getName(), key, e);
            throw new BizException("OSS 上传失败：" + e.getMessage());
        }
        return new StoredImage(name(), publicUrl(key, record.getCustomDomain(),
                record.getEndpoint(), record.getBucket(), record.getRegion()));
    }

    /** 头像上传（SPEC §23）：调用方指定完整 key（换头像带时间戳避免 CDN/浏览器缓存旧图） */
    public StoredImage storeAvatar(byte[] data, String mediaType, StorageConfigDO record, String key) {
        PutObjectRequest request = buildRequest(record.getBucket(), key, mediaType, data);
        try {
            ossClientManager.getFor(record).putObject(request);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("头像上传失败 record={} key={}", record.getName(), key, e);
            throw new BizException("头像上传失败：" + e.getMessage());
        }
        return new StoredImage(name(), publicUrl(key, record.getCustomDomain(),
                record.getEndpoint(), record.getBucket(), record.getRegion()));
    }

    private static PutObjectRequest buildRequest(String bucket, String key, String mediaType, byte[] data) {
        return PutObjectRequest.newBuilder()
                .bucket(bucket)
                .key(key)
                .objectAcl("public-read")                       // 对象级公开，bucket 保持私有（§20.8）
                .contentType(mediaType)
                .cacheControl("public, max-age=31536000")       // 一年缓存，图片不可变
                .body(BinaryData.fromBytes(data))
                .build();
    }

    /** key = {keyPrefix}{yyyyMMdd}/{uuid}.{ext}，UUID 不可枚举（§20.8） */
    private String buildKey(String prefixRaw, String mediaType) {
        String prefix = prefixRaw == null || prefixRaw.isBlank()
                ? properties.getStorage().getOss().getKeyPrefix() : prefixRaw;
        if (!prefix.endsWith("/")) {
            prefix += "/";
        }
        String ext = EXT.getOrDefault(mediaType, "bin");
        return prefix + LocalDate.now().format(DAY) + "/" + UUID.randomUUID() + "." + ext;
    }

    /** 公网直链：customDomain > endpoint（virtual-hosted）> 标准 region 域 */
    private String publicUrl(String key, String customDomain, String endpoint, String bucket, String region) {
        if (customDomain != null && !customDomain.isBlank()) {
            return stripTrailingSlash(customDomain) + "/" + key;
        }
        if (endpoint != null && !endpoint.isBlank()) {
            String host = endpoint.replaceFirst("^https?://", "");
            return "https://" + bucket + "." + stripTrailingSlash(host) + "/" + key;
        }
        return "https://" + bucket + ".oss-" + region + ".aliyuncs.com/" + key;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
