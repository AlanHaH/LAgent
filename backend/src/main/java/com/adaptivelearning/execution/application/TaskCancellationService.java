package com.adaptivelearning.execution.application;

import com.adaptivelearning.execution.domain.LearningTaskEntity;
import com.adaptivelearning.execution.domain.TaskStatusPolicy;
import com.adaptivelearning.execution.infrastructure.LearningTaskMapper;
import com.adaptivelearning.planning.domain.OutboxEventEntity;
import com.adaptivelearning.planning.infrastructure.PlanningMappers.OutboxMapper;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.support.application.AuditService;
import com.adaptivelearning.support.infrastructure.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskCancellationService {
    public enum Source {
        USER,
        PLAN_PUBLICATION,
        PARENT_GOAL,
        PARENT_PROJECT,
        PARENT_MILESTONE
    }

    public enum QuiesceMode { PAUSE, STOP }

    private final LearningTaskMapper taskMapper;
    private final UserMapper userMapper;
    private final StudySessionService sessions;
    private final OutboxMapper outboxMapper;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditService audit;

    /** Always joins TaskService's command transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LearningTaskEntity cancelUser(String taskPublicId, String reason) {
        long userId = SecurityUtils.currentUserId();
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED,
                    "取消任务必须确认并填写原因");
        }
        userLock(userId);
        LearningTaskEntity task = taskMapper.lockOwnedByPublicId(taskPublicId, userId);
        if (task == null) notFound();
        return cancelLocked(task, Source.USER, reason.trim());
    }

    /** Always joins the surrounding M05 publication transaction; never starts an independent commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LearningTaskEntity cancelForPlanPublication(long taskId, long userId, String reason) {
        userLock(userId);
        LearningTaskEntity task = taskMapper.lockById(taskId);
        if (task == null || !Objects.equals(task.getUserId(), userId)) notFound();
        return cancelLocked(task, Source.PLAN_PUBLICATION,
                reason == null || reason.isBlank() ? "计划版本发布取消任务" : reason);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void quiesceGoal(long goalId, long userId, QuiesceMode mode) {
        quiesce(taskMapper.lockByGoal(goalId, userId), userId, mode);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void quiesceProject(long projectId, long userId, QuiesceMode mode) {
        quiesce(taskMapper.lockByProject(projectId, userId), userId, mode);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void quiesceMilestone(long milestoneId, long userId, QuiesceMode mode) {
        quiesce(taskMapper.lockByMilestone(milestoneId, userId), userId, mode);
    }

    private LearningTaskEntity cancelLocked(LearningTaskEntity task, Source source, String reason) {
        if ("CANCELED".equals(task.getLifecycleStatus())) return task;
        TaskStatusPolicy.require(task.getLifecycleStatus(), "CANCELED");
        String from = task.getLifecycleStatus();
        sessions.stopOpenForTaskLocked(task.getId(), task.getUserId());
        task.setLifecycleStatus("CANCELED");
        if (taskMapper.updateById(task) != 1) conflict();

        String correlationId = UUID.randomUUID().toString();
        int history = jdbc.update("""
                INSERT INTO task_status_history(
                  id,task_id,from_status,to_status,reason,event_at,operator_type,correlation_id
                ) VALUES(?,?,?,?,?,?,?,?)
                """, IdWorker.getId(), task.getId(), from, "CANCELED", reason,
                Instant.now(), source.name(), correlationId);
        if (history != 1) conflict();
        event(task, correlationId, Map.of("from", from, "to", "CANCELED", "source", source.name()));
        audit.record("TASK_CANCELED", "LEARNING_TASK", task.getPublicId(), from,
                "CANCELED:" + source.name() + ":" + reason, "SUCCESS");
        return task;
    }

    private void quiesce(List<LearningTaskEntity> tasks, long userId, QuiesceMode mode) {
        for (LearningTaskEntity task : tasks) {
            if (!Objects.equals(task.getUserId(), userId)) {
                throw new BusinessException(ErrorCode.DEPENDENCY_DATA_INVALID,
                        "父对象任务归属异常，已拒绝状态传播");
            }
            if (mode == QuiesceMode.PAUSE) sessions.pauseRunningForTaskLocked(task.getId(), userId);
            else sessions.stopOpenForTaskLocked(task.getId(), userId);
        }
    }

    private void event(LearningTaskEntity task, String correlationId, Object payload) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setAggregateType("LEARNING_TASK");
        event.setAggregateId(task.getPublicId());
        event.setEventType("TaskStatusChanged");
        event.setPayloadJson(json(payload));
        event.setCorrelationId(correlationId);
        event.setStatus("PENDING");
        event.setAttempts(0);
        event.setNextRetryAt(Instant.now());
        event.setCreatedAt(Instant.now());
        if (outboxMapper.insert(event) != 1) conflict();
    }

    private void userLock(long userId) {
        if (userMapper.lockById(userId) == null) notFound();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void conflict() {
        throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT,
                "任务状态已经变化，请刷新后重试");
    }

    private void notFound() {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资源不存在");
    }
}
