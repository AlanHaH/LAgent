package com.adaptivelearning.knowledgebase.domain;
import com.baomidou.mybatisplus.annotation.*;import lombok.Getter;import lombok.Setter;import java.time.Instant;
@Getter @Setter @TableName("qa_message") public class QaMessageEntity {@TableId(type=IdType.ASSIGN_ID)private Long id;private String publicId;private Long sessionId;private String role;private String content;private String answerMode;private String evidenceLevel;private Long modelRunId;private Long latencyMs;private Instant createdAt;}
