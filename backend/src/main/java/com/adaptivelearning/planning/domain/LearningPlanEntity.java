package com.adaptivelearning.planning.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @TableName("learning_plan")
public class LearningPlanEntity extends BaseEntity {
    private String publicId;
    private Long userId;
    private Long goalId;
    private Long projectId;
    private String name;
    private String status;
}

