package com.teamer.teapot.ai.core.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统配置（表 t_sys_config，SPEC §16.5.1）。
 * 敏感项 config_value 存 AES-GCM 密文 v<keyVer>:<base64(iv+ciphertext+tag)>。
 */
@Data
public class SysConfigDO implements Serializable {

    private Long id;
    private String configKey;
    private String configValue;
    private Integer keyVersion;
    /** 1 密文 0 明文 */
    private Integer encrypted;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
