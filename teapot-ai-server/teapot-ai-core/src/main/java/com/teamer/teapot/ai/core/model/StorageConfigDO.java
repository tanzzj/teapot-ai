package com.teamer.teapot.ai.core.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * OSS 存储连接记录（表 t_storage_config，SPEC §20.12 多记录）。
 * DB 中 AK/Secret 为 AES-GCM 密文；Service 层解密后回填明文供运行期使用。
 */
@Data
public class StorageConfigDO implements Serializable {

    private Long id;
    /** 记录名（唯一标识） */
    private String name;
    private String accessKeyId;
    private String accessKeySecret;
    private String region;
    private String bucket;
    private String endpoint;
    private String customDomain;
    private String keyPrefix;
    private String remark;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
