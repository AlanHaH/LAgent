package com.adaptivelearning.knowledgebase.domain;
import com.adaptivelearning.shared.domain.BaseEntity;import com.baomidou.mybatisplus.annotation.TableName;import lombok.Getter;import lombok.Setter;
@Getter @Setter @TableName("knowledge_document") public class KnowledgeDocumentEntity extends BaseEntity {private String publicId;private Long spaceId;private Long ownerUserId;private Long categoryId;private String displayName;private String status;private Integer activeVersionNo;private String visibility;}
