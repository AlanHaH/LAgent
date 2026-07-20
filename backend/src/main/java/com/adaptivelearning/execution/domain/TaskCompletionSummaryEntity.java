package com.adaptivelearning.execution.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Getter @Setter @TableName("task_completion_summary")
public class TaskCompletionSummaryEntity {
  @TableId(type=IdType.ASSIGN_ID)private Long id;private Long taskId;private Long userId;private String learnedText;private String difficultyText;
  private Integer qualityLevel;private Integer confidenceLevel;private String remainingQuestions;private Integer revisionNo;private Instant createdAt;
}

