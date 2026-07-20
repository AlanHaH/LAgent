package com.adaptivelearning.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("profile_generation_job")
public class ProfileGenerationJobEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String publicId;
    private Long userId;
    private String status;
    private Long profileVersionId;
    private String errorCode;
    private Instant createdAt;
    private Instant finishedAt;
}

