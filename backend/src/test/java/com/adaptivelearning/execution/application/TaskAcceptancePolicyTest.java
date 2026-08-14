package com.adaptivelearning.execution.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.support.application.HashingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskAcceptancePolicyTest {
    private TaskAcceptancePolicy policy;

    @BeforeEach
    void setUp() {
        policy = new TaskAcceptancePolicy(new ObjectMapper(), new HashingService());
    }

    @Test
    void allCriteriaWithCurrentSnapshotAreAccepted() {
        var snapshot = policy.snapshot("[\"完成示例\",\"通过练习\"]");

        policy.requireConfirmed(snapshot,
                new TaskAcceptancePolicy.Confirmation(snapshot.snapshotHash(), List.of(0, 1)));

        assertThat(snapshot.criteria()).extracting(TaskAcceptancePolicy.Criterion::text)
                .containsExactly("完成示例", "通过练习");
    }

    @Test
    void missingDuplicateAndOutOfRangeIndexesAreRejected() {
        var snapshot = policy.snapshot("[\"A\",\"B\"]");

        assertInvalid(() -> policy.requireConfirmed(snapshot,
                new TaskAcceptancePolicy.Confirmation(snapshot.snapshotHash(), List.of(0))));
        assertInvalid(() -> policy.requireConfirmed(snapshot,
                new TaskAcceptancePolicy.Confirmation(snapshot.snapshotHash(), List.of(0, 0, 1))));
        assertInvalid(() -> policy.requireConfirmed(snapshot,
                new TaskAcceptancePolicy.Confirmation(snapshot.snapshotHash(), List.of(0, 2))));
    }

    @Test
    void staleOrForgedSnapshotIsRejectedWithStableCode() {
        var snapshot = policy.snapshot("[\"A\"]");

        assertThatThrownBy(() -> policy.requireConfirmed(snapshot,
                new TaskAcceptancePolicy.Confirmation("forged", List.of(0))))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(ErrorCode.TASK_ACCEPTANCE_STALE));
    }

    @Test
    void canonicalHashIsStableAcrossJsonWhitespace() {
        assertThat(policy.snapshot("[\"A\", \"B\"]").snapshotHash())
                .isEqualTo(policy.snapshot("  [ \"A\" , \"B\" ] ").snapshotHash());
    }

    @Test
    void emptyLegacyAcceptanceRequiresNoConfirmation() {
        var snapshot = policy.snapshot("[]");
        policy.requireConfirmed(snapshot, null);
        assertThat(snapshot.criteria()).isEmpty();
    }

    @Test
    void malformedDatabaseAcceptanceFailsClosed() {
        assertThatThrownBy(() -> policy.snapshot("{\"text\":\"not-an-array\"}"))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(ErrorCode.DEPENDENCY_DATA_INVALID));
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(ErrorCode.COMMON_VALIDATION_ERROR));
    }
}
