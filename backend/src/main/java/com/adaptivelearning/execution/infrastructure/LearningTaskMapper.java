package com.adaptivelearning.execution.infrastructure;

import com.adaptivelearning.execution.domain.LearningTaskEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LearningTaskMapper extends BaseMapper<LearningTaskEntity> {
    @Select("SELECT * FROM learning_task WHERE id=#{id} AND deleted_at IS NULL FOR UPDATE")
    LearningTaskEntity lockById(@Param("id") long id);

    @Select("SELECT * FROM learning_task WHERE public_id=#{publicId} AND user_id=#{userId} AND deleted_at IS NULL FOR UPDATE")
    LearningTaskEntity lockOwnedByPublicId(@Param("publicId") String publicId, @Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM task_dependency WHERE successor_task_id=#{successorId}")
    int dependencyCount(@Param("successorId") long successorId);

    @Select("""
            SELECT predecessor.*
            FROM task_dependency dependency
            JOIN learning_task predecessor ON predecessor.id=dependency.predecessor_task_id
            WHERE dependency.successor_task_id=#{successorId}
              AND predecessor.user_id=#{userId}
              AND predecessor.deleted_at IS NULL
            ORDER BY predecessor.id
            FOR UPDATE
            """)
    List<LearningTaskEntity> lockValidPredecessors(@Param("successorId") long successorId,
                                                   @Param("userId") long userId);

    @Select("""
            SELECT task.* FROM learning_task task
            WHERE task.goal_id=#{goalId} AND task.user_id=#{userId} AND task.deleted_at IS NULL
            ORDER BY task.id FOR UPDATE
            """)
    List<LearningTaskEntity> lockByGoal(@Param("goalId") long goalId, @Param("userId") long userId);

    @Select("""
            SELECT task.* FROM learning_task task
            WHERE task.project_id=#{projectId} AND task.user_id=#{userId} AND task.deleted_at IS NULL
            ORDER BY task.id FOR UPDATE
            """)
    List<LearningTaskEntity> lockByProject(@Param("projectId") long projectId, @Param("userId") long userId);

    @Select("""
            SELECT task.* FROM learning_task task
            WHERE task.milestone_id=#{milestoneId} AND task.user_id=#{userId} AND task.deleted_at IS NULL
            ORDER BY task.id FOR UPDATE
            """)
    List<LearningTaskEntity> lockByMilestone(@Param("milestoneId") long milestoneId,
                                             @Param("userId") long userId);
}
