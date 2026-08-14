package com.adaptivelearning.goalproject.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDate;

@Getter
@Setter
@TableName("learning_goal")
public class LearningGoalEntity extends BaseEntity {
    private String publicId;
    private Long userId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long directionId;
    private String customDirection;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceGoalId;
    private String sourceType;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long profileVersionId;
    private String recommendationSnapshotJson;
    private String name;
    private String type;
    private String description;
    private String priority;
    private LocalDate startDate;
    private LocalDate dueDate;
    private Integer weeklyBudgetMinutes;
    private String status;
    private String successCriteriaJson;
    private String acceptanceSnapshotJson;
}
