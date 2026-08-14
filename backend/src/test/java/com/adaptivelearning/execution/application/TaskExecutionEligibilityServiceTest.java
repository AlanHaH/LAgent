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
import com.adaptivelearning.shared.security.CurrentUser;
import com.adaptivelearning.support.domain.UserEntity;
import com.adaptivelearning.support.infrastructure.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskExecutionEligibilityServiceTest {
    private final UserMapper users = mock(UserMapper.class);
    private final GoalMapper goals = mock(GoalMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final MilestoneMapper milestones = mock(MilestoneMapper.class);
    private final LearningTaskMapper tasks = mock(LearningTaskMapper.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ExecutionIntegrityAuditService integrityAudit = mock(ExecutionIntegrityAuditService.class);
    private final TaskExecutionEligibilityService service = new TaskExecutionEligibilityService(
            users, goals, projects, milestones, tasks, jdbc, integrityAudit);

    private LearningTaskEntity task;
    private LearningGoalEntity goal;
    private LearningProjectEntity project;
    private MilestoneEntity milestone;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(42L, "u-42", "student", "", Set.of("STUDENT"), Set.of()), null, List.of()));
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setTimezone("Asia/Shanghai");
        when(users.lockById(42L)).thenReturn(user);

        task = new LearningTaskEntity();
        task.setId(400L);
        task.setPublicId("task-400");
        task.setUserId(42L);
        task.setGoalId(100L);
        task.setProjectId(200L);
        task.setMilestoneId(300L);
        task.setLifecycleStatus("NOT_STARTED");
        task.setScheduledStart(Instant.now());
        task.setDueAt(Instant.now().plusSeconds(3600));
        when(tasks.selectOne(any())).thenReturn(task);
        when(tasks.selectById(400L)).thenReturn(task);
        when(tasks.lockById(400L)).thenReturn(task);
        when(tasks.lockValidPredecessors(400L, 42L)).thenReturn(List.of());
        when(tasks.dependencyCount(400L)).thenReturn(0);

        goal = new LearningGoalEntity();
        goal.setId(100L);
        goal.setUserId(42L);
        goal.setStatus("ACTIVE");
        when(goals.lockById(100L)).thenReturn(goal);
        project = new LearningProjectEntity();
        project.setId(200L);
        project.setUserId(42L);
        project.setStatus("ACTIVE");
        when(projects.lockById(200L)).thenReturn(project);
        milestone = new MilestoneEntity();
        milestone.setId(300L);
        milestone.setProjectId(200L);
        milestone.setStatus("NOT_STARTED");
        when(milestones.lockById(300L)).thenReturn(milestone);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void commandUsesStableParentAndTaskLockOrder() {
        var result = service.lockAndRequire("task-400", TaskExecutionEligibilityPolicy.Action.TASK_START);

        assertThat(result.decision().allowed()).isTrue();
        InOrder order = inOrder(users, goals, projects, milestones, tasks);
        order.verify(users).lockById(42L);
        order.verify(goals).lockById(100L);
        order.verify(projects).lockById(200L);
        order.verify(milestones).lockById(300L);
        order.verify(tasks).lockById(400L);
        order.verify(tasks).lockValidPredecessors(400L, 42L);
    }

    @Test
    void dependencyCountMismatchFailsClosedAndAuditsWithoutLeakingRows() {
        when(tasks.dependencyCount(400L)).thenReturn(1);
        when(tasks.lockValidPredecessors(400L, 42L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.lockAndRequire("task-400",
                TaskExecutionEligibilityPolicy.Action.TASK_START))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.DEPENDENCY_DATA_INVALID);
        verify(integrityAudit).taskDependencyInvalid("task-400");
    }

    @Test
    void pausedProjectIsObservedInsideLockedContext() {
        project.setStatus("PAUSED");

        assertThatThrownBy(() -> service.lockAndRequire("task-400",
                TaskExecutionEligibilityPolicy.Action.SESSION_START))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("项目未处于活动状态");
    }
}
