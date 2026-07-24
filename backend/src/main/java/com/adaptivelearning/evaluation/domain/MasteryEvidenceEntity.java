package com.adaptivelearning.evaluation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@TableName("mastery_evidence")
public class MasteryEvidenceEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long knowledgePointId;
    private String evidenceType;
    private Long sourceId;
    private BigDecimal score;
    private BigDecimal weight;
    private Instant occurredAt;
    private Boolean validFlag;
    private String calcVersion;
    private Instant createdAt;
}
