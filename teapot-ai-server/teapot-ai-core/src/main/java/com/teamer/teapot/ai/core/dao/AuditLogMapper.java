package com.teamer.teapot.ai.core.dao;

import com.teamer.teapot.ai.core.model.AuditLogDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * t_audit_log 数据访问（SQL 见 resources/sqlclient/AuditLogMapper.xml）。
 */
@Mapper
public interface AuditLogMapper {

    int insert(AuditLogDO log);
}
