package com.adaptivelearning.planning.application;

import com.adaptivelearning.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RuleBasedPlannerM05BTest {
    private final RuleBasedPlanner planner = new RuleBasedPlanner();
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");
    private final LocalDate monday = LocalDate.of(2026, 8, 10);

    @Test
    void usesMultipleSlotsAndAdjacentTasksWithoutDefaultTime() {
        List<RuleBasedPlanner.TaskDraft> result = planner.schedule(
                List.of(content("A", 30), content("B", 30), content("C", 45)), monday, monday, zone,
                List.of(new RuleBasedPlanner.Slot(1, LocalTime.of(8, 0), 60),
                        new RuleBasedPlanner.Slot(1, LocalTime.of(19, 0), 60)), List.of(), BigDecimal.ONE,
                List.of(), 2, 25);

        assertThat(result).extracting(task -> task.start().toLocalTime())
                .containsExactly(LocalTime.of(8, 0), LocalTime.of(8, 30), LocalTime.of(19, 0));
        assertThat(result.get(0).due().toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(result.get(0).start().plusMinutes(30)).isEqualTo(result.get(1).start());
    }

    @Test
    void dailyAndFocusAreSoftButNoSlotAndZeroExceptionAreHard() {
        List<RuleBasedPlanner.TaskDraft> three = planner.schedule(
                List.of(content("A", 50), content("B", 50), content("C", 50)), monday, monday, zone,
                List.of(new RuleBasedPlanner.Slot(1, LocalTime.of(8, 0), 180)), List.of(), BigDecimal.ONE,
                List.of(), 2, 30);
        assertThat(three).hasSize(3);
        assertThatThrownBy(() -> planner.schedule(List.of(content("A", 30)), monday, monday, zone,
                List.of(), List.of(), BigDecimal.ONE, List.of(), 2, 45)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> planner.schedule(List.of(content("A", 30)), monday, monday, zone,
                List.of(new RuleBasedPlanner.Slot(1, LocalTime.of(8, 0), 60)),
                List.of(new RuleBasedPlanner.DayException(monday, 0)), BigDecimal.ONE, List.of(), 2, 45))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsOutOfRangeInsteadOfClamping() {
        assertThatThrownBy(() -> planner.schedule(List.of(content("A", 180)), monday, monday, zone,
                List.of(new RuleBasedPlanner.Slot(1, LocalTime.of(8, 0), 240)), List.of(), BigDecimal.ONE,
                List.of(), 2, 45)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("10～120");
    }

    private RuleBasedPlanner.TaskContent content(String title, int minutes) {
        return new RuleBasedPlanner.TaskContent(title, "LEARNING", "MEDIUM", minutes, List.of(), List.of(),
                "完成可解释的学习成果", List.of(), false, List.of("提交可验证结果"), "test",
                "task-" + title, null, List.of(), List.of(), monday);
    }
}
