package com.adaptivelearning.evaluation.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@TableName("attempt_answer")
public class AttemptAnswerEntity extends BaseEntity {
    private String publicId;
    private Long attemptId;
    private Integer sequenceNo;
    private String answerJson;
    private Instant savedAt;
    private BigDecimal score;
    private String gradingStatus;
    private String graderType;
    private BigDecimal graderConfidence;
    private String feedback;
}
