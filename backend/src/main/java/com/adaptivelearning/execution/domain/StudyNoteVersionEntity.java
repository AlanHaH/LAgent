package com.adaptivelearning.execution.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("study_note_version")
public class StudyNoteVersionEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long noteId;
    private Integer versionNo;
    private String contentMarkdown;
    private String contentHash;
    private Instant createdAt;
    private Long createdBy;
}

