package com.adaptivelearning.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@TableName("profile_version")
public class ProfileVersionEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long profileId;
    private Integer versionNo;
    private String snapshotJson;
    private BigDecimal confidence;
    private String triggerType;
    private String triggerEventId;
    private Instant createdAt;
    private Long createdBy;
}

