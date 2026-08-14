package com.adaptivelearning.planning.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

@Getter @Setter @TableName("planning_job")
public class PlanningJobEntity extends BaseEntity {
    private String publicId;
    private Long userId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long goalId;
    private String jobType;
    private String status;
    private String idempotencyKey;
    private String requestHash;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planVersionId;
    private String errorCode;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
}
