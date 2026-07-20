package com.adaptivelearning.profile.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_profile_direction")
public class ProfileDirectionEntity extends BaseEntity {
    private Long profileId;
    private Long directionId;
    private String customDirection;
    private String sourceType;
    private String currentStage;
    private Boolean isPrimary;
    private String status;
}

