package com.adaptivelearning.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

@Getter
@Setter
@TableName("profile_generation_job")
public class ProfileGenerationJobEntity {
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String publicId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;
    private String status;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long profileVersionId;
    private String errorCode;
    private Instant createdAt;
    private Instant finishedAt;
}
