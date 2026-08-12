package com.teamer.teapot.ai.rbac.dao;

import com.teamer.teapot.ai.rbac.model.TeapotUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * t_user 数据访问（SQL 见 resources/sqlclient/UserMapper.xml）。
 */
@Mapper
public interface UserMapper {

    TeapotUser selectByUsername(@Param("username") String username);

    TeapotUser selectByUserId(@Param("userId") String userId);

    List<TeapotUser> selectPage(@Param("offset") int offset, @Param("size") int size);

    long countAll();

    int insert(TeapotUser user);

    int update(TeapotUser user);
}
