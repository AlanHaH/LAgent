package com.adaptivelearning.execution.application;

import com.adaptivelearning.execution.domain.LearningTaskEntity;
import com.adaptivelearning.execution.domain.StudyNoteEntity;
import com.adaptivelearning.execution.domain.StudyNoteVersionEntity;
import com.adaptivelearning.execution.domain.StudySessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class M06IdSerializationTest {
    private static final long BIG = 9_007_199_254_741_991L;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void browserVisibleExecutionIdsAreStrings() throws Exception {
        LearningTaskEntity task = new LearningTaskEntity();
        task.setGoalId(BIG);
        task.setProjectId(BIG + 1);
        task.setMilestoneId(BIG + 2);
        task.setOriginPlanVersionId(BIG + 3);
        task.setLearningBlockId(BIG + 4);
        task.setAcceptanceJson("[\"server-only\"]");
        StudySessionEntity session = new StudySessionEntity();
        session.setTaskId(BIG + 5);
        StudyNoteEntity note = new StudyNoteEntity();
        note.setTaskId(BIG + 6);
        note.setSyncDocumentId(BIG + 7);
        StudyNoteVersionEntity version = new StudyNoteVersionEntity();
        version.setId(BIG + 8);
        version.setNoteId(BIG + 9);
        version.setCreatedBy(BIG + 10);

        assertThat(json.readTree(json.writeValueAsString(task)).path("goalId").isTextual()).isTrue();
        assertThat(json.readTree(json.writeValueAsString(task)).path("originPlanVersionId").asText())
                .isEqualTo(String.valueOf(BIG + 3));
        assertThat(json.readTree(json.writeValueAsString(task)).has("acceptanceJson")).isFalse();
        assertThat(json.readTree(json.writeValueAsString(session)).path("taskId").isTextual()).isTrue();
        assertThat(json.readTree(json.writeValueAsString(note)).path("syncDocumentId").isTextual()).isTrue();
        assertThat(json.readTree(json.writeValueAsString(version)).path("id").isTextual()).isTrue();
        assertThat(json.readTree(json.writeValueAsString(version)).path("createdBy").asText())
                .isEqualTo(String.valueOf(BIG + 10));
    }

    @Test
    void knowledgeSourceUsesStringChunkId() throws Exception {
        TaskService.KnowledgeSourceView source = new TaskService.KnowledgeSourceView(
                String.valueOf(BIG), "document", "资料", 1, "摘要", null, null);
        assertThat(json.readTree(json.writeValueAsString(source)).path("chunkId").asText())
                .isEqualTo(String.valueOf(BIG));
    }
}
