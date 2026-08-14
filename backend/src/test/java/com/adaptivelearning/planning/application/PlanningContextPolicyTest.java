package com.adaptivelearning.planning.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanningContextPolicyTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void fingerprintIsDeterministicForSameOrderedContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("goalVersion", 3);
        context.put("basePlanVersion", null);
        context.put("baseTaskFingerprint", "tasks-v0");

        assertThat(PlanningContextPolicy.fingerprint(json, context))
                .isEqualTo(PlanningContextPolicy.fingerprint(json, context));
    }

    @Test
    void publicationCasRejectsChangedFormalVersionOrTaskSet() {
        assertThatThrownBy(() -> PlanningContextPolicy.requirePublicationCas(
                2, "tasks-v2", 3, "tasks-v3"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.PLAN_CONTEXT_STALE);

        assertThatThrownBy(() -> PlanningContextPolicy.requirePublicationCas(
                2, "tasks-v2", 2, "tasks-edited"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.PLAN_CONTEXT_STALE);
    }

    @Test
    void initialProposalCasAcceptsOnlyWhenPublicationStillMissing() {
        PlanningContextPolicy.requirePublicationCas(null, "empty", null, "empty");

        assertThatThrownBy(() -> PlanningContextPolicy.requirePublicationCas(
                null, "empty", 1, "tasks-v1"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.PLAN_CONTEXT_STALE);
    }

    @Test
    void planVersionTerminalStatesCannotBeReopened() {
        for (String terminal : new String[]{"PUBLISHED", "REJECTED", "SUPERSEDED"}) {
            assertThatThrownBy(() -> PlanningContextPolicy.requireState(
                    terminal, "重新校验", "DRAFT", "VALIDATION_FAILED"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(error -> ((BusinessException) error).getCode())
                    .isEqualTo(ErrorCode.STATE_TRANSITION_INVALID);
        }
    }

    @Test
    void taskMustMatchUserGoalAndExactProjectScope() {
        PlanningContextPolicy.requireTaskScope(1L, 10L, 100L, 1L, 10L, 100L);
        PlanningContextPolicy.requireTaskScope(1L, 10L, null, 1L, 10L, null);

        assertThatThrownBy(() -> PlanningContextPolicy.requireTaskScope(
                1L, 10L, 100L, 1L, 10L, 200L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThatThrownBy(() -> PlanningContextPolicy.requireTaskScope(
                1L, 10L, null, 1L, 10L, 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
