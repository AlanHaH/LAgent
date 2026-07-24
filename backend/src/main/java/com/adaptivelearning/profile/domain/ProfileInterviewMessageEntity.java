package com.adaptivelearning.profile.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("profile_interview_message")
public class ProfileInterviewMessageEntity extends BaseEntity {
    private String publicId;
    private Long sessionId;
    private Long userId;
    private Integer sequenceNo;
    private String role;
    private String content;
    private String source;
}
