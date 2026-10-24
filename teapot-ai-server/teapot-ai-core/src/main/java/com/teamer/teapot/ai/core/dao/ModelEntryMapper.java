package com.teamer.teapot.ai.core.dao;

import com.teamer.teapot.ai.core.model.ModelEntryDO;
import com.teamer.teapot.ai.core.model.ModelWorkspaceGrantDO;
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

    /** 指定空间桶可用的启用入口：命中该桶或全局 0 桶的授权（SPEC §28.11） */
    List<ModelEntryDO> selectEnabledForWorkspace(@Param("workspaceId") Long workspaceId);

    ModelEntryDO selectById(@Param("id") Long id);

    ModelEntryDO selectByModelId(@Param("provider") String provider, @Param("modelName") String modelName);

    int insert(ModelEntryDO entry);

    int update(ModelEntryDO entry);

    int deleteById(@Param("id") Long id);

    /** 全部授权行（admin 列表批量回填 workspaceIds，SPEC §28.11） */
    List<ModelWorkspaceGrantDO> selectAllGrants();

    /** 某模型入口已授权的空间桶（含 0 全局桶），保存校验用 */
    List<Long> selectGrantedWorkspaceIds(@Param("modelEntryId") Long modelEntryId);

    int deleteGrants(@Param("modelEntryId") Long modelEntryId);

    int insertGrant(@Param("modelEntryId") Long modelEntryId, @Param("workspaceId") Long workspaceId,
                    @Param("creator") String creator);
}
