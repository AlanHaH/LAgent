package com.adaptivelearning.execution.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("study_note")
public class StudyNoteEntity extends BaseEntity {
    private String publicId;
    private Long userId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;
    private Integer currentVersionNo;
    private String title;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long syncDocumentId;
}
