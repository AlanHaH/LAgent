package com.adaptivelearning.execution.infrastructure;

import com.adaptivelearning.execution.domain.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public final class ExecutionMappers {
    private ExecutionMappers() {
    }

    @Mapper
    public interface SessionMapper extends BaseMapper<StudySessionEntity> {
        @Select("SELECT * FROM study_session WHERE id=#{id} AND user_id=#{userId} AND deleted_at IS NULL FOR UPDATE")
        StudySessionEntity lockOwnedById(@Param("id") long id, @Param("userId") long userId);

        @Select("SELECT * FROM study_session WHERE public_id=#{publicId} AND user_id=#{userId} AND deleted_at IS NULL FOR UPDATE")
        StudySessionEntity lockOwnedByPublicId(@Param("publicId") String publicId,
                                               @Param("userId") long userId);

        @Select("""
                SELECT * FROM study_session
                WHERE user_id=#{userId} AND status='RUNNING' AND deleted_at IS NULL
                ORDER BY id FOR UPDATE
                """)
        List<StudySessionEntity> lockRunningByUser(@Param("userId") long userId);

        @Select("""
                SELECT * FROM study_session
                WHERE task_id=#{taskId} AND status IN ('RUNNING','PAUSED') AND deleted_at IS NULL
                ORDER BY id FOR UPDATE
                """)
        List<StudySessionEntity> lockOpenByTask(@Param("taskId") long taskId);

        @Select("""
                SELECT * FROM study_session
                WHERE user_id=#{userId} AND status IN ('RUNNING','PAUSED') AND deleted_at IS NULL
                ORDER BY CASE WHEN status='RUNNING' THEN 0 ELSE 1 END,started_at DESC,id DESC
                """)
        List<StudySessionEntity> findActiveByUser(@Param("userId") long userId);
    }

    @Mapper
    public interface SessionPauseMapper extends BaseMapper<StudySessionPauseEntity> {
        @Select("""
                SELECT * FROM study_session_pause
                WHERE session_id=#{sessionId} AND resumed_at IS NULL
                ORDER BY id FOR UPDATE
                """)
        List<StudySessionPauseEntity> lockOpenBySession(@Param("sessionId") long sessionId);
    }

    @Mapper
    public interface NoteMapper extends BaseMapper<StudyNoteEntity> {
    }

    @Mapper
    public interface NoteVersionMapper extends BaseMapper<StudyNoteVersionEntity> {
    }

    @Mapper
    public interface CompletionSummaryMapper extends BaseMapper<TaskCompletionSummaryEntity> {
    }
}
