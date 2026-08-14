package com.adaptivelearning.goalproject.infrastructure;

import com.adaptivelearning.goalproject.domain.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public final class GoalProjectMappers {
    private GoalProjectMappers() {}
    @Mapper public interface GoalMapper extends BaseMapper<LearningGoalEntity> {
        @Select("SELECT * FROM learning_goal WHERE public_id=#{publicId} AND user_id=#{userId} AND deleted_at IS NULL FOR UPDATE")
        LearningGoalEntity lockOwnedByPublicId(@Param("publicId") String publicId, @Param("userId") long userId);
        @Select("SELECT * FROM learning_goal WHERE id=#{id} AND deleted_at IS NULL FOR UPDATE")
        LearningGoalEntity lockById(@Param("id") long id);
    }
    @Mapper public interface ProjectMapper extends BaseMapper<LearningProjectEntity> {
        @Select("SELECT * FROM learning_project WHERE public_id=#{publicId} AND deleted_at IS NULL FOR UPDATE")
        LearningProjectEntity lockByPublicId(@Param("publicId") String publicId);
        @Select("SELECT * FROM learning_project WHERE id=#{id} AND deleted_at IS NULL FOR UPDATE")
        LearningProjectEntity lockById(@Param("id") long id);
    }
    @Mapper public interface MilestoneMapper extends BaseMapper<MilestoneEntity> {
        @Select("SELECT * FROM milestone WHERE public_id=#{publicId} AND deleted_at IS NULL FOR UPDATE")
        MilestoneEntity lockByPublicId(@Param("publicId") String publicId);
        @Select("SELECT * FROM milestone WHERE id=#{id} AND deleted_at IS NULL FOR UPDATE")
        MilestoneEntity lockById(@Param("id") long id);
    }
    @Mapper public interface GoalProjectMapper extends BaseMapper<GoalProjectEntity> {
        @Delete("DELETE FROM goal_project WHERE goal_id=#{goalId} AND project_id=#{projectId}")
        int unlink(@Param("goalId") long goalId, @Param("projectId") long projectId);
    }
}
