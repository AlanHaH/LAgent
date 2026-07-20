package com.adaptivelearning.execution.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Getter @Setter @TableName("study_session_pause")
public class StudySessionPauseEntity {
  @TableId(type=IdType.ASSIGN_ID) private Long id;private Long sessionId;private Instant pausedAt;private Instant resumedAt;private Long seconds;
}

