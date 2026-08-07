package com.adaptivelearning.evaluation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("wrong_question")
public class WrongQuestionEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long questionVersionId;
    private Long knowledgePointId;
    private Instant firstWrongAt;
    private Instant lastWrongAt;
    private Integer wrongCount;
    private String aiReasonCode;
    private String confirmedReasonCode;
    private String status;
    private Instant correctedAt;
}
