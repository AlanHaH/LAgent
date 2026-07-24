package com.adaptivelearning.execution.domain;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;

import java.util.Map;
import java.util.Set;

public final class TaskStatusPolicy {
    private TaskStatusPolicy() {
    }

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
        "NOT_STARTED", Set.of("IN_PROGRESS", "BLOCKED", "CANCELED"),
        "IN_PROGRESS", Set.of("PAUSED", "BLOCKED", "COMPLETED", "CANCELED"),
        "PAUSED", Set.of("IN_PROGRESS", "BLOCKED", "COMPLETED", "CANCELED"),
        "BLOCKED", Set.of("IN_PROGRESS", "CANCELED"),
        "COMPLETED", Set.of("PAUSED"),
        "CANCELED", Set.of());

    public static void require(String from, String to) {
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "任务不能从 " + from + " 转换为 " + to, Map.of("from", from, "to", to));
    }
}

