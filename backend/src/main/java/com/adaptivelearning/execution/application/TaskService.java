package com.adaptivelearning.execution.application;

import com.adaptivelearning.execution.domain.*;
import com.adaptivelearning.execution.infrastructure.ExecutionMappers.CompletionSummaryMapper;
import com.adaptivelearning.execution.infrastructure.ExecutionMappers.NoteMapper;
import com.adaptivelearning.execution.infrastructure.ExecutionMappers.NoteVersionMapper;
import com.adaptivelearning.execution.infrastructure.ExecutionMappers.SessionMapper;
import com.adaptivelearning.execution.infrastructure.LearningTaskMapper;
import com.adaptivelearning.planning.domain.OutboxEventEntity;
import com.adaptivelearning.planning.infrastructure.PlanningMappers.OutboxMapper;
import com.adaptivelearning.shared.ai.AiModelException;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.support.application.AuditService;
import com.adaptivelearning.support.application.HashingService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskService {
    private final LearningTaskMapper taskMapper;
    private final SessionMapper sessionMapper;
    private final StudySessionService sessions;
    private final NoteMapper noteMapper;
    private final NoteVersionMapper noteVersionMapper;
    private final CompletionSummaryMapper summaryMapper;
    private final OutboxMapper outboxMapper;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final HashingService hashing;
    private final AuditService audit;
    private final PythonAiServiceClient pythonAi;

    public record TaskView(LearningTaskEntity task, String scheduleStatus, boolean ownerGoalPaused,
                           long effectiveSeconds) {
    }

    public record UpdateInput(String title, String description, String priority, Integer estimatedMinutes,
                              Instant scheduledStart, Instant dueAt, Integer version) {
    }

    public record CompletionInput(String learnedText, String difficultyText, Integer qualityLevel,
                                  Integer confidenceLevel, String remainingQuestions) {
    }

    public record NoteView(StudyNoteEntity note, StudyNoteVersionEntity currentVersion) {
    }

    public List<TaskView> list(LocalDate date, String status) {
        long user = SecurityUtils.currentUserId();
        String timezone = jdbc.queryForObject("SELECT timezone FROM sys_user WHERE id=?", String.class, user);
        ZoneId zone = ZoneId.of(timezone);
        var q = new LambdaQueryWrapper<LearningTaskEntity>().eq(LearningTaskEntity::getUserId, user).eq(status != null, LearningTaskEntity::getLifecycleStatus, status).orderByAsc(LearningTaskEntity::getScheduledStart, LearningTaskEntity::getDueAt);
        if (date != null) {
            Instant from = date.atStartOfDay(zone).toInstant(), to = date.plusDays(1).atStartOfDay(zone).toInstant();
            q.and(x -> x.ge(LearningTaskEntity::getScheduledStart, from).lt(LearningTaskEntity::getScheduledStart, to).or().ge(LearningTaskEntity::getDueAt, from).lt(LearningTaskEntity::getDueAt, to));
        }
        return taskMapper.selectList(q).stream().map(this::view).toList();
    }

    public TaskView get(String id) {
        return view(owned(id));
    }

    @Transactional
    public TaskView update(String id, UpdateInput i) {
        LearningTaskEntity t = owned(id);
        version(t, i.version());
        if (Boolean.TRUE.equals(t.getLockedSchedule()) && (i.scheduledStart() != null || i.dueAt() != null))
            throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED, "锁定任务的时间不能直接修改");
        if (i.title() != null) {
            if (i.title().length() < 2 || i.title().length() > 200) bad();
            t.setTitle(i.title());
        }
        if (i.description() != null) t.setDescription(i.description());
        if (i.priority() != null) t.setPriority(i.priority());
        if (i.estimatedMinutes() != null) {
            if (i.estimatedMinutes() < 10 || i.estimatedMinutes() > 120) bad();
            t.setEstimatedMinutes(i.estimatedMinutes());
        }
        if (i.scheduledStart() != null) t.setScheduledStart(i.scheduledStart());
        if (i.dueAt() != null) t.setDueAt(i.dueAt());
        if (t.getScheduledStart() != null && t.getDueAt() != null && !t.getDueAt().isAfter(t.getScheduledStart()))
            bad();
        if (taskMapper.updateById(t) != 1) conflict();
        return view(owned(id));
    }

    @Transactional
    public TaskView transition(String id, String target, String reason, CompletionInput summary, boolean confirmed) {
        LearningTaskEntity t = owned(id);
        String from = t.getLifecycleStatus();
        TaskStatusPolicy.require(from, target);
        if ("CANCELED".equals(target) && (!confirmed || reason == null || reason.isBlank()))
            throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED, "取消任务必须确认并填写原因");
        if ("CANCELED".equals(target)) {
            StudySessionEntity active = active(t.getId());
            if (active != null) sessions.stop(active.getPublicId());
        }
        if ("PAUSED".equals(target)) {
            StudySessionEntity running = running(t.getId());
            if (running != null) sessions.pause(running.getPublicId());
        }
        if ("COMPLETED".equals(target)) {
            StudySessionEntity active = active(t.getId());
            if (active != null) sessions.stop(active.getPublicId());
            saveSummary(t, summary);
            t.setProgressPercent(new BigDecimal("100"));
            t.setCompletedAt(Instant.now());
        }
        if ("PAUSED".equals(target) && "COMPLETED".equals(from)) {
            if (!confirmed || reason == null || reason.isBlank())
                throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED, "撤销完成必须确认并说明原因");
            t.setCompletedAt(null);
            t.setProgressPercent(BigDecimal.ZERO);
        }
        t.setLifecycleStatus(target);
        if (taskMapper.updateById(t) != 1) conflict();
        jdbc.update("INSERT INTO task_status_history(id,task_id,from_status,to_status,reason,event_at,operator_type,correlation_id) VALUES(?,?,?,?,?,?,?,?)", IdWorker.getId(), t.getId(), from, target, reason, Instant.now(), "USER", UUID.randomUUID().toString());
        event(t, "TaskStatusChanged", Map.of("from", from, "to", target));
        audit.record("TASK_" + target, "LEARNING_TASK", id, from, target + ":" + reason, "SUCCESS");
        return view(owned(id));
    }

    @Transactional
    public StudySessionEntity startTask(String id, boolean startTimer) {
        LearningTaskEntity t = owned(id);
        if ("NOT_STARTED".equals(t.getLifecycleStatus()) || "PAUSED".equals(t.getLifecycleStatus()))
            transition(id, "IN_PROGRESS", "开始执行", null, false);
        else if (!"IN_PROGRESS".equals(t.getLifecycleStatus()))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "当前任务不能开始");
        return startTimer ? sessions.start(id) : null;
    }

    public NoteView note(String taskId) {
        LearningTaskEntity t = owned(taskId);
        StudyNoteEntity n = noteMapper.selectOne(new LambdaQueryWrapper<StudyNoteEntity>().eq(StudyNoteEntity::getTaskId, t.getId()).eq(StudyNoteEntity::getUserId, t.getUserId()));
        if (n == null) return null;
        StudyNoteVersionEntity v = noteVersionMapper.selectOne(new LambdaQueryWrapper<StudyNoteVersionEntity>().eq(StudyNoteVersionEntity::getNoteId, n.getId()).eq(StudyNoteVersionEntity::getVersionNo, n.getCurrentVersionNo()));
        return new NoteView(n, v);
    }

    @Transactional
    public NoteView saveNote(String taskId, String title, String markdown, Integer version) {
        LearningTaskEntity t = owned(taskId);
        if (markdown == null || markdown.length() > 100_000)
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "笔记内容不能超过 100000 字");
        StudyNoteEntity n = noteMapper.selectOne(new LambdaQueryWrapper<StudyNoteEntity>().eq(StudyNoteEntity::getTaskId, t.getId()));
        if (n == null) {
            n = new StudyNoteEntity();
            n.setPublicId(UUID.randomUUID().toString());
            n.setUserId(t.getUserId());
            n.setTaskId(t.getId());
            n.setTitle(title);
            n.setCurrentVersionNo(0);
            noteMapper.insert(n);
        } else {
            version(n, version);
            n.setTitle(title);
        }
        StudyNoteVersionEntity nv = new StudyNoteVersionEntity();
        nv.setNoteId(n.getId());
        nv.setVersionNo(n.getCurrentVersionNo() + 1);
        nv.setContentMarkdown(markdown);
        nv.setContentHash(hashing.sha256(markdown));
        nv.setCreatedAt(Instant.now());
        nv.setCreatedBy(t.getUserId());
        noteVersionMapper.insert(nv);
        n.setCurrentVersionNo(nv.getVersionNo());
        noteMapper.updateById(n);
        return note(taskId);
    }

    private void saveSummary(LearningTaskEntity t, CompletionInput i) {
        if (i == null) return;
        if (i.qualityLevel() != null && (i.qualityLevel() < 1 || i.qualityLevel() > 5) || i.confidenceLevel() != null && (i.confidenceLevel() < 1 || i.confidenceLevel() > 5))
            bad();
        TaskCompletionSummaryEntity s = summaryMapper.selectOne(new LambdaQueryWrapper<TaskCompletionSummaryEntity>().eq(TaskCompletionSummaryEntity::getTaskId, t.getId()));
        if (s == null) {
            s = new TaskCompletionSummaryEntity();
            s.setTaskId(t.getId());
            s.setUserId(t.getUserId());
            s.setRevisionNo(1);
            s.setCreatedAt(Instant.now());
        } else s.setRevisionNo(s.getRevisionNo() + 1);
        s.setLearnedText(i.learnedText());
        s.setDifficultyText(i.difficultyText());
        s.setQualityLevel(i.qualityLevel());
        s.setConfidenceLevel(i.confidenceLevel());
        s.setRemainingQuestions(i.remainingQuestions());
        if (s.getId() == null) summaryMapper.insert(s);
        else summaryMapper.updateById(s);
    }

    private TaskView view(LearningTaskEntity t) {
        long seconds = Optional.ofNullable(jdbc.queryForObject("SELECT COALESCE(SUM(effective_seconds),0) FROM study_session WHERE task_id=? AND status='COMPLETED' AND deleted_at IS NULL", Long.class, t.getId())).orElse(0L);
        Integer paused = jdbc.queryForObject("SELECT COUNT(*) FROM learning_goal WHERE id=? AND status='PAUSED'", Integer.class, t.getGoalId());
        return new TaskView(t, schedule(t), paused != null && paused > 0, seconds);
    }

    private String schedule(LearningTaskEntity t) {
        Instant now = Instant.now();
        if ("COMPLETED".equals(t.getLifecycleStatus()))
            return t.getDueAt() != null && t.getCompletedAt().isAfter(t.getDueAt()) ? "LATE_COMPLETED" : "ON_TIME_COMPLETED";
        if ("CANCELED".equals(t.getLifecycleStatus())) return "CANCELED";
        if (t.getScheduledStart() == null && t.getDueAt() == null) return "UNSCHEDULED";
        if (t.getDueAt() != null && now.isAfter(t.getDueAt())) return "OVERDUE";
        if (t.getDueAt() != null && now.plus(Duration.ofMinutes(30)).isAfter(t.getDueAt())) return "DUE_SOON";
        return "UPCOMING";
    }

    private void event(LearningTaskEntity t, String type, Object payload) {
        OutboxEventEntity e = new OutboxEventEntity();
        e.setAggregateType("LEARNING_TASK");
        e.setAggregateId(t.getPublicId());
        e.setEventType(type);
        e.setPayloadJson(json(payload));
        e.setCorrelationId(UUID.randomUUID().toString());
        e.setStatus("PENDING");
        e.setAttempts(0);
        e.setNextRetryAt(Instant.now());
        e.setCreatedAt(Instant.now());
        outboxMapper.insert(e);
    }

    private StudySessionEntity running(long task) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<StudySessionEntity>().eq(StudySessionEntity::getTaskId, task).eq(StudySessionEntity::getStatus, "RUNNING"));
    }

    private StudySessionEntity active(long task) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<StudySessionEntity>().eq(StudySessionEntity::getTaskId, task).in(StudySessionEntity::getStatus, "RUNNING", "PAUSED"));
    }

    public PythonAiServiceClient.TaskChatResult chat(String id, String message,
                                                     List<PythonAiServiceClient.TaskChatTurn> history) {
        LearningTaskEntity t = owned(id);
        if (message == null || message.isBlank() || message.length() > 2000)
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "消息不能为空且不能超过 2000 字");
        if (!pythonAi.isConfigured()) throw new AiModelException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE);
        long userId = SecurityUtils.currentUserId();
        List<Long> spaceIds = jdbc.query("SELECT id FROM knowledge_space WHERE (user_id=? OR visibility='PUBLIC') AND deleted_at IS NULL",
                (rs, row) -> rs.getLong(1), userId);
        List<PythonAiServiceClient.TaskChatTurn> cleaned = (history == null ? List.<PythonAiServiceClient.TaskChatTurn>of() : history).stream()
                .filter(turn -> turn != null && turn.content() != null && !turn.content().isBlank())
                .filter(turn -> "USER".equals(turn.role()) || "ASSISTANT".equals(turn.role()))
                .map(turn -> new PythonAiServiceClient.TaskChatTurn(turn.role(),
                        turn.content().length() > 2000 ? turn.content().substring(0, 2000) : turn.content()))
                .toList();
        if (cleaned.size() > 6) cleaned = cleaned.subList(cleaned.size() - 6, cleaned.size());
        return pythonAi.taskChat(new PythonAiServiceClient.TaskChatRequest(
                userId, t.getTitle(), t.getTaskType(), message.trim(), cleaned, spaceIds, 5));
    }

    private LearningTaskEntity owned(String id) {
        LearningTaskEntity t = taskMapper.selectOne(new LambdaQueryWrapper<LearningTaskEntity>().eq(LearningTaskEntity::getPublicId, id).eq(LearningTaskEntity::getUserId, SecurityUtils.currentUserId()));
        if (t == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资源不存在");
        return t;
    }

    private void version(com.adaptivelearning.shared.domain.BaseEntity e, Integer v) {
        if (v == null || !v.equals(e.getVersion())) conflict();
    }

    private String json(Object v) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void bad() {
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "任务参数不合法");
    }

    private void conflict() {
        throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "资源版本冲突，请刷新后重试");
    }
}

