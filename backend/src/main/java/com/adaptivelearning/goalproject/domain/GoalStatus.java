package com.adaptivelearning.goalproject.domain;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;

import java.util.Map;
import java.util.Set;

public enum GoalStatus {
    DRAFT, ACTIVE, PAUSED, COMPLETED, CANCELED;

    private static final Map<GoalStatus, Set<GoalStatus>> TRANSITIONS = Map.of(
        DRAFT, Set.of(ACTIVE, CANCELED),
        ACTIVE, Set.of(PAUSED, COMPLETED, CANCELED),
        PAUSED, Set.of(ACTIVE, CANCELED),
        COMPLETED, Set.of(),
        CANCELED, Set.of());

    public void requireCanTransitionTo(GoalStatus target) {
        if (!TRANSITIONS.get(this).contains(target)) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                "目标不能从 " + this + " 转换为 " + target,
                Map.of("from", name(), "to", target.name()));
        }
    }
}

