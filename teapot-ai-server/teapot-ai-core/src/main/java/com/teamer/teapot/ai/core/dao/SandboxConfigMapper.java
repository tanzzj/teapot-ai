package com.teamer.teapot.ai.core.dao;

import com.teamer.teapot.ai.core.model.SandboxConfigDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 沙箱连接记录 Mapper（SPEC §22.2）。
 */
@Mapper
public interface SandboxConfigMapper {

    List<SandboxConfigDO> selectAll();

    SandboxConfigDO selectByName(@Param("name") String name);

    void insert(SandboxConfigDO record);

    /** 按 name 更新；null 字段保持原值（敏感列留空不修改） */
    void updateByName(SandboxConfigDO record);

    int deleteByName(@Param("name") String name);
}
