package com.adaptivelearning.profile.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@TableName("learning_preference")
public class LearningPreferenceEntity extends BaseEntity {
    private Long userId;
    private String contentModesJson;
    private String guidanceStyle;
    private String taskGranularity;
    private Integer focusMinutes;
    private BigDecimal capacityRatio;
    private Integer difficultyMin;
    private Integer difficultyMax;
    private String reminderJson;
}

