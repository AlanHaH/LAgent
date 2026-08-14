package com.adaptivelearning.execution.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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
    @JsonSerialize(using = ToStringSerializer.class)
    private Long goalId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long milestoneId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long originPlanVersionId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long learningBlockId;
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
    @JsonIgnore
    private String acceptanceJson;
}
