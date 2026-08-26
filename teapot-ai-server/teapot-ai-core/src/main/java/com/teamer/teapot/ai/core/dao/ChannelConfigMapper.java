package com.teamer.teapot.ai.core.dao;

import com.teamer.teapot.ai.core.model.ChannelConfigDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Channel 连接器记录 Mapper（SPEC §24.3）。
 */
@Mapper
public interface ChannelConfigMapper {

    List<ChannelConfigDO> selectAll();

    ChannelConfigDO selectByName(@Param("name") String name);

    void insert(ChannelConfigDO record);

    /** 按 name 更新；null 字段保持原值（appSecret 留空不修改） */
    void updateByName(ChannelConfigDO record);

    int deleteByName(@Param("name") String name);
}
