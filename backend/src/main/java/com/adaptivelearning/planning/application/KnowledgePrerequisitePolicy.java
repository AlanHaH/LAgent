package com.adaptivelearning.planning.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Validates knowledge prerequisites and converts them into a stable task order.
 * The policy is deliberately independent of persistence and model providers so Java remains
 * the final authority over plan ordering.
 */
public final class KnowledgePrerequisitePolicy {

    public record TaskKnowledge<T>(T value, List<Long> knowledgePointIds) {
        public TaskKnowledge {
            knowledgePointIds = knowledgePointIds == null ? List.of() : List.copyOf(knowledgePointIds);
        }
    }

    public record Dependency(long predecessorId, long successorId) { }

    public record TaskDependency(int predecessorTaskIndex, int successorTaskIndex) { }

    public record Result<T>(List<TaskKnowledge<T>> tasks, List<TaskDependency> taskDependencies) { }

    public <T> Result<T> normalize(List<TaskKnowledge<T>> tasks,
                                   Set<Long> allowedKnowledgePointIds,
                                   List<Dependency> dependencies,
                                   Set<Long> satisfiedPrerequisiteIds) {
        List<TaskKnowledge<T>> candidates = tasks == null ? List.of() : List.copyOf(tasks);
        Set<Long> allowed = allowedKnowledgePointIds == null ? Set.of() : Set.copyOf(allowedKnowledgePointIds);
        List<Dependency> knowledgeDependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        Set<Long> satisfied = satisfiedPrerequisiteIds == null
                ? Set.of() : Set.copyOf(satisfiedPrerequisiteIds);

        Map<Long, Integer> firstTaskByKnowledgePoint = new HashMap<>();
        for (int taskIndex = 0; taskIndex < candidates.size(); taskIndex++) {
            for (Long knowledgePointId : candidates.get(taskIndex).knowledgePointIds()) {
                if (knowledgePointId == null || !allowed.contains(knowledgePointId)) {
                    throw invalid("AI 返回了候选范围外的知识点 ID");
                }
                firstTaskByKnowledgePoint.putIfAbsent(knowledgePointId, taskIndex);
            }
        }

        LinkedHashSet<TaskDependency> projected = new LinkedHashSet<>();
        for (Dependency dependency : knowledgeDependencies) {
            if (!allowed.contains(dependency.predecessorId()) || !allowed.contains(dependency.successorId())) {
                throw invalid("前置关系包含候选范围外的知识点 ID");
            }
            Integer successorTask = firstTaskByKnowledgePoint.get(dependency.successorId());
            if (successorTask == null || satisfied.contains(dependency.predecessorId())) {
                continue;
            }
            Integer predecessorTask = firstTaskByKnowledgePoint.get(dependency.predecessorId());
            if (predecessorTask == null) {
                throw invalid("后续知识点已出现，但未满足的前置知识点没有任务覆盖");
            }
            if (!predecessorTask.equals(successorTask)) {
                projected.add(new TaskDependency(predecessorTask, successorTask));
            }
        }

        List<Integer> stableOrder = stableTopologicalOrder(candidates.size(), projected);
        Map<Integer, Integer> newIndexByOldIndex = new HashMap<>();
        List<TaskKnowledge<T>> normalized = new ArrayList<>(candidates.size());
        for (int newIndex = 0; newIndex < stableOrder.size(); newIndex++) {
            int oldIndex = stableOrder.get(newIndex);
            newIndexByOldIndex.put(oldIndex, newIndex);
            normalized.add(candidates.get(oldIndex));
        }

        List<TaskDependency> normalizedDependencies = projected.stream()
                .map(edge -> new TaskDependency(
                        newIndexByOldIndex.get(edge.predecessorTaskIndex()),
                        newIndexByOldIndex.get(edge.successorTaskIndex())))
                .sorted(Comparator.comparingInt(TaskDependency::predecessorTaskIndex)
                        .thenComparingInt(TaskDependency::successorTaskIndex))
                .toList();
        return new Result<>(List.copyOf(normalized), normalizedDependencies);
    }

    private List<Integer> stableTopologicalOrder(int taskCount, Set<TaskDependency> dependencies) {
        List<Set<Integer>> successors = new ArrayList<>(taskCount);
        int[] indegree = new int[taskCount];
        for (int index = 0; index < taskCount; index++) {
            successors.add(new HashSet<>());
        }
        for (TaskDependency dependency : dependencies) {
            if (successors.get(dependency.predecessorTaskIndex()).add(dependency.successorTaskIndex())) {
                indegree[dependency.successorTaskIndex()]++;
            }
        }

        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (int index = 0; index < taskCount; index++) {
            if (indegree[index] == 0) {
                ready.add(index);
            }
        }
        List<Integer> order = new ArrayList<>(taskCount);
        while (!ready.isEmpty()) {
            int current = ready.remove();
            order.add(current);
            for (Integer successor : successors.get(current).stream().sorted().toList()) {
                if (--indegree[successor] == 0) {
                    ready.add(successor);
                }
            }
        }
        if (order.size() != taskCount) {
            throw invalid("任务级前置关系形成环，无法安全调整计划顺序");
        }
        return order;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID, message);
    }
}
