package com.adaptivelearning.support.infrastructure;

import com.adaptivelearning.support.domain.UserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.Set;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    @Select("SELECT * FROM sys_user WHERE id = #{userId} AND deleted_at IS NULL FOR UPDATE")
    UserEntity lockById(@Param("userId") long userId);

    @Select("""
            SELECT r.code FROM sys_role r
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId} AND r.status = 'ACTIVE'
            """)
    Set<String> findRoleCodes(@Param("userId") long userId);

    @Select("""
            SELECT DISTINCT p.code FROM sys_permission p
            JOIN sys_role_permission rp ON rp.permission_id = p.id
            JOIN sys_role r ON r.id = rp.role_id AND r.status = 'ACTIVE'
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            """)
    Set<String> findPermissionCodes(@Param("userId") long userId);

    @Update("""
            UPDATE sys_user
            SET locked_until = CASE
                    WHEN login_failed_count + 1 >= #{maxFailures} THEN #{lockedUntil}
                    ELSE NULL
                END,
                login_failed_count = CASE
                    WHEN login_failed_count + 1 >= #{maxFailures} THEN 0
                    ELSE login_failed_count + 1
                END,
                updated_at = #{now}, updated_by = id, version = version + 1
            WHERE id = #{userId} AND status = 'ACTIVE' AND deleted_at IS NULL
            """)
    int recordLoginFailure(@Param("userId") long userId,
                           @Param("maxFailures") int maxFailures,
                           @Param("lockedUntil") Instant lockedUntil,
                           @Param("now") Instant now);

    @Update("""
            UPDATE sys_user
            SET login_failed_count = 0, locked_until = NULL, last_login_at = #{now},
                updated_at = #{now}, updated_by = id, version = version + 1
            WHERE id = #{userId} AND status = 'ACTIVE' AND deleted_at IS NULL
            """)
    int recordLoginSuccess(@Param("userId") long userId, @Param("now") Instant now);

    @Update("""
            UPDATE sys_user
            SET password_hash = #{passwordHash}, login_failed_count = 0, locked_until = NULL,
                updated_at = #{now}, updated_by = id, version = version + 1
            WHERE id = #{userId} AND version = #{version} AND deleted_at IS NULL
            """)
    int resetPasswordAndLoginLock(@Param("userId") long userId,
                                  @Param("version") int version,
                                  @Param("passwordHash") String passwordHash,
                                  @Param("now") Instant now);

    @Select("SELECT id FROM sys_role WHERE code = #{code} AND status = 'ACTIVE'")
    Long findRoleId(@Param("code") String code);

    @org.apache.ibatis.annotations.Insert("INSERT INTO sys_user_role(user_id, role_id) VALUES(#{userId}, #{roleId})")
    int addRole(@Param("userId") long userId, @Param("roleId") long roleId);
}
