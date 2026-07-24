package com.adaptivelearning.evaluation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("question_version")
public class QuestionVersionEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long questionId;
    private Integer versionNo;
    private String type;
    private String stem;
    private String optionsJson;
    private String answerJson;
    private String rubricJson;
    private String analysis;
    private Integer difficulty;
    private Long sourceDocumentVersionId;
    private Instant createdAt;
}
