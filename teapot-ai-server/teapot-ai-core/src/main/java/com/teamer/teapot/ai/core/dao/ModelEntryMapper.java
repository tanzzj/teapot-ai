package com.teamer.teapot.ai.core.dao;

import com.teamer.teapot.ai.core.model.ModelEntryDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模型入口配置 Mapper（SPEC §6.4 修订）。
 */
@Mapper
public interface ModelEntryMapper {

    List<ModelEntryDO> selectAll();

    List<ModelEntryDO> selectAllEnabled();

    ModelEntryDO selectById(@Param("id") Long id);

    ModelEntryDO selectByModelId(@Param("provider") String provider, @Param("modelName") String modelName);

    int insert(ModelEntryDO entry);

    int update(ModelEntryDO entry);

    int deleteById(@Param("id") Long id);
}
