package com.teamer.teapot.ai.core.dao;

import com.teamer.teapot.ai.core.model.WorkspaceDO;
import com.teamer.teapot.ai.core.model.WorkspaceMemberDO;
import com.teamer.teapot.ai.core.model.vo.WorkspaceMemberVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * teapot_workspace / teapot_workspace_member 数据访问（SQL 见 resources/sqlclient/WorkspaceMapper.xml，SPEC §28）。
 */
@Mapper
public interface WorkspaceMapper {

    WorkspaceDO selectById(@Param("id") Long id);

    WorkspaceDO selectByCode(@Param("code") String code);

    /** 当前用户作为成员所属的全部启用空间（join member，SPEC §28.3） */
    List<WorkspaceDO> selectByUser(@Param("userId") String userId);

    /** 白名单用户可见全部启用空间 */
    List<WorkspaceDO> selectAllEnabled();

    int insert(WorkspaceDO workspace);

    int update(WorkspaceDO workspace);

    int softDelete(@Param("id") Long id);

    /** 成员存在性（>0 即成员，SPEC §28.3 requireMember） */
    long countMember(@Param("workspaceId") Long workspaceId, @Param("userId") String userId);

    /** 该空间成员角色（owner/member），非成员返回 null */
    String selectMemberRole(@Param("workspaceId") Long workspaceId, @Param("userId") String userId);

    List<WorkspaceMemberVO> selectMembersByWorkspace(@Param("workspaceId") Long workspaceId);

    int insertMember(WorkspaceMemberDO member);

    int deleteMember(@Param("workspaceId") Long workspaceId, @Param("userId") String userId);
}
