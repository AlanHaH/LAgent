package com.adaptivelearning.profile.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_profile")
public class UserProfileEntity extends BaseEntity {
    private Long userId;
    private String timezone;
    private Integer weekStart;
    private Integer planPeriodDays;
    private String backgroundText;
    private String profileStatus;
    private Integer currentVersionNo;
}

