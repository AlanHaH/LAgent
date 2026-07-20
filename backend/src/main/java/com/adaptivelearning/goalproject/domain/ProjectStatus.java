package com.adaptivelearning.goalproject.domain;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;

import java.util.Map;
import java.util.Set;

public enum ProjectStatus {
    DRAFT, ACTIVE, PAUSED, COMPLETED, CANCELED, ARCHIVED;

    private static final Map<ProjectStatus, Set<ProjectStatus>> TRANSITIONS = Map.of(
            DRAFT, Set.of(ACTIVE, CANCELED),
            ACTIVE, Set.of(PAUSED, COMPLETED, CANCELED),
            PAUSED, Set.of(ACTIVE, CANCELED),
            COMPLETED, Set.of(ARCHIVED),
            CANCELED, Set.of(ARCHIVED),
            ARCHIVED, Set.of());

    public void requireCanTransitionTo(ProjectStatus target) {
        if (!TRANSITIONS.get(this).contains(target)) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    "项目不能从 " + this + " 转换为 " + target);
        }
    }
}

