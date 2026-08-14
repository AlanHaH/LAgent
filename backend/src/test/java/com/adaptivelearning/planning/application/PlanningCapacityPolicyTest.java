package com.adaptivelearning.planning.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningCapacityPolicyTest {
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");
    private final LocalDate monday = LocalDate.of(2026, 8, 10);

    @Test
    void conflictUsesStartPlusMinutesNotDueAndAdjacentIsAllowed() {
        var a = task("a", 1, null, "NOT_STARTED", 8, 0, 30, 23);
        var b = task("b", 2, null, "NOT_STARTED", 8, 30, 30, 23);
        var ok = PlanningCapacityPolicy.validate(List.of(a, b), context(Map.of(1L, 60, 2L, 60)),
                monday.atStartOfDay(zone).toInstant());
        assertThat(ok).noneMatch(issue -> issue.code().equals("TASK_TIME_CONFLICT"));

        var overlap = task("b", 2, null, "NOT_STARTED", 8, 29, 30, 23);
        assertThat(PlanningCapacityPolicy.validate(List.of(a, overlap), context(Map.of(1L, 60, 2L, 60)),
                monday.atStartOfDay(zone).toInstant())).anyMatch(issue -> issue.code().equals("TASK_TIME_CONFLICT"));
    }

    @Test
    void goalBudgetAggregatesProjectsAndTerminalTasksDoNotOccupy() {
        var projectA = task("a", 1, 10L, "PAUSED", 8, 0, 40, 9);
        var projectB = task("b", 1, 11L, "BLOCKED", 9, 0, 40, 10);
        assertThat(PlanningCapacityPolicy.validate(List.of(projectA, projectB), context(Map.of(1L, 60)),
                monday.atStartOfDay(zone).toInstant())).anyMatch(issue -> issue.code().equals("GOAL_WEEKLY_BUDGET_EXCEEDED"));

        var done = task("c", 1, null, "COMPLETED", 10, 0, 120, 12);
        var canceled = task("d", 1, null, "CANCELED", 12, 0, 120, 14);
        assertThat(PlanningCapacityPolicy.validate(List.of(done, canceled), context(Map.of(1L, 1)),
                monday.atStartOfDay(zone).toInstant())).isEmpty();
    }

    @Test
    void allGoalsShareUserCapacityAndWeekStartIsDeterministic() {
        var a = task("a", 1, null, "NOT_STARTED", 8, 0, 80, 10);
        var b = task("b", 2, null, "IN_PROGRESS", 10, 0, 80, 12);
        var issues = PlanningCapacityPolicy.validate(List.of(a, b), new PlanningCapacityPolicy.Context(zone, 1,
                        BigDecimal.ONE, List.of(new RuleBasedPlanner.Slot(1, LocalTime.of(8, 0), 120)), List.of(),
                        Map.of(1L, 200, 2L, 200)), monday.atStartOfDay(zone).toInstant());
        assertThat(issues).anyMatch(issue -> issue.code().equals("USER_DAILY_CAPACITY_EXCEEDED"));
        assertThat(PlanningCapacityPolicy.startOfWeek(LocalDate.of(2026, 8, 16), 1)).isEqualTo(monday);
        assertThat(PlanningCapacityPolicy.startOfWeek(LocalDate.of(2026, 8, 16), 7))
                .isEqualTo(LocalDate.of(2026, 8, 16));
    }

    private PlanningCapacityPolicy.Context context(Map<Long, Integer> budgets) {
        return new PlanningCapacityPolicy.Context(zone, 1, BigDecimal.ONE,
                List.of(new RuleBasedPlanner.Slot(1, LocalTime.of(8, 0), 600)), List.of(), budgets);
    }
    private PlanningCapacityPolicy.Task task(String ref, long goal, Long project, String status,
                                              int hour, int minute, int minutes, int dueHour) {
        ZonedDateTime start = ZonedDateTime.of(monday, LocalTime.of(hour, minute), zone);
        return new PlanningCapacityPolicy.Task(ref, goal, project, null, status, start,
                ZonedDateTime.of(monday, LocalTime.of(dueHour, 0), zone), minutes, monday, monday);
    }
}
