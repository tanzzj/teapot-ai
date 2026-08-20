package com.teamer.teapot.ai.core.storage;

import com.teamer.teapot.ai.core.config.OssConnection;
import com.teamer.teapot.ai.core.config.TeapotAiProperties;
import com.teamer.teapot.ai.core.dao.AgentMapper;
import com.teamer.teapot.ai.core.model.AgentDO;
import com.teamer.teapot.ai.core.model.AgentFeature;
import com.teamer.teapot.ai.core.model.StorageConfigDO;
import com.teamer.teapot.ai.core.service.StorageConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 图片存储路由（SPEC §20.3/§22.1）：
 * 按 Agent 解析生效载体——feature.storage.mode=oss 且引用记录凭证齐备 → oss，否则 base64；
 * Agent 未配置 storage 命名空间（存量）→ 回落全局策略（yml 开关 ∧ t_sys_config strategy ∧ 凭证齐备）。
 * 回落语义同 AgentRegistry 双链路模式——条件不满足时回落 base64 并 warn。
 */
@Slf4j
@Service
public class ImageStorageRouter {

    private final TeapotAiProperties properties;
    private final OssConnection ossConnection;
    private final InlineBase64StorageStrategy base64Strategy;
    private final OssImageStorageStrategy ossStrategy;
    private final AgentMapper agentMapper;
    private final StorageConfigService storageConfigService;

    public ImageStorageRouter(TeapotAiProperties properties,
                              OssConnection ossConnection,
                              InlineBase64StorageStrategy base64Strategy,
                              OssImageStorageStrategy ossStrategy,
                              AgentMapper agentMapper,
                              StorageConfigService storageConfigService) {
        this.properties = properties;
        this.ossConnection = ossConnection;
        this.base64Strategy = base64Strategy;
        this.ossStrategy = ossStrategy;
        this.agentMapper = agentMapper;
        this.storageConfigService = storageConfigService;
    }

    /** 配置策略（管理员所配，可能因条件不满足而未生效；全局存量语义） */
    public String configuredStrategy() {
        return "oss".equalsIgnoreCase(ossConnection.getStrategy()) ? "oss" : "base64";
    }

    /** 全局生效策略：前端存量上传链路据此选择（§20.7） */
    public String effectiveStrategy() {
        if ("oss".equals(configuredStrategy())) {
            if (!properties.getStorage().getOss().isEnabled()) {
                log.warn("OSS 策略已配置但 yml 开关关闭（teapot.ai.storage.oss.enabled=false），回落 base64");
                return "base64";
            }
            if (!ossConnection.configured()) {
                log.warn("OSS 策略已配置但凭证不齐（需 AK/Secret/Region/Bucket），回落 base64");
                return "base64";
            }
            return "oss";
        }
        return "base64";
    }

    /**
     * 按 Agent 解析生效载体（§22.1）：feature.storage 存在 → mode=oss 且记录可用才 oss；
     * 未配置 storage 命名空间 → 回落全局策略（存量兼容）。
     */
    public String effectiveStrategy(String agentKey) {
        AgentFeature.Storage st = agentStorage(agentKey);
        if (st == null) {
            return effectiveStrategy();
        }
        if ("oss".equals(st.getMode())) {
            StorageConfigDO record = resolveOssRecord(st.getStorageRecord());
            if (record != null) {
                return "oss";
            }
            log.warn("Agent 选择 OSS 但记录不可用 agentKey={} record={}，回落 base64",
                    agentKey, st.getStorageRecord());
        }
        return "base64";
    }

    /** 按生效策略存储（存量入口，等价 agentKey=null） */
    public StoredImage store(byte[] data, String mediaType) {
        return store(data, mediaType, null);
    }

    /** 按 Agent 生效载体存储；OSS 运行期上传失败不静默回落（§20.7，异常由调用方上抛） */
    public StoredImage store(byte[] data, String mediaType, String agentKey) {
        AgentFeature.Storage st = agentStorage(agentKey);
        if (st != null) {
            if ("oss".equals(st.getMode())) {
                StorageConfigDO record = resolveOssRecord(st.getStorageRecord());
                if (record != null) {
                    return ossStrategy.store(data, mediaType, record);
                }
                log.warn("Agent 选择 OSS 但记录不可用 agentKey={} record={}，回落 base64",
                        agentKey, st.getStorageRecord());
            }
            return base64Strategy.store(data, mediaType);
        }
        // 存量：未配置 storage 命名空间，回落全局策略
        if ("oss".equals(effectiveStrategy())) {
            return ossStrategy.store(data, mediaType);
        }
        return base64Strategy.store(data, mediaType);
    }

    /** Agent feature.storage 命名空间；agentKey 空/Agent 不存在/未配置返回 null */
    private AgentFeature.Storage agentStorage(String agentKey) {
        if (agentKey == null || agentKey.isBlank()) {
            return null;
        }
        try {
            AgentDO agent = agentMapper.selectByAgentKey(agentKey);
            if (agent == null) {
                return null;
            }
            return AgentFeature.parse(agent.getFeature()).getStorage();
        } catch (Exception e) {
            log.warn("解析 Agent 存储配置失败 agentKey={}", agentKey, e);
            return null;
        }
    }

    /** 记录存在 + 凭证齐备 + yml 开关开启才可用；否则返回 null */
    private StorageConfigDO resolveOssRecord(String recordName) {
        if (!properties.getStorage().getOss().isEnabled()) {
            log.warn("OSS yml 开关关闭（teapot.ai.storage.oss.enabled=false），Agent 级 OSS 选择不生效");
            return null;
        }
        StorageConfigDO record = storageConfigService.getPlain(recordName == null ? "" : recordName.trim());
        if (record == null) {
            return null;
        }
        if (isBlank(record.getAccessKeyId()) || isBlank(record.getAccessKeySecret())
                || isBlank(record.getRegion()) || isBlank(record.getBucket())) {
            log.warn("OSS 记录凭证不齐 record={}", record.getName());
            return null;
        }
        return record;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
