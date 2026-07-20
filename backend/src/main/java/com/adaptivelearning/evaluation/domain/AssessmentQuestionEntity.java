package com.adaptivelearning.evaluation.domain;import com.baomidou.mybatisplus.annotation.*;import lombok.Getter;import lombok.Setter;import java.math.BigDecimal;
@Getter @Setter @TableName("assessment_question") public class AssessmentQuestionEntity {@TableId private Long assessmentId;private Integer sequenceNo;private Long questionVersionId;private BigDecimal score;private String snapshotJson;}
