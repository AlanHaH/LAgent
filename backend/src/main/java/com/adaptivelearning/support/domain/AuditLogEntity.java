package com.adaptivelearning.support.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("audit_log")
public class AuditLogEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String requestId;
    private Long operatorId;
    private String operatorType;
    private String action;
    private String resourceType;
    private String resourceId;
    private String beforeSummary;
    private String afterSummary;
    private String result;
    private String ip;
    private Instant createdAt;
}

