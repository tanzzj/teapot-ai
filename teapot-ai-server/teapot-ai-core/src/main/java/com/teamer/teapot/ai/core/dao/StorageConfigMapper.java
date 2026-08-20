package com.teamer.teapot.ai.core.dao;

import com.teamer.teapot.ai.core.model.StorageConfigDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * OSS 存储连接记录 Mapper（SPEC §20.12）。
 */
@Mapper
public interface StorageConfigMapper {

    List<StorageConfigDO> selectAll();

    StorageConfigDO selectByName(@Param("name") String name);

    void insert(StorageConfigDO record);

    /** 按 name 更新；AK/Secret 字段为 null 时保持原值（留空不修改） */
    void updateByName(StorageConfigDO record);

    int deleteByName(@Param("name") String name);
}
