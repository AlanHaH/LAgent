package com.adaptivelearning.knowledgebase.domain;
import com.baomidou.mybatisplus.annotation.*;import lombok.Getter;import lombok.Setter;import java.time.Instant;
@Getter @Setter @TableName("document_deletion_token") public class DocumentDeletionTokenEntity {@TableId(type=IdType.ASSIGN_ID)private Long id;private Long documentId;private Long userId;private String tokenHash;private String status;private Instant expiresAt;private Instant createdAt;}
