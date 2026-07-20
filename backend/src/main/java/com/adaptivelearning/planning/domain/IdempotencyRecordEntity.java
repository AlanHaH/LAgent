package com.adaptivelearning.planning.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter @Setter @TableName("idempotency_record")
public class IdempotencyRecordEntity {
    @TableId(type=IdType.ASSIGN_ID) private Long id;
    private Long userId;
    private String keyHash;
    private String requestHash;
    private String responseRef;
    private String status;
    private Instant expiresAt;
    private Instant createdAt;
}

