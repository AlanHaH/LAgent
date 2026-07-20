package com.adaptivelearning.knowledgebase.domain;
import com.adaptivelearning.shared.domain.BaseEntity;import com.baomidou.mybatisplus.annotation.TableName;import lombok.Getter;import lombok.Setter;
@Getter @Setter @TableName("knowledge_space") public class KnowledgeSpaceEntity extends BaseEntity {private String publicId;private Long userId;private String name;private String visibility;private String status;private Long directionId;}
