package com.adaptivelearning.execution.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("study_note_version")
public class StudyNoteVersionEntity {
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long noteId;
    private Integer versionNo;
    private String contentMarkdown;
    private String contentHash;
    private Instant createdAt;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;
}
