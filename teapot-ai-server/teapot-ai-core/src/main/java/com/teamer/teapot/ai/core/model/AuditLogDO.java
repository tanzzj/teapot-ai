package com.teamer.teapot.ai.core.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作审计（表 t_audit_log，SPEC §10.3）。
 */
@Data
public class AuditLogDO implements Serializable {

    private Long id;
    private String userId;
    /** 如 agent.create / skill.save / user.reset_password */
    private String action;
    private String target;
    /** JSON 摘要（脱敏） */
    private String detail;
    private LocalDateTime createdAt;
}
