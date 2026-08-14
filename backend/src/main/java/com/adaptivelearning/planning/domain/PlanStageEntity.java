package com.adaptivelearning.planning.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDate;

@Getter @Setter @TableName("plan_stage")
public class PlanStageEntity {
    @TableId(type=IdType.ASSIGN_ID) @JsonSerialize(using = ToStringSerializer.class) private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planVersionId;
    private String clientRef;
    private String name;
    private Integer sequenceNo;
    private LocalDate startDate;
    private LocalDate endDate;
    private String outcome;
}
