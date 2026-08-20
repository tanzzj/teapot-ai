package com.teamer.teapot.ai.core.config;

import com.teamer.teapot.ai.core.model.StorageConfigDO;
import com.teamer.teapot.ai.core.service.StorageConfigService;
import com.teamer.teapot.ai.core.service.SysConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OSS 图片存储全局配置（SPEC §20.4/§20.12）：env 优先、激活记录（t_storage_config）次之、
 * 旧单键（t_sys_config oss.*）兜底。{@link #configured()} = AK/Secret/region/bucket 四项齐备。
 */
@Slf4j
@Component
public class OssConnection {

    /** DB 配置 key（SPEC §20.4/§20.12） */
    public static final String KEY_STRATEGY = "storage.image.strategy";
    /** 激活记录名（§20.12 多记录）：指向 t_storage_config.name */
    public static final String KEY_ACTIVE = "storage.image.active";
    public static final String KEY_ACCESS_KEY_ID = "oss.access_key_id";
    public static final String KEY_ACCESS_KEY_SECRET = "oss.access_key_secret";
    public static final String KEY_REGION = "oss.region";
    public static final String KEY_BUCKET = "oss.bucket";
    public static final String KEY_ENDPOINT = "oss.endpoint";
    public static final String KEY_CUSTOM_DOMAIN = "oss.custom_domain";
    public static final String KEY_KEY_PREFIX = "oss.key_prefix";

    private final SysConfigService sysConfigService;
    private final StorageConfigService storageConfigService;
    /** 应急覆盖通道：env 存在时优先于 DB（同 AgentRunConnection，SPEC §16.5） */
    private final String envStrategy;
    private final String envAccessKeyId;
    private final String envAccessKeySecret;
    private final String envRegion;
    private final String envBucket;
    private final String envEndpoint;
    private final String envCustomDomain;

    public OssConnection(SysConfigService sysConfigService,
                         StorageConfigService storageConfigService,
                         @Value("${STORAGE_IMAGE_STRATEGY:}") String envStrategy,
                         @Value("${OSS_ACCESS_KEY_ID:}") String envAccessKeyId,
                         @Value("${OSS_ACCESS_KEY_SECRET:}") String envAccessKeySecret,
                         @Value("${OSS_REGION:}") String envRegion,
                         @Value("${OSS_BUCKET:}") String envBucket,
                         @Value("${OSS_ENDPOINT:}") String envEndpoint,
                         @Value("${OSS_CUSTOM_DOMAIN:}") String envCustomDomain) {
        this.sysConfigService = sysConfigService;
        this.storageConfigService = storageConfigService;
        this.envStrategy = envStrategy;
        this.envAccessKeyId = envAccessKeyId;
        this.envAccessKeySecret = envAccessKeySecret;
        this.envRegion = envRegion;
        this.envBucket = envBucket;
        this.envEndpoint = envEndpoint;
        this.envCustomDomain = envCustomDomain;
    }

    /** 策略：base64（默认）/ oss */
    public String getStrategy() {
        String v = resolve(envStrategy, KEY_STRATEGY);
        return v == null ? "base64" : v;
    }

    /** 激活记录名（§20.12）；未设置返回 null */
    public String getActiveName() {
        try {
            String v = sysConfigService.getPlain(KEY_ACTIVE);
            return notBlank(v) ? v.trim() : null;
        } catch (Exception e) {
            log.warn("读取激活存储记录名失败", e);
            return null;
        }
    }

    public String getAccessKeyId() {
        return resolveRecord(envAccessKeyId, KEY_ACCESS_KEY_ID, StorageConfigDO::getAccessKeyId);
    }

    public String getAccessKeySecret() {
        return resolveRecord(envAccessKeySecret, KEY_ACCESS_KEY_SECRET, StorageConfigDO::getAccessKeySecret);
    }

    public String getRegion() {
        return resolveRecord(envRegion, KEY_REGION, StorageConfigDO::getRegion);
    }

    public String getBucket() {
        return resolveRecord(envBucket, KEY_BUCKET, StorageConfigDO::getBucket);
    }

    public String getEndpoint() {
        return resolveRecord(envEndpoint, KEY_ENDPOINT, StorageConfigDO::getEndpoint);
    }

    /** 自定义域名（含 https://）：作 URL 前缀 + SDK useCName（§20.8 内地新 bucket 合规） */
    public String getCustomDomain() {
        return resolveRecord(envCustomDomain, KEY_CUSTOM_DOMAIN, StorageConfigDO::getCustomDomain);
    }

    public String getKeyPrefix(String fallback) {
        String v = resolveRecord(null, KEY_KEY_PREFIX, StorageConfigDO::getKeyPrefix);
        return v == null ? fallback : v;
    }

    /** AK/Secret/region/bucket 四项齐备（env > 激活记录 > 旧单键合并后） */
    public boolean configured() {
        return notBlank(getAccessKeyId()) && notBlank(getAccessKeySecret())
                && notBlank(getRegion()) && notBlank(getBucket());
    }

    /** 三级解析（§20.12）：env 应急覆盖 > 激活记录 > 旧 t_sys_config 单键（向后兼容） */
    private String resolveRecord(String envValue, String legacyKey,
                                 java.util.function.Function<StorageConfigDO, String> getter) {
        if (notBlank(envValue)) {
            return envValue.trim();
        }
        try {
            StorageConfigDO active = storageConfigService.getActivePlain();
            if (active != null) {
                String v = getter.apply(active);
                if (notBlank(v)) {
                    return v.trim();
                }
            }
        } catch (Exception e) {
            log.warn("读取激活存储记录失败", e);
        }
        return resolve(null, legacyKey);
    }

    private String resolve(String envValue, String dbKey) {
        if (notBlank(envValue)) {
            return envValue.trim();
        }
        try {
            String dbValue = sysConfigService.getPlain(dbKey);
            return notBlank(dbValue) ? dbValue.trim() : null;
        } catch (Exception e) {
            // 解密失败（主密钥变更等）不拖垮上传链路，按未配置处理
            log.warn("读取系统配置失败 key={}", dbKey, e);
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
