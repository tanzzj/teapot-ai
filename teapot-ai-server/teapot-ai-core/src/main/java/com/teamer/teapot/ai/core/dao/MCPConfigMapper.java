package com.teamer.teapot.ai.core.dao;

import com.teamer.teapot.ai.core.model.MCPConfigDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MCP Server 配置 Mapper。
 */
@Mapper
public interface MCPConfigMapper {

    List<MCPConfigDO> selectAll();

    MCPConfigDO selectByName(@Param("name") String name);

    void insert(MCPConfigDO record);

    /** 按 name 更新；null 字段保持原值 */
    void updateByName(MCPConfigDO record);

    int deleteByName(@Param("name") String name);

    /** 切换启用/禁用 */
    int toggleEnabled(@Param("name") String name, @Param("enabled") Boolean enabled, @Param("updatedBy") String updatedBy);
}
