package com.adaptivelearning.planning.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

@Getter @Setter @TableName("plan_change_item")
public class PlanChangeItemEntity {
    @TableId(type=IdType.ASSIGN_ID) @JsonSerialize(using = ToStringSerializer.class) private Long id;
    private String publicId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planVersionId;
    private String action;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long targetTaskId;
    private String clientRef;
    private String beforeJson;
    private String afterJson;
    private String reason;
    private String riskLevel;
    private Boolean confirmRequired;
    private String itemStatus;
}
