package com.adaptivelearning.planning.application;

import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PlanGoalFingerprintTest {

    @Test
    void statusChangesDoNotInvalidateProposal() {
        var snapshot = goal("Java 基础", "2026-08-01", "2026-08-30", 1L, null, "ACTIVE");
        var current = goal("Java 基础", "2026-08-01", "2026-08-30", 1L, null, "PAUSED");
        assertThat(PlanValidationPolicy.goalContextStale(PlanValidationPolicy.goalFingerprint(snapshot), current, 1, 3)).isFalse();
    }

    @Test
    void dueDateChangeInvalidatesProposal() {
        var snapshot = goal("Java 基础", "2026-08-01", "2026-08-30", 1L, null, "ACTIVE");
        var current = goal("Java 基础", "2026-08-01", "2026-09-30", 1L, null, "ACTIVE");
        assertThat(PlanValidationPolicy.goalContextStale(PlanValidationPolicy.goalFingerprint(snapshot), current, 1, 2)).isTrue();
    }

    @Test
    void nameChangeInvalidatesProposal() {
        var snapshot = goal("Java 基础", "2026-08-01", "2026-08-30", 1L, null, "ACTIVE");
        var current = goal("Java 进阶", "2026-08-01", "2026-08-30", 1L, null, "ACTIVE");
        assertThat(PlanValidationPolicy.goalContextStale(PlanValidationPolicy.goalFingerprint(snapshot), current, 1, 2)).isTrue();
    }

    @Test
    void directionChangeInvalidatesProposal() {
        var snapshot = goal("Java 基础", "2026-08-01", "2026-08-30", 1L, null, "ACTIVE");
        var current = goal("Java 基础", "2026-08-01", "2026-08-30", 2L, null, "ACTIVE");
        assertThat(PlanValidationPolicy.goalContextStale(PlanValidationPolicy.goalFingerprint(snapshot), current, 1, 2)).isTrue();
    }

    @Test
    void sameContentDifferentVersionsStaysFresh() {
        var snapshot = goal("Java 基础", "2026-08-01", "2026-08-30", 1L, null, "ACTIVE");
        var current = goal("Java 基础", "2026-08-01", "2026-08-30", 1L, null, "ACTIVE");
        assertThat(PlanValidationPolicy.goalContextStale(PlanValidationPolicy.goalFingerprint(snapshot), current, 1, 5)).isFalse();
    }

    @Test
    void legacySnapshotWithoutFingerprintFallsBackToVersionComparison() {
        var current = goal("Java 基础", "2026-08-01", "2026-08-30", 1L, null, "ACTIVE");
        assertThat(PlanValidationPolicy.goalContextStale(null, current, 1, 1)).isFalse();
        assertThat(PlanValidationPolicy.goalContextStale(null, current, 1, 2)).isTrue();
        assertThat(PlanValidationPolicy.goalContextStale("", current, 1, 1)).isFalse();
    }

    private LearningGoalEntity goal(String name, String start, String due, Long directionId, String customDirection, String status) {
        var g = new LearningGoalEntity();
        g.setName(name);
        g.setStartDate(LocalDate.parse(start));
        g.setDueDate(LocalDate.parse(due));
        g.setDirectionId(directionId);
        g.setCustomDirection(customDirection);
        g.setStatus(status);
        return g;
    }
}
