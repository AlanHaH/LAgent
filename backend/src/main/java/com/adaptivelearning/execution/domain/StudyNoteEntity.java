package com.adaptivelearning.execution.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("study_note")
public class StudyNoteEntity extends BaseEntity {
    private String publicId;
    private Long userId;
    private Long taskId;
    private Integer currentVersionNo;
    private String title;
    private Long syncDocumentId;
}

