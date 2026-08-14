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
@TableName("learning_project")
public class LearningProjectEntity extends BaseEntity {
    private String publicId;
    private Long userId;
    @JsonSerialize(using = ToStringSerializer.class)
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
