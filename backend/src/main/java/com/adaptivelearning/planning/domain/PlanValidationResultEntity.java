package com.adaptivelearning.planning.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

@Getter @Setter @TableName("plan_validation_result")
public class PlanValidationResultEntity {
    @TableId(type=IdType.ASSIGN_ID) @JsonSerialize(using = ToStringSerializer.class) private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planVersionId;
    private String validatorCode;
    private String severity;
    private String fieldPath;
    private String message;
    private String detailsJson;
    private Instant createdAt;
}
