package com.adaptivelearning.planning.application;

import com.adaptivelearning.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgePrerequisitePolicyTest {
    private final KnowledgePrerequisitePolicy policy = new KnowledgePrerequisitePolicy();

    @Test
    void stablyTopologicallyOrdersOrdinaryReversalAndKeepsUnrelatedOrder() {
        var result = policy.normalize(
                List.of(task("B", 2L), task("A", 1L), task("X", 3L), task("Y", 4L)),
                Set.of(1L, 2L, 3L, 4L), List.of(edge(1L, 2L)), Set.of());

        assertThat(result.tasks()).extracting(KnowledgePrerequisitePolicy.TaskKnowledge::value)
                .containsExactly("A", "B", "X", "Y");
        assertThat(result.taskDependencies()).containsExactly(
                new KnowledgePrerequisitePolicy.TaskDependency(0, 1));
    }

    @Test
    void rejectsMissingUnsatisfiedPredecessor() {
        assertThatThrownBy(() -> policy.normalize(
                List.of(task("B", 2L)), Set.of(1L, 2L), List.of(edge(1L, 2L)), Set.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("前置知识点");
    }

    @Test
    void allowsPredecessorAndSuccessorInSameTask() {
        var result = policy.normalize(
                List.of(task("A+B", 1L, 2L)), Set.of(1L, 2L), List.of(edge(1L, 2L)), Set.of());

        assertThat(result.tasks()).hasSize(1);
        assertThat(result.taskDependencies()).isEmpty();
    }

    @Test
    void rejectsCycleCreatedByMultiKnowledgePointTaskProjection() {
        assertThatThrownBy(() -> policy.normalize(
                List.of(task("A+D", 1L, 4L), task("B+C", 2L, 3L)),
                Set.of(1L, 2L, 3L, 4L),
                List.of(edge(1L, 2L), edge(3L, 4L)), Set.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务级前置关系形成环");
    }

    @Test
    void proficientPredecessorMayBeSkipped() {
        var result = policy.normalize(
                List.of(task("B", 2L)), Set.of(1L, 2L), List.of(edge(1L, 2L)), Set.of(1L));

        assertThat(result.tasks()).extracting(KnowledgePrerequisitePolicy.TaskKnowledge::value)
                .containsExactly("B");
    }

    @Test
    void tentativeProficientPredecessorMayNotBeSkipped() {
        assertThatThrownBy(() -> policy.normalize(
                List.of(task("B", 2L)), Set.of(1L, 2L), List.of(edge(1L, 2L)), Set.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("前置知识点");
    }

    @Test
    void rejectsKnowledgePointOutsideInputCatalog() {
        assertThatThrownBy(() -> policy.normalize(
                List.of(task("invented", 99L)), Set.of(1L, 2L), List.of(), Set.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("候选范围外");
    }

    private KnowledgePrerequisitePolicy.TaskKnowledge<String> task(String value, Long... ids) {
        return new KnowledgePrerequisitePolicy.TaskKnowledge<>(value, List.of(ids));
    }

    private KnowledgePrerequisitePolicy.Dependency edge(long predecessor, long successor) {
        return new KnowledgePrerequisitePolicy.Dependency(predecessor, successor);
    }
}
