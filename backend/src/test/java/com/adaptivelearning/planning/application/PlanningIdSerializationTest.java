package com.adaptivelearning.planning.application;

import com.adaptivelearning.planning.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningIdSerializationTest {
    private static final long LARGE_ID = 9_007_199_254_740_993L;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void browserVisiblePlanningIdsAreSerializedAsStrings() throws Exception {
        LearningPlanEntity plan = new LearningPlanEntity();
        plan.setGoalId(LARGE_ID); plan.setProjectId(LARGE_ID + 1);
        PlanVersionEntity version = new PlanVersionEntity();
        version.setPlanId(LARGE_ID); version.setModelRunId(LARGE_ID + 1);
        PlanningJobEntity job = new PlanningJobEntity();
        job.setGoalId(LARGE_ID); job.setPlanVersionId(LARGE_ID + 1);
        PlanStageEntity stage = new PlanStageEntity();
        stage.setId(LARGE_ID); stage.setPlanVersionId(LARGE_ID + 1);
        PlanChangeItemEntity change = new PlanChangeItemEntity();
        change.setId(LARGE_ID); change.setPlanVersionId(LARGE_ID + 1); change.setTargetTaskId(LARGE_ID + 2);
        PlanValidationResultEntity validation = new PlanValidationResultEntity();
        validation.setId(LARGE_ID); validation.setPlanVersionId(LARGE_ID + 1);

        assertText(plan, "goalId", LARGE_ID);
        assertText(plan, "projectId", LARGE_ID + 1);
        assertText(version, "planId", LARGE_ID);
        assertText(version, "modelRunId", LARGE_ID + 1);
        assertText(job, "goalId", LARGE_ID);
        assertText(job, "planVersionId", LARGE_ID + 1);
        assertText(stage, "id", LARGE_ID);
        assertText(stage, "planVersionId", LARGE_ID + 1);
        assertText(change, "id", LARGE_ID);
        assertText(change, "planVersionId", LARGE_ID + 1);
        assertText(change, "targetTaskId", LARGE_ID + 2);
        assertText(validation, "id", LARGE_ID);
        assertText(validation, "planVersionId", LARGE_ID + 1);
    }

    @Test
    void validationContractUsesValidatorCodeAndSeverityOnly() throws Exception {
        PlanValidationResultEntity validation = new PlanValidationResultEntity();
        validation.setId(LARGE_ID); validation.setPlanVersionId(LARGE_ID + 1);
        validation.setValidatorCode("DATE_INVALID"); validation.setSeverity("ERROR");
        JsonNode value = json.valueToTree(validation);

        assertThat(value.path("validatorCode").asText()).isEqualTo("DATE_INVALID");
        assertThat(value.path("severity").asText()).isEqualTo("ERROR");
        assertThat(value.has("ruleCode")).isFalse();
        assertThat(value.has("passed")).isFalse();
    }

    private void assertText(Object value, String field, long expected) throws Exception {
        JsonNode node = json.valueToTree(value).path(field);
        assertThat(node.isTextual()).as(field).isTrue();
        assertThat(node.asText()).isEqualTo(String.valueOf(expected));
    }
}
