package com.adaptivelearning.planning.domain;

import com.adaptivelearning.shared.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @TableName("plan_version")
public class PlanVersionEntity extends BaseEntity {
    private String publicId;
    private Long planId;
    private Integer versionNo;
    private Integer baseVersionNo;
    private String status;
    private String triggerType;
    private String triggerEventId;
    private String contextSnapshotJson;
    private String proposalHash;
    private String riskLevel;
    private Long modelRunId;
    private String summaryJson;
}

