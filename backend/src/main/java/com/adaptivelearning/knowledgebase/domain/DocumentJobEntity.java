package com.adaptivelearning.knowledgebase.domain;
import com.baomidou.mybatisplus.annotation.*;import lombok.Getter;import lombok.Setter;import java.time.Instant;
@Getter @Setter @TableName("document_job") public class DocumentJobEntity {@TableId(type=IdType.ASSIGN_ID)private Long id;private String publicId;private Long documentVersionId;private String jobType;private String status;private String idempotencyKey;private Integer attempts;private Instant nextRetryAt;private String errorCode;private String errorMessage;private Instant createdAt;private Instant updatedAt;}
