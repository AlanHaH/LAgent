package com.adaptivelearning.goalproject.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@TableName("learning_project")
public class LearningProjectEntity extends BaseEntity {
    private String publicId;
    private Long userId;
    private Long primaryDirectionId;
    private String name;
    private String description;
    private LocalDate startDate;
    private LocalDate dueDate;
    private String priority;
    private String status;
    private String deliverableJson;
    private String repositoryUrl;
}

