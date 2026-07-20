package com.adaptivelearning.knowledgebase.domain;
import com.baomidou.mybatisplus.annotation.*;import lombok.Getter;import lombok.Setter;import java.time.Instant;
@Getter @Setter @TableName("qa_feedback") public class QaFeedbackEntity {@TableId(type=IdType.ASSIGN_ID)private Long id;private Long messageId;private Long userId;private Integer rating;private String reasonCode;private String comment;private Instant createdAt;}
