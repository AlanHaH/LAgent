package com.adaptivelearning.support.infrastructure;

import com.adaptivelearning.support.domain.RefreshTokenEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshTokenEntity> {
    @Update("""
            UPDATE refresh_token
            SET revoked_at=#{revokedAt}, updated_at=#{revokedAt}, updated_by=user_id, version=version+1
            WHERE id=#{id} AND version=#{version} AND revoked_at IS NULL AND deleted_at IS NULL
            """)
    int revokeIfActive(@Param("id") long id, @Param("version") int version,
                       @Param("revokedAt") Instant revokedAt);

    @Update("""
            UPDATE refresh_token
            SET rotated_to_id=#{replacementId}, updated_at=UTC_TIMESTAMP(6), updated_by=user_id, version=version+1
            WHERE id=#{id} AND version=#{version} AND revoked_at IS NOT NULL
              AND rotated_to_id IS NULL AND deleted_at IS NULL
            """)
    int linkReplacement(@Param("id") long id, @Param("version") int version,
                        @Param("replacementId") long replacementId);
}
