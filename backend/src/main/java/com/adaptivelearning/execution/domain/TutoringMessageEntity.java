package com.adaptivelearning.execution.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("tutoring_message")
public class TutoringMessageEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String publicId;
    private Long sessionId;
    private String role;
    private String content;
    private Integer guidanceLevel;
    private Long modelRunId;
    private String metadataJson;
    private Instant createdAt;
}
