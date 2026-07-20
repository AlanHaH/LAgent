package com.adaptivelearning.planning.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter @Setter @TableName("plan_confirmation")
public class PlanConfirmationEntity {
    @TableId(type=IdType.ASSIGN_ID) private Long id;
    private Long planVersionId;
    private Long userId;
    private String proposalHash;
    private String tokenHash;
    private String status;
    private Instant expiresAt;
    private Instant confirmedAt;
    private Instant createdAt;
}

