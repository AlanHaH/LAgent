package com.adaptivelearning.execution.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@TableName("learning_task")
public class LearningTaskEntity extends BaseEntity {
    private String publicId;
    private Long userId;
    private Long goalId;
    private Long projectId;
    private Long milestoneId;
    private Long originPlanVersionId;
    private String title;
    private String description;
    private String taskType;
    private String priority;
    private Integer estimatedMinutes;
    private Instant scheduledStart;
    private Instant dueAt;
    private Boolean lockedSchedule;
    private String lifecycleStatus;
    private BigDecimal progressPercent;
    private Instant completedAt;
    private Integer rescheduleCount;
    private String acceptanceJson;
}

