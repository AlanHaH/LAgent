package com.adaptivelearning.support.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("refresh_token")
public class RefreshTokenEntity extends BaseEntity {
    private Long userId;
    private String tokenHash;
    private String deviceId;
    private Instant expiresAt;
    private Instant revokedAt;
    private Long rotatedToId;
}

