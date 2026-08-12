package com.teamer.teapot.ai.core.config;

import com.teamer.teapot.ai.core.model.AuditLogDO;
import com.teamer.teapot.ai.core.dao.AuditLogMapper;
import com.teamer.teapot.ai.rbac.context.ContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 审计埋点（SPEC §10.3/§14.6）：Agent/Skill/User 写操作落 t_audit_log + 应用日志。
 */
@Slf4j
@Component
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    public AuditService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    public void log(String action, String target, String detail) {
        String userId = ContextUtil.currentUserId();
        if (userId == null) {
            userId = "system";
        }
        log.info("AUDIT user={} action={} target={} detail={}", userId, action, target, detail);
        try {
            AuditLogDO logDO = new AuditLogDO();
            logDO.setUserId(userId);
            logDO.setAction(action);
            logDO.setTarget(target);
            logDO.setDetail(detail);
            auditLogMapper.insert(logDO);
        } catch (Exception e) {
            // 审计失败不阻塞业务
            log.warn("审计写入失败 action={} target={}", action, target, e);
        }
    }
}
