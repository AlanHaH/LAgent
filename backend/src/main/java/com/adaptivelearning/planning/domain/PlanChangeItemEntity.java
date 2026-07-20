package com.adaptivelearning.planning.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @TableName("plan_change_item")
public class PlanChangeItemEntity {
    @TableId(type=IdType.ASSIGN_ID) private Long id;
    private String publicId;
    private Long planVersionId;
    private String action;
    private Long targetTaskId;
    private String clientRef;
    private String beforeJson;
    private String afterJson;
    private String reason;
    private String riskLevel;
    private Boolean confirmRequired;
    private String itemStatus;
}

