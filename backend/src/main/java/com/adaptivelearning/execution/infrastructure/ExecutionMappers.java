package com.adaptivelearning.execution.infrastructure;

import com.adaptivelearning.execution.domain.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

public final class ExecutionMappers {
  private ExecutionMappers(){}
  @Mapper public interface SessionMapper extends BaseMapper<StudySessionEntity>{}
  @Mapper public interface SessionPauseMapper extends BaseMapper<StudySessionPauseEntity>{}
  @Mapper public interface NoteMapper extends BaseMapper<StudyNoteEntity>{}
  @Mapper public interface NoteVersionMapper extends BaseMapper<StudyNoteVersionEntity>{}
  @Mapper public interface CompletionSummaryMapper extends BaseMapper<TaskCompletionSummaryEntity>{}
}

