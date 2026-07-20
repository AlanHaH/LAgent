package com.adaptivelearning.knowledgebase.domain;
import com.baomidou.mybatisplus.annotation.*;import lombok.Getter;import lombok.Setter;import java.time.Instant;
@Getter @Setter @TableName("document_version") public class DocumentVersionEntity {@TableId(type=IdType.ASSIGN_ID)private Long id;private Long documentId;private Integer versionNo;private Long storedObjectId;private String parserVersion;private String chunkConfigJson;private String embeddingModel;private Integer embeddingDimension;private String status;private String textHash;private String fileHash;private Instant createdAt;}
