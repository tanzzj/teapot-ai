package com.teamer.teapot.ai.core.storage;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import com.teamer.teapot.ai.common.exception.BizException;
import com.teamer.teapot.ai.core.config.OssConnection;
import com.teamer.teapot.ai.core.model.StorageConfigDO;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * OSSClient 管理（SPEC §20.3/§22.1）：SDK 客户端 AutoCloseable，按配置指纹缓存；
 * 全局链路（旧）单实例指纹缓存；记录链路（§22.1 按 Agent 选记录）按记录名缓存。
 */
@Slf4j
@Component
public class OssClientManager {

    private final OssConnection ossConnection;
    private volatile OSSClient client;
    private volatile String fingerprint;
    /** 记录级客户端缓存：name → [client, fingerprint]（§22.1） */
    private final Map<String, OSSClient> recordClients = new HashMap<>();
    private final Map<String, String> recordFingerprints = new HashMap<>();

    public OssClientManager(OssConnection ossConnection) {
        this.ossConnection = ossConnection;
    }

    /** 取（必要时重建）OSSClient；凭证不齐时抛业务异常（上传端点转明确错误，§20.7 不静默回落） */
    public synchronized OSSClient get() {
        if (!ossConnection.configured()) {
            throw new BizException("OSS 未接入：请先在系统配置-存储中填写 AK/Secret/Region/Bucket");
        }
        String fp = String.join("|",
                nullToEmpty(ossConnection.getAccessKeyId()),
                nullToEmpty(ossConnection.getAccessKeySecret()),
                nullToEmpty(ossConnection.getRegion()),
                nullToEmpty(ossConnection.getBucket()),
                nullToEmpty(ossConnection.getEndpoint()),
                nullToEmpty(ossConnection.getCustomDomain()));
        if (client == null || !fp.equals(fingerprint)) {
            closeQuietly(client);
            client = build();
            fingerprint = fp;
            log.info("OSSClient 已构建/重建 region={} bucket={} cName={}",
                    ossConnection.getRegion(), ossConnection.getBucket(),
                    ossConnection.getCustomDomain() != null);
        }
        return client;
    }

    private OSSClient build() {
        var builder = OSSClient.newBuilder()
                .credentialsProvider(new StaticCredentialsProvider(
                        ossConnection.getAccessKeyId(), ossConnection.getAccessKeySecret()))
                .region(ossConnection.getRegion());
        String customDomain = ossConnection.getCustomDomain();
        if (customDomain != null && !customDomain.isBlank()) {
            // 自定义域名（CNAME）：2025-03-20 起内地新 bucket 数据面必须走自定义域名（§20.8）
            builder.endpoint(customDomain).useCName(true);
        } else if (ossConnection.getEndpoint() != null && !ossConnection.getEndpoint().isBlank()) {
            builder.endpoint(ossConnection.getEndpoint());
        }
        return builder.build();
    }

    /** 按记录取（必要时重建）OSSClient（§22.1）；凭证不齐抛业务异常 */
    public synchronized OSSClient getFor(StorageConfigDO record) {
        if (record == null || isBlank(record.getAccessKeyId()) || isBlank(record.getAccessKeySecret())
                || isBlank(record.getRegion()) || isBlank(record.getBucket())) {
            throw new BizException("OSS 记录凭证不齐：" + (record == null ? "null" : record.getName()));
        }
        String fp = String.join("|",
                nullToEmpty(record.getAccessKeyId()),
                nullToEmpty(record.getAccessKeySecret()),
                nullToEmpty(record.getRegion()),
                nullToEmpty(record.getBucket()),
                nullToEmpty(record.getEndpoint()),
                nullToEmpty(record.getCustomDomain()));
        OSSClient cached = recordClients.get(record.getName());
        if (cached == null || !fp.equals(recordFingerprints.get(record.getName()))) {
            closeQuietly(cached);
            cached = buildFor(record);
            recordClients.put(record.getName(), cached);
            recordFingerprints.put(record.getName(), fp);
            log.info("OSSClient(记录) 已构建/重建 record={} region={} bucket={} cName={}",
                    record.getName(), record.getRegion(), record.getBucket(),
                    record.getCustomDomain() != null);
        }
        return cached;
    }

    private OSSClient buildFor(StorageConfigDO record) {
        var builder = OSSClient.newBuilder()
                .credentialsProvider(new StaticCredentialsProvider(
                        record.getAccessKeyId(), record.getAccessKeySecret()))
                .region(record.getRegion());
        if (!isBlank(record.getCustomDomain())) {
            builder.endpoint(record.getCustomDomain()).useCName(true);
        } else if (!isBlank(record.getEndpoint())) {
            builder.endpoint(record.getEndpoint());
        }
        return builder.build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @PreDestroy
    public void close() {
        closeQuietly(client);
        recordClients.values().forEach(OssClientManager::closeQuietly);
        recordClients.clear();
        recordFingerprints.clear();
    }

    private static void closeQuietly(OSSClient c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                log.warn("关闭 OSSClient 失败", e);
            }
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
