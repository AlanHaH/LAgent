package com.adaptivelearning.goalproject.application;

import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
import com.adaptivelearning.goalproject.domain.LearningProjectEntity;
import com.adaptivelearning.goalproject.domain.MilestoneEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoalProjectIdSerializationTest {
    private static final long LARGE_ID = 9_007_199_254_740_993L;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void browserVisibleM04IdsAreStrings() {
        LearningGoalEntity goal = new LearningGoalEntity();
        goal.setDirectionId(LARGE_ID);
        goal.setSourceGoalId(LARGE_ID + 1);
        goal.setProfileVersionId(LARGE_ID + 2);
        assertThat(json.valueToTree(goal).path("directionId").isTextual()).isTrue();
        assertThat(json.valueToTree(goal).path("sourceGoalId").asText()).isEqualTo("9007199254740994");
        assertThat(json.valueToTree(goal).path("profileVersionId").isTextual()).isTrue();

        LearningProjectEntity project = new LearningProjectEntity();
        project.setPrimaryDirectionId(LARGE_ID);
        assertThat(json.valueToTree(project).path("primaryDirectionId").asText())
                .isEqualTo("9007199254740993");

        MilestoneEntity milestone = new MilestoneEntity();
        milestone.setProjectId(LARGE_ID);
        assertThat(json.valueToTree(milestone).path("projectId").isTextual()).isTrue();
    }
}
