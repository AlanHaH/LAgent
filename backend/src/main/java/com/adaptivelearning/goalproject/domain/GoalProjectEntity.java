package com.adaptivelearning.goalproject.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@TableName("goal_project")
public class GoalProjectEntity {
    @TableId
    private Long goalId;
    private Long projectId;
    private BigDecimal contributionWeight;
}

