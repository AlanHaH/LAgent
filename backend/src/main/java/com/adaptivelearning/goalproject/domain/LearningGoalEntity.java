package com.adaptivelearning.goalproject.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@TableName("learning_goal")
public class LearningGoalEntity extends BaseEntity {
    private String publicId;
    private Long userId;
    private Long directionId;
    private String customDirection;
    private Long sourceGoalId;
    private String sourceType;
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
