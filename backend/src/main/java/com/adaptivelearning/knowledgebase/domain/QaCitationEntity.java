package com.adaptivelearning.knowledgebase.domain;
import com.baomidou.mybatisplus.annotation.*;import lombok.Getter;import lombok.Setter;import java.math.BigDecimal;
@Getter @Setter @TableName("qa_citation") public class QaCitationEntity {@TableId(type=IdType.ASSIGN_ID)private Long id;private Long messageId;private String citationCode;private Long chunkId;private Long documentVersionId;private String quotePreview;private Integer rankNo;private BigDecimal scoreSnapshot;private String accessStatus;}
