package com.adaptivelearning.planning.application;

import com.adaptivelearning.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class PlanCandidatePolicyTest {
    private final PlanCandidatePolicy policy = new PlanCandidatePolicy();
    private final PlanCandidatePolicy.Milestone milestone = new PlanCandidatePolicy.Milestone(
            9L, "milestone-9", LocalDate.of(2026, 8, 20), Set.of("M:milestone-9:C1"));
    private final PlanCandidatePolicy.Context context = new PlanCandidatePolicy.Context(false,
            Set.of(1L, 2L), Set.of(11L), Set.of("GC1"), Map.of(9L, milestone));

    @Test
    void acceptsStructuredCoverageAndRejectsForgedIds() {
        assertThat(policy.validateCandidates(List.of(candidate(60, 9L, List.of("GC1"),
                List.of("M:milestone-9:C1"))), context)).hasSize(1);
        assertThatThrownBy(() -> policy.validateCandidates(List.of(candidate(60, 9L,
                List.of("GC-X"), List.of())), context)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validateCandidates(List.of(candidate(60, 99L,
                List.of(), List.of())), context)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsDurationEnumKnowledgeChunkAndCustomDirectionViolations() {
        assertInvalid(candidate(180, null, List.of(), List.of()), context);
        PlanCandidatePolicy.Candidate invalidEnum = new PlanCandidatePolicy.Candidate("task-a", "任务A", "STUDY",
                "HIGH", 30, List.of(1L), List.of(11L), "目标", List.of("验收"), null, List.of(), List.of());
        assertInvalid(invalidEnum, context);
        PlanCandidatePolicy.Candidate outside = new PlanCandidatePolicy.Candidate("task-a", "任务A", "LEARNING",
                "HIGH", 30, List.of(99L), List.of(999L), "目标", List.of("验收"), null, List.of(), List.of());
        assertInvalid(outside, context);
        assertInvalid(candidate(30, null, List.of(), List.of()), new PlanCandidatePolicy.Context(true,
                Set.of(1L), Set.of(11L), Set.of(), Map.of()));
    }

    @Test
    void finalSetMustCoverGoalAndEveryActiveMilestone() {
        PlanCandidatePolicy.FinalTask task = new PlanCandidatePolicy.FinalTask("task-a", "COMPLETED", 9L,
                List.of("提交产物"), Set.of("GC1"), Set.of("M:milestone-9:C1"));
        assertThatCode(() -> policy.requireFinalCoverage(List.of(task), Set.of("GC1"), Map.of(9L, milestone)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireFinalCoverage(List.of(task), Set.of("GC1", "GC2"),
                Map.of(9L, milestone))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.requireFinalCoverage(List.of(), Set.of(), Map.of(9L, milestone)))
                .isInstanceOf(BusinessException.class);
    }

    private PlanCandidatePolicy.Candidate candidate(int minutes, Long milestoneId, List<String> goal,
                                                    List<String> milestoneCoverage) {
        return new PlanCandidatePolicy.Candidate("task-a", "任务A", "LEARNING", "HIGH", minutes,
                List.of(1L), List.of(11L), "完成目标", List.of("提交可验收结果"), milestoneId,
                goal, milestoneCoverage);
    }
    private void assertInvalid(PlanCandidatePolicy.Candidate candidate, PlanCandidatePolicy.Context ctx) {
        assertThatThrownBy(() -> policy.validateCandidates(List.of(candidate), ctx))
                .isInstanceOf(BusinessException.class);
    }
}
