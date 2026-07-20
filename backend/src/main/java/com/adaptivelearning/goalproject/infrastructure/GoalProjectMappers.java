package com.adaptivelearning.goalproject.infrastructure;

import com.adaptivelearning.goalproject.domain.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

public final class GoalProjectMappers {
    private GoalProjectMappers() {}
    @Mapper public interface GoalMapper extends BaseMapper<LearningGoalEntity> {}
    @Mapper public interface ProjectMapper extends BaseMapper<LearningProjectEntity> {}
    @Mapper public interface MilestoneMapper extends BaseMapper<MilestoneEntity> {}
    @Mapper public interface GoalProjectMapper extends BaseMapper<GoalProjectEntity> {
        @Delete("DELETE FROM goal_project WHERE goal_id=#{goalId} AND project_id=#{projectId}")
        int unlink(@Param("goalId") long goalId, @Param("projectId") long projectId);
    }
}

