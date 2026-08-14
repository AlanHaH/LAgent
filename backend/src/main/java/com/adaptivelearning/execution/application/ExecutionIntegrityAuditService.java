package com.adaptivelearning.execution.application;

import com.adaptivelearning.support.application.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists integrity rejection evidence even when the rejected command transaction rolls back. */
@Service
@RequiredArgsConstructor
public class ExecutionIntegrityAuditService {
    private final AuditService audit;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void taskDependencyInvalid(String taskPublicId) {
        audit.record("TASK_DEPENDENCY_DATA_INTEGRITY", "LEARNING_TASK", taskPublicId,
                null, "DEPENDENCY_DATA_INVALID", "REJECTED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void activeSessionInvalid(String userResourceId) {
        audit.record("STUDY_SESSION_DATA_INTEGRITY", "USER", userResourceId,
                null, "ACTIVE_SESSION_DATA_INVALID", "REJECTED");
    }
}
