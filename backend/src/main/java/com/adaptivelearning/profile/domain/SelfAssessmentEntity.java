package com.adaptivelearning.profile.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@TableName("self_assessment")
public class SelfAssessmentEntity extends BaseEntity {
    private Long userId;
    private Long knowledgePointId;
    private Integer level;
    private Instant assessedAt;
    private LocalDate lastStudiedAt;
    private String note;
}

