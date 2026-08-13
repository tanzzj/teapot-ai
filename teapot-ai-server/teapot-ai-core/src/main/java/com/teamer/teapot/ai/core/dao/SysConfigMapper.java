package com.teamer.teapot.ai.core.dao;

import com.teamer.teapot.ai.core.model.SysConfigDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 系统配置 Mapper（SPEC §16.5.1）。
 */
@Mapper
public interface SysConfigMapper {

    SysConfigDO selectByKey(@Param("configKey") String configKey);

    /** 存在则更新值/版本/密文标记/更新人，不存在则插入 */
    void upsert(@Param("configKey") String configKey,
                @Param("configValue") String configValue,
                @Param("keyVersion") int keyVersion,
                @Param("encrypted") int encrypted,
                @Param("updatedBy") String updatedBy);
}
