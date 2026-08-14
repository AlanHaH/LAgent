package com.adaptivelearning.planning.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

@Getter @Setter @TableName("learning_plan")
public class LearningPlanEntity extends BaseEntity {
    private String publicId;
    private Long userId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long goalId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectId;
    private String name;
    private String status;
}
