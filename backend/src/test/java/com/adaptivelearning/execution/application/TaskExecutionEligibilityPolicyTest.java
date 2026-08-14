package com.adaptivelearning.execution.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExecutionEligibilityPolicyTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);
    private static final Instant NOW = ZonedDateTime.of(2026, 8, 14, 10, 0, 0, 0, ZONE).toInstant();

    @Test
    void scheduledTimeLaterTodayDoesNotBlockStart() {
        var decision = TaskExecutionEligibilityPolicy.evaluate(context(
                TaskExecutionEligibilityPolicy.Action.TASK_START, "NOT_STARTED",
                ZonedDateTime.of(2026, 8, 14, 14, 0, 0, 0, ZONE).toInstant(),
                ZonedDateTime.of(2026, 8, 14, 15, 0, 0, 0, ZONE).toInstant(),
                "ACTIVE", "ACTIVE", "NOT_STARTED", true, List.of()));

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void futureAndPastNotStartedTasksRequireReschedule() {
        var future = TaskExecutionEligibilityPolicy.evaluate(context(
                TaskExecutionEligibilityPolicy.Action.TASK_START, "NOT_STARTED",
                ZonedDateTime.of(2026, 8, 15, 9, 0, 0, 0, ZONE).toInstant(),
                ZonedDateTime.of(2026, 8, 15, 10, 0, 0, 0, ZONE).toInstant(),
                "ACTIVE", null, null, true, List.of()));
        var past = TaskExecutionEligibilityPolicy.evaluate(context(
                TaskExecutionEligibilityPolicy.Action.TASK_START, "NOT_STARTED",
                ZonedDateTime.of(2026, 8, 13, 9, 0, 0, 0, ZONE).toInstant(),
                ZonedDateTime.of(2026, 8, 13, 10, 0, 0, 0, ZONE).toInstant(),
                "ACTIVE", null, null, true, List.of()));

        assertThat(future.allowed()).isFalse();
        assertThat(future.code()).isEqualTo("TASK_NOT_EXECUTABLE_DATE");
        assertThat(past.allowed()).isFalse();
        assertThat(past.code()).isEqualTo("TASK_RESCHEDULE_REQUIRED");
    }

    @Test
    void overdueInProgressTaskCanResumeAndComplete() {
        Instant yesterday = ZonedDateTime.of(2026, 8, 13, 9, 0, 0, 0, ZONE).toInstant();
        var resume = TaskExecutionEligibilityPolicy.evaluate(context(
                TaskExecutionEligibilityPolicy.Action.SESSION_RESUME, "IN_PROGRESS",
                yesterday, yesterday.plusSeconds(3600), "ACTIVE", null, null, true, List.of()));
        var complete = TaskExecutionEligibilityPolicy.evaluate(context(
                TaskExecutionEligibilityPolicy.Action.TASK_COMPLETE, "IN_PROGRESS",
                yesterday, yesterday.plusSeconds(3600), "ACTIVE", null, null, true, List.of()));

        assertThat(resume.allowed()).isTrue();
        assertThat(complete.allowed()).isTrue();
    }

    @Test
    void inactiveParentBlocksNewExecution() {
        var projectPaused = TaskExecutionEligibilityPolicy.evaluate(context(
                TaskExecutionEligibilityPolicy.Action.SESSION_START, "IN_PROGRESS",
                NOW, NOW.plusSeconds(3600), "ACTIVE", "PAUSED", null, true, List.of()));
        var milestoneCompleted = TaskExecutionEligibilityPolicy.evaluate(context(
                TaskExecutionEligibilityPolicy.Action.TASK_START, "NOT_STARTED",
                NOW, NOW.plusSeconds(3600), "ACTIVE", "ACTIVE", "COMPLETED", true, List.of()));

        assertThat(projectPaused.allowed()).isFalse();
        assertThat(projectPaused.code()).isEqualTo("PROJECT_NOT_ACTIVE");
        assertThat(milestoneCompleted.allowed()).isFalse();
        assertThat(milestoneCompleted.code()).isEqualTo("MILESTONE_NOT_EXECUTABLE");
    }

    @Test
    void canceledPredecessorRemainsBlockedAndRequiresReplan() {
        var decision = TaskExecutionEligibilityPolicy.evaluate(context(
                TaskExecutionEligibilityPolicy.Action.TASK_START, "NOT_STARTED",
                NOW, NOW.plusSeconds(3600), "ACTIVE", null, null, true,
                List.of(new TaskExecutionEligibilityPolicy.Prerequisite(11L, "task-11", "CANCELED", false, null))));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("CANCELED_PREDECESSOR");
        assertThat(decision.replanRequired()).isTrue();
    }

    @Test
    void crossUserOrMissingDependencyFailsClosed() {
        var decision = TaskExecutionEligibilityPolicy.evaluate(context(
                TaskExecutionEligibilityPolicy.Action.TASK_START, "NOT_STARTED",
                NOW, NOW.plusSeconds(3600), "ACTIVE", null, null, false, List.of()));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("DEPENDENCY_DATA_INVALID");
    }

    private TaskExecutionEligibilityPolicy.Context context(
            TaskExecutionEligibilityPolicy.Action action,
            String taskStatus,
            Instant scheduledStart,
            Instant dueAt,
            String goalStatus,
            String projectStatus,
            String milestoneStatus,
            boolean dependencyDataValid,
            List<TaskExecutionEligibilityPolicy.Prerequisite> prerequisites) {
        return new TaskExecutionEligibilityPolicy.Context(action, taskStatus, ZONE, TODAY, NOW,
                scheduledStart, dueAt, goalStatus, projectStatus, milestoneStatus,
                dependencyDataValid, prerequisites);
    }
}
