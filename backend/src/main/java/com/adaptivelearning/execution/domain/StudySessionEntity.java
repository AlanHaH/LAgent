package com.adaptivelearning.execution.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("study_session")
public class StudySessionEntity extends BaseEntity {
    private String publicId;
    private String sessionGroupId;
    private Long userId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;
    private String source;
    private Instant startedAt;
    private Instant endedAt;
    private Long pauseSeconds;
    private Long effectiveSeconds;
    private String status;
    private String manualReason;
}
