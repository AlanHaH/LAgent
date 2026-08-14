package com.adaptivelearning.execution.application;

import com.adaptivelearning.execution.domain.LearningTaskEntity;
import com.adaptivelearning.execution.infrastructure.LearningTaskMapper;
import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
import com.adaptivelearning.goalproject.domain.LearningProjectEntity;
import com.adaptivelearning.goalproject.domain.MilestoneEntity;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.GoalMapper;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.MilestoneMapper;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.ProjectMapper;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.support.domain.UserEntity;
import com.adaptivelearning.support.infrastructure.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TaskExecutionEligibilityService {
    private final UserMapper userMapper;
    private final GoalMapper goalMapper;
    private final ProjectMapper projectMapper;
    private final MilestoneMapper milestoneMapper;
    private final LearningTaskMapper taskMapper;
    private final JdbcTemplate jdbc;
    private final ExecutionIntegrityAuditService integrityAudit;

    public record Evaluation(LearningTaskEntity task,
                             TaskExecutionEligibilityPolicy.Decision decision,
                             List<TaskExecutionEligibilityPolicy.Prerequisite> prerequisites) { }

    /**
     * Lock order: sys_user -> goal -> project -> milestone -> task -> predecessor tasks.
     * The caller must be transactional when this method is used for a command.
     */
    public Evaluation lockAndRequire(String taskPublicId, TaskExecutionEligibilityPolicy.Action action) {
        long userId = SecurityUtils.currentUserId();
        LearningTaskEntity preview = taskMapper.selectOne(new LambdaQueryWrapper<LearningTaskEntity>()
                .eq(LearningTaskEntity::getPublicId, taskPublicId)
                .eq(LearningTaskEntity::getUserId, userId));
        if (preview == null) notFound();
        return lockAndRequire(preview.getId(), userId, taskPublicId, action);
    }

    public Evaluation lockAndRequire(long taskId, long userId, TaskExecutionEligibilityPolicy.Action action) {
        return lockAndRequire(taskId, userId, null, action);
    }

    public LearningTaskEntity lockOwnedTask(String taskPublicId) {
        long userId = SecurityUtils.currentUserId();
        LearningTaskEntity preview = taskMapper.selectOne(new LambdaQueryWrapper<LearningTaskEntity>()
                .eq(LearningTaskEntity::getPublicId, taskPublicId)
                .eq(LearningTaskEntity::getUserId, userId));
        if (preview == null) notFound();
        lockUser(userId);
        LearningTaskEntity locked = taskMapper.lockById(preview.getId());
        requireSameTask(locked, preview.getId(), userId, taskPublicId);
        return locked;
    }

    public UserEntity lockUser(long userId) {
        UserEntity user = userMapper.lockById(userId);
        if (user == null) notFound();
        return user;
    }

    public Evaluation evaluateReadOnly(LearningTaskEntity task) {
        UserEntity user = userMapper.selectById(task.getUserId());
        if (user == null) notFound();
        LearningGoalEntity goal = goalMapper.selectById(task.getGoalId());
        LearningProjectEntity project = task.getProjectId() == null ? null : projectMapper.selectById(task.getProjectId());
        MilestoneEntity milestone = task.getMilestoneId() == null ? null : milestoneMapper.selectById(task.getMilestoneId());
        boolean parentsValid = validParents(task, goal, project, milestone);
        List<TaskExecutionEligibilityPolicy.Prerequisite> prerequisites = readPrerequisites(task, false);
        boolean dependencyValid = taskMapper.dependencyCount(task.getId()) == prerequisites.size();
        var decision = TaskExecutionEligibilityPolicy.evaluate(context(actionForRead(task), task, user, goal, project,
                milestone, parentsValid && dependencyValid, prerequisites));
        return new Evaluation(task, decision, prerequisites);
    }

    private Evaluation lockAndRequire(long taskId, long userId, String expectedPublicId,
                                      TaskExecutionEligibilityPolicy.Action action) {
        UserEntity user = lockUser(userId);

        LearningTaskEntity preview = taskMapper.selectById(taskId);
        if (preview == null || !Objects.equals(preview.getUserId(), userId)) notFound();
        if (expectedPublicId != null && !expectedPublicId.equals(preview.getPublicId())) notFound();

        LearningGoalEntity goal = goalMapper.lockById(preview.getGoalId());
        LearningProjectEntity project = preview.getProjectId() == null ? null : projectMapper.lockById(preview.getProjectId());
        MilestoneEntity milestone = preview.getMilestoneId() == null ? null : milestoneMapper.lockById(preview.getMilestoneId());
        LearningTaskEntity task = taskMapper.lockById(taskId);
        requireSameTask(task, taskId, userId, expectedPublicId);
        boolean parentsValid = validParents(task, goal, project, milestone);

        List<TaskExecutionEligibilityPolicy.Prerequisite> prerequisites = readPrerequisites(task, true);
        boolean dependencyValid = taskMapper.dependencyCount(task.getId()) == prerequisites.size();
        var decision = TaskExecutionEligibilityPolicy.evaluate(context(action, task, user, goal, project,
                milestone, parentsValid && dependencyValid, prerequisites));
        if (!dependencyValid) {
            integrityAudit.taskDependencyInvalid(task.getPublicId());
        }
        decision.requireAllowed();
        return new Evaluation(task, decision, prerequisites);
    }

    private List<TaskExecutionEligibilityPolicy.Prerequisite> readPrerequisites(LearningTaskEntity task,
                                                                                boolean lock) {
        List<LearningTaskEntity> rows = lock ? taskMapper.lockValidPredecessors(task.getId(), task.getUserId())
                : jdbc.query("""
                        SELECT predecessor.*
                        FROM task_dependency dependency
                        JOIN learning_task predecessor ON predecessor.id=dependency.predecessor_task_id
                        WHERE dependency.successor_task_id=? AND predecessor.user_id=?
                          AND predecessor.deleted_at IS NULL
                        ORDER BY predecessor.id
                        """, (rs, row) -> {
                    LearningTaskEntity item = new LearningTaskEntity();
                    item.setId(rs.getLong("id"));
                    item.setPublicId(rs.getString("public_id"));
                    item.setLifecycleStatus(rs.getString("lifecycle_status"));
                    return item;
                }, task.getId(), task.getUserId());
        return rows.stream().map(item -> {
            List<String> blockStatuses = jdbc.query("""
                    SELECT status FROM learning_block
                    WHERE task_id=? AND deleted_at IS NULL ORDER BY id LIMIT 1
                    """, (rs, row) -> rs.getString(1), item.getId());
            return new TaskExecutionEligibilityPolicy.Prerequisite(item.getId(), item.getPublicId(),
                    item.getLifecycleStatus(), !blockStatuses.isEmpty(),
                    blockStatuses.isEmpty() ? null : blockStatuses.get(0));
        }).toList();
    }

    private TaskExecutionEligibilityPolicy.Context context(TaskExecutionEligibilityPolicy.Action action,
                                                            LearningTaskEntity task, UserEntity user,
                                                            LearningGoalEntity goal, LearningProjectEntity project,
                                                            MilestoneEntity milestone, boolean dependencyDataValid,
                                                            List<TaskExecutionEligibilityPolicy.Prerequisite> prerequisites) {
        ZoneId zone = ZoneId.of(user.getTimezone());
        Instant now = Instant.now();
        return new TaskExecutionEligibilityPolicy.Context(action, task.getLifecycleStatus(), zone,
                LocalDate.now(zone), now, task.getScheduledStart(), task.getDueAt(),
                goal == null ? null : goal.getStatus(), project == null ? null : project.getStatus(),
                milestone == null ? null : milestone.getStatus(), dependencyDataValid, prerequisites);
    }

    private boolean validParents(LearningTaskEntity task, LearningGoalEntity goal,
                                 LearningProjectEntity project, MilestoneEntity milestone) {
        if (goal == null || !Objects.equals(goal.getUserId(), task.getUserId())) return false;
        if (task.getProjectId() != null && (project == null
                || !Objects.equals(project.getUserId(), task.getUserId())
                || !Objects.equals(project.getId(), task.getProjectId()))) return false;
        if (task.getProjectId() == null && project != null) return false;
        if (task.getMilestoneId() != null && (milestone == null
                || !Objects.equals(milestone.getId(), task.getMilestoneId())
                || project == null || !Objects.equals(milestone.getProjectId(), project.getId()))) return false;
        return task.getMilestoneId() != null || milestone == null;
    }

    private TaskExecutionEligibilityPolicy.Action actionForRead(LearningTaskEntity task) {
        return TaskExecutionEligibilityPolicy.Action.GRAPH_AVAILABILITY;
    }

    private void requireSameTask(LearningTaskEntity task, long id, long userId, String publicId) {
        if (task == null || task.getId() != id || !Objects.equals(task.getUserId(), userId)
                || publicId != null && !publicId.equals(task.getPublicId())) notFound();
    }

    private void notFound() {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资源不存在");
    }
}
