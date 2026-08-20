package com.teamer.teapot.ai.core.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 沙箱连接记录（表 t_sandbox_config，SPEC §22.2 多记录）。
 * linkType=e2b 消费 e2b* 字段；linkType=agentrun 消费 apiKey/accountId/region/defaultTemplate/mcpServerUrl。
 * DB 中敏感列为 AES-GCM 密文；Service 层解密后回填明文供运行期使用。
 */
@Data
public class SandboxConfigDO implements Serializable {

    private Long id;
    /** 记录名（唯一标识，Agent feature.sandbox.sandboxRecord 引用） */
    private String name;
    /** e2b | agentrun */
    private String linkType;
    private String e2bApiKey;
    private String e2bApiBaseUrl;
    private String e2bDomain;
    private String e2bDefaultTemplate;
    /** AgentRun API Key */
    private String apiKey;
    /** 阿里云账号 ID */
    private String accountId;
    private String region;
    private String defaultTemplate;
    private String mcpServerUrl;
    private String remark;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
