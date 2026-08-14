package com.adaptivelearning.execution.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, deterministic execution gate shared by command paths and the read-only task graph.
 * Database locking is deliberately handled by {@link TaskExecutionEligibilityService}.
 */
public final class TaskExecutionEligibilityPolicy {
    private TaskExecutionEligibilityPolicy() { }

    public enum Action {
        TASK_START,
        TASK_RESUME,
        SESSION_START,
        SESSION_RESUME,
        TASK_COMPLETE,
        GRAPH_AVAILABILITY
    }

    public record Prerequisite(long id, String publicId, String status,
                               boolean learningBlockRequired, String learningBlockStatus) {
        boolean complete() {
            return "COMPLETED".equals(status)
                    && (!learningBlockRequired || "COMPLETED".equals(learningBlockStatus));
        }
    }

    public record Context(Action action, String taskStatus, ZoneId zone, LocalDate today, Instant now,
                          Instant scheduledStart, Instant dueAt,
                          String goalStatus, String projectStatus, String milestoneStatus,
                          boolean dependencyDataValid, List<Prerequisite> prerequisites) {
        public Context {
            prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        }
    }

    public record Decision(boolean allowed, String code, String message, boolean replanRequired) {
        static Decision allow() {
            return new Decision(true, null, null, false);
        }

        static Decision deny(String code, String message) {
            return new Decision(false, code, message, false);
        }

        static Decision deny(String code, String message, boolean replanRequired) {
            return new Decision(false, code, message, replanRequired);
        }

        public void requireAllowed() {
            if (allowed) return;
            ErrorCode error = "DEPENDENCY_DATA_INVALID".equals(code)
                    ? ErrorCode.DEPENDENCY_DATA_INVALID : ErrorCode.STATE_TRANSITION_INVALID;
            throw new BusinessException(error, message,
                    Map.of("blockedReason", code, "replanRequired", replanRequired));
        }
    }

    public static Decision evaluate(Context context) {
        if (!context.dependencyDataValid()) {
            return Decision.deny("DEPENDENCY_DATA_INVALID", "任务依赖数据异常，已拒绝执行");
        }
        if (!"ACTIVE".equals(context.goalStatus())) {
            return Decision.deny("GOAL_NOT_ACTIVE", "目标未处于活动状态，不能执行任务");
        }
        if (context.projectStatus() != null && !"ACTIVE".equals(context.projectStatus())) {
            return Decision.deny("PROJECT_NOT_ACTIVE", "项目未处于活动状态，不能执行任务");
        }
        if (context.milestoneStatus() != null && !"NOT_STARTED".equals(context.milestoneStatus())) {
            return Decision.deny("MILESTONE_NOT_EXECUTABLE", "里程碑已结束，不能继续执行关联任务");
        }

        Prerequisite canceled = context.prerequisites().stream()
                .filter(item -> "CANCELED".equals(item.status())).findFirst().orElse(null);
        if (canceled != null) {
            return Decision.deny("CANCELED_PREDECESSOR", "前置任务已取消，需要重新规划", true);
        }
        if (context.prerequisites().stream().anyMatch(item -> !item.complete())) {
            return Decision.deny("PREREQUISITE_INCOMPLETE", "请先完成全部前置任务");
        }

        Decision state = requireTaskState(context.action(), context.taskStatus());
        if (!state.allowed()) return state;

        if ("NOT_STARTED".equals(context.taskStatus())) {
            if (context.scheduledStart() == null) {
                return Decision.deny("TASK_RESCHEDULE_REQUIRED", "任务尚未排期，请先通过计划模块安排日期");
            }
            LocalDate scheduledDate = context.scheduledStart().atZone(context.zone()).toLocalDate();
            if (scheduledDate.isAfter(context.today())) {
                return Decision.deny("TASK_NOT_EXECUTABLE_DATE", "任务尚未到计划执行日期");
            }
            if (scheduledDate.isBefore(context.today())
                    || context.dueAt() != null && context.dueAt().isBefore(context.now())) {
                return Decision.deny("TASK_RESCHEDULE_REQUIRED", "任务已错过计划窗口，请先通过计划模块改期");
            }
        }
        return Decision.allow();
    }

    private static Decision requireTaskState(Action action, String status) {
        Set<String> allowed = switch (action) {
            case TASK_START, GRAPH_AVAILABILITY -> Set.of("NOT_STARTED", "IN_PROGRESS", "PAUSED", "BLOCKED");
            case TASK_RESUME -> Set.of("PAUSED", "BLOCKED");
            case SESSION_START, SESSION_RESUME -> Set.of("IN_PROGRESS");
            case TASK_COMPLETE -> Set.of("IN_PROGRESS", "PAUSED");
        };
        return allowed.contains(status) ? Decision.allow()
                : Decision.deny("TASK_STATE_INVALID", "当前任务状态不允许该执行操作");
    }
}
