package com.adaptivelearning.profile.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@TableName("availability_exception")
public class AvailabilityExceptionEntity extends BaseEntity {
    private Long userId;
    private LocalDate localDate;
    private Integer availableMinutes;
    private String reason;
}

