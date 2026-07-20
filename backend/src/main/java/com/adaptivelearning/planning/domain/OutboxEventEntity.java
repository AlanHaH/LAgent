package com.adaptivelearning.planning.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter @Setter @TableName("outbox_event")
public class OutboxEventEntity {
    @TableId(type=IdType.ASSIGN_ID) private Long id;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String payloadJson;
    private String correlationId;
    private String status;
    private Integer attempts;
    private Instant nextRetryAt;
    private Instant createdAt;
}

