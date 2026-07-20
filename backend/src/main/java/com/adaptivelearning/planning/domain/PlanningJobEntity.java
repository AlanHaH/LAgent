package com.adaptivelearning.planning.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter @Setter @TableName("planning_job")
public class PlanningJobEntity extends BaseEntity {
    private String publicId;
    private Long userId;
    private Long goalId;
    private String jobType;
    private String status;
    private String idempotencyKey;
    private String requestHash;
    private Long planVersionId;
    private String errorCode;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
}

