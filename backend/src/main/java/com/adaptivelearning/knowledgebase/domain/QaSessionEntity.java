package com.adaptivelearning.knowledgebase.domain;
import com.adaptivelearning.shared.domain.BaseEntity;import com.baomidou.mybatisplus.annotation.TableName;import lombok.Getter;import lombok.Setter;
@Getter @Setter @TableName("qa_session") public class QaSessionEntity extends BaseEntity {private String publicId;private Long userId;private String title;private String selectedSpaceJson;private String status;private String contextSummary;}
