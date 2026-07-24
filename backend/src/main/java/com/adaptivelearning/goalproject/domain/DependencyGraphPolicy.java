package com.adaptivelearning.goalproject.domain;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;

import java.util.*;

public final class DependencyGraphPolicy {
    private DependencyGraphPolicy() {
    }

    public record Edge(long predecessor, long successor) {
    }

    public static void requireAcyclic(Collection<Edge> edges) {
        Map<Long, List<Long>> graph = new HashMap<>();
        Set<Long> nodes = new HashSet<>();
        for (Edge edge : edges) {
            if (edge.predecessor() == edge.successor()) cycle(List.of(edge.predecessor(), edge.successor()));
            graph.computeIfAbsent(edge.predecessor(), ignored -> new ArrayList<>()).add(edge.successor());
            nodes.add(edge.predecessor());
            nodes.add(edge.successor());
        }
        Set<Long> visiting = new HashSet<>();
        Set<Long> visited = new HashSet<>();
        Deque<Long> path = new ArrayDeque<>();
        for (Long node : nodes) dfs(node, graph, visiting, visited, path);
    }

    private static void dfs(Long node, Map<Long, List<Long>> graph, Set<Long> visiting,
                            Set<Long> visited, Deque<Long> path) {
        if (visited.contains(node)) return;
        if (!visiting.add(node)) {
            List<Long> cycle = new ArrayList<>(path);
            cycle.add(node);
            cycle(cycle);
        }
        path.addLast(node);
        for (Long next : graph.getOrDefault(node, List.of())) dfs(next, graph, visiting, visited, path);
        path.removeLast();
        visiting.remove(node);
        visited.add(node);
    }

    private static void cycle(List<Long> path) {
        throw new BusinessException(ErrorCode.DEPENDENCY_CYCLE_DETECTED, "依赖关系形成环",
            Map.of("path", path));
    }
}

