package com.adaptivelearning.support.infrastructure;

import com.adaptivelearning.support.domain.UserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Set;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    @Select("""
            SELECT r.code FROM sys_role r
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId} AND r.status = 'ACTIVE'
            """)
    Set<String> findRoleCodes(@Param("userId") long userId);

    @Select("""
            SELECT DISTINCT p.code FROM sys_permission p
            JOIN sys_role_permission rp ON rp.permission_id = p.id
            JOIN sys_user_role ur ON ur.role_id = rp.role_id
            WHERE ur.user_id = #{userId}
            """)
    Set<String> findPermissionCodes(@Param("userId") long userId);

    @Select("SELECT id FROM sys_role WHERE code = #{code} AND status = 'ACTIVE'")
    Long findRoleId(@Param("code") String code);

    @org.apache.ibatis.annotations.Insert("INSERT INTO sys_user_role(user_id, role_id) VALUES(#{userId}, #{roleId})")
    int addRole(@Param("userId") long userId, @Param("roleId") long roleId);
}

