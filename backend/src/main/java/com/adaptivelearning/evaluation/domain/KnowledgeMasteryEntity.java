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
@TableName("knowledge_mastery")
public class KnowledgeMasteryEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long knowledgePointId;
    private BigDecimal score;
    private BigDecimal confidence;
    private String level;
    private Integer evidenceCount;
    private Instant calculatedAt;
    private String calcVersion;
}
