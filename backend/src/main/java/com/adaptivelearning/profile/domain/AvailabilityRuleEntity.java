package com.adaptivelearning.profile.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;

@Getter
@Setter
@TableName("availability_rule")
public class AvailabilityRuleEntity extends BaseEntity {
    private Long userId;
    private Integer weekday;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer availableMinutes;
    private String energyLevel;
}

