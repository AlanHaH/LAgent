package com.adaptivelearning.support.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("sys_user")
public class UserEntity extends BaseEntity {
    private String publicId;
    private String username;
    private String email;
    private Instant emailVerifiedAt;
    private String passwordHash;
    private String status;
    private String timezone;
    private Integer loginFailedCount;
    private Instant lockedUntil;
    private Instant lastLoginAt;
}
