package com.adaptivelearning.goalproject.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@TableName("milestone")
public class MilestoneEntity extends BaseEntity {
    private String publicId;
    private Long projectId;
    private String name;
    private Integer sequenceNo;
    private LocalDate dueDate;
    private BigDecimal weight;
    private String status;
    private String acceptanceJson;
    private String completionEvidenceJson;
    private Instant completedAt;
}

