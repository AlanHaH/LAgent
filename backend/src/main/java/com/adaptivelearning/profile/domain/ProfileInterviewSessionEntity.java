package com.adaptivelearning.profile.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("profile_interview_session")
public class ProfileInterviewSessionEntity extends BaseEntity {
    private String publicId;
    private Long userId;
    private String status;
    private String draftJson;
    private String missingFieldsJson;
    private Integer completenessPercent;
    private String assistantMode;
    private Instant confirmedAt;
}
