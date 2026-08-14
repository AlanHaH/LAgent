package com.adaptivelearning.execution.application;

import com.adaptivelearning.execution.domain.*;
import com.adaptivelearning.execution.infrastructure.ExecutionMappers.CompletionSummaryMapper;
import com.adaptivelearning.execution.infrastructure.ExecutionMappers.NoteMapper;
import com.adaptivelearning.execution.infrastructure.ExecutionMappers.NoteVersionMapper;
import com.adaptivelearning.execution.infrastructure.LearningTaskMapper;
import com.adaptivelearning.execution.infrastructure.TutoringMappers.TutoringMessageMapper;
import com.adaptivelearning.execution.infrastructure.TutoringMappers.TutoringSessionMapper;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskService {
    private final LearningTaskMapper taskMapper;
    private final StudySessionService sessions;
    private final NoteMapper noteMapper;
    private final NoteVersionMapper noteVersionMapper;
    private final CompletionSummaryMapper summaryMapper;
    private final TutoringSessionMapper tutoringSessionMapper;
    private final TutoringMessageMapper tutoringMessageMapper;
    private final OutboxMapper outboxMapper;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final HashingService hashing;
    private final AuditService audit;
    private final PythonAiServiceClient pythonAi;
    private final LearningBlockService learningBlocks;
    private final TaskExecutionEligibilityService eligibility;
    private final TaskCancellationService cancellations;
    private final TaskAcceptancePolicy acceptancePolicy;

    @Value("${app.task-chat.history-max-messages:400}")
    private int taskChatHistoryMaxMessages;
    @Value("${app.task-chat.history-max-characters:600000}")
    private int taskChatHistoryMaxCharacters;

    public record DependencyView(String publicId, String title, String status) {
    }

    public record KnowledgeSourceView(String chunkId, String documentId, String documentName,
                                      int chunkNo, String quotePreview,
                                      Integer pageFrom, Integer pageTo) {
    }

    public record ParentContextView(String publicId, String name, String status) { }

    public record CompletionSummaryView(String learnedText, String difficultyText,
                                        Integer qualityLevel, Integer confidenceLevel,
                                        String remainingQuestions, int revisionNo,
                                        Instant createdAt) { }

    public record TaskView(LearningTaskEntity task, String scheduleStatus, boolean ownerGoalPaused,
                           long effectiveSeconds, List<DependencyView> prerequisites,
                           List<KnowledgeSourceView> knowledgeSources,
                           Map<String, Object> learningBlock,
                           String blockedReason, boolean replanRequired, boolean availableToday,
                           ParentContextView project, ParentContextView milestone,
                           TaskAcceptancePolicy.Snapshot acceptance,
                           CompletionSummaryView completionSummary) {
    }

    public record TaskGraphNode(String publicId, String title, String goalId, String goalName,
                                String taskType, String priority, int estimatedMinutes,
                                Instant scheduledStart, Instant dueAt, String status,
                                boolean availableToday, String temporalState,
                                String blockedReason, boolean replanRequired) {
    }

    public record TaskGraphEdge(String source, String target) {
    }

    public record TaskGraphView(LocalDate today, String timezone, List<TaskGraphNode> nodes,
                                List<TaskGraphEdge> edges) {
    }

    public record UpdateInput(String title, String description, String priority, Integer estimatedMinutes,
                              Instant scheduledStart, Instant dueAt, Integer version) {
    }

    public record CompletionInput(String learnedText, String difficultyText, Integer qualityLevel,
                                  Integer confidenceLevel, String remainingQuestions) {
    }

    public record NoteView(StudyNoteEntity note, StudyNoteVersionEntity currentVersion) {
    }

    public record TaskChatResponse(String sessionId, String answer, String mode,
                                   List<PythonAiServiceClient.TaskChatCitation> citations) {
    }

    public record TaskChatHistory(String sessionId, List<Map<String, Object>> messages) {
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

    public TaskGraphView graph() {
        long user = SecurityUtils.currentUserId();
        String timezone = jdbc.queryForObject("SELECT timezone FROM sys_user WHERE id=?", String.class, user);
        ZoneId zone = ZoneId.of(timezone);
        LocalDate today = LocalDate.now(zone);
        List<TaskGraphNode> nodes = jdbc.query("""
                SELECT t.id,t.public_id,t.user_id,t.goal_id,t.project_id,t.milestone_id,t.origin_plan_version_id,
                       t.title,g.public_id AS goal_public_id,g.name AS goal_name,t.task_type,t.priority,
                       t.estimated_minutes,t.scheduled_start,t.due_at,t.lifecycle_status
                FROM learning_task t
                JOIN learning_goal g ON g.id=t.goal_id AND g.deleted_at IS NULL
                WHERE t.user_id=?
                  AND t.origin_plan_version_id IS NOT NULL
                  AND t.deleted_at IS NULL
                ORDER BY g.created_at,t.scheduled_start,t.due_at,t.id
                """, (rs, row) -> {
            LearningTaskEntity task = new LearningTaskEntity();
            task.setId(rs.getLong("id"));
            task.setPublicId(rs.getString("public_id"));
            task.setUserId(rs.getLong("user_id"));
            task.setGoalId(rs.getLong("goal_id"));
            task.setProjectId((Long) rs.getObject("project_id"));
            task.setMilestoneId((Long) rs.getObject("milestone_id"));
            task.setOriginPlanVersionId((Long) rs.getObject("origin_plan_version_id"));
            task.setTitle(rs.getString("title"));
            task.setTaskType(rs.getString("task_type"));
            task.setPriority(rs.getString("priority"));
            task.setEstimatedMinutes(rs.getInt("estimated_minutes"));
            var scheduledTimestamp = rs.getTimestamp("scheduled_start");
            var dueTimestamp = rs.getTimestamp("due_at");
            Instant scheduledStart = scheduledTimestamp == null ? null : scheduledTimestamp.toInstant();
            Instant dueAt = dueTimestamp == null ? null : dueTimestamp.toInstant();
            task.setScheduledStart(scheduledStart);
            task.setDueAt(dueAt);
            task.setLifecycleStatus(rs.getString("lifecycle_status"));
            Instant taskTime = scheduledStart != null ? scheduledStart : dueAt;
            LocalDate taskDate = taskTime == null ? null : taskTime.atZone(zone).toLocalDate();
            TaskExecutionEligibilityService.Evaluation evaluation = eligibility.evaluateReadOnly(task);
            String temporalState = taskDate == null ? "UNSCHEDULED"
                    : taskDate.isAfter(today) ? "FUTURE"
                    : taskDate.isBefore(today) ? "PAST"
                    : "TODAY";
            return new TaskGraphNode(
                    task.getPublicId(), task.getTitle(), rs.getString("goal_public_id"),
                    rs.getString("goal_name"), task.getTaskType(), task.getPriority(),
                    task.getEstimatedMinutes(), scheduledStart, dueAt, task.getLifecycleStatus(),
                    evaluation.decision().allowed(), temporalState, evaluation.decision().code(),
                    evaluation.decision().replanRequired());
        }, user);
        List<TaskGraphEdge> edges = jdbc.query("""
                SELECT predecessor.public_id,successor.public_id
                FROM task_dependency dependency
                JOIN learning_task predecessor ON predecessor.id=dependency.predecessor_task_id
                JOIN learning_task successor ON successor.id=dependency.successor_task_id
                WHERE predecessor.user_id=? AND successor.user_id=?
                  AND predecessor.origin_plan_version_id IS NOT NULL
                  AND successor.origin_plan_version_id IS NOT NULL
                  AND predecessor.deleted_at IS NULL AND successor.deleted_at IS NULL
                ORDER BY predecessor.scheduled_start,successor.scheduled_start
                """, (rs, row) -> new TaskGraphEdge(rs.getString(1), rs.getString(2)), user, user);
        return new TaskGraphView(today, timezone, nodes, edges);
    }

    @Transactional
    public TaskView update(String id, UpdateInput i) {
        LearningTaskEntity t = owned(id);
        version(t, i.version());
        List<String> changedFields = changedFields(i);
        if (changedFields.isEmpty()) return view(t);
        if (Set.of("COMPLETED", "CANCELED").contains(t.getLifecycleStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    "终态任务不能修改正式业务字段");
        }
        if (t.getOriginPlanVersionId() != null) {
            throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED,
                    "正式计划任务必须通过计划提案修改");
        }
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
        audit.record("TASK_LEGACY_UPDATED", "LEARNING_TASK", t.getPublicId(),
                "version=" + i.version(), "fields=" + String.join(",", changedFields), "SUCCESS");
        return view(owned(id));
    }

    @Transactional
    public TaskView transition(String id, String target, String reason, CompletionInput summary,
                               boolean confirmed, TaskAcceptancePolicy.Confirmation acceptance) {
        if ("CANCELED".equals(target)) {
            if (!confirmed)
                throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED, "取消任务必须二次确认");
            cancellations.cancelUser(id, reason);
            return view(owned(id));
        }

        LearningTaskEntity task = switch (target) {
            case "IN_PROGRESS" -> eligibility.lockAndRequire(id,
                    TaskExecutionEligibilityPolicy.Action.TASK_START).task();
            case "COMPLETED" -> eligibility.lockAndRequire(id,
                    TaskExecutionEligibilityPolicy.Action.TASK_COMPLETE).task();
            default -> eligibility.lockOwnedTask(id);
        };
        applyTransitionLocked(task, target, reason, summary, confirmed, acceptance);
        return view(owned(id));
    }

    @Transactional
    public TaskView transition(String id, String target, String reason,
                               CompletionInput summary, boolean confirmed) {
        return transition(id, target, reason, summary, confirmed, null);
    }

    @Transactional
    public StudySessionEntity startTask(String id, boolean startTimer) {
        LearningTaskEntity task = eligibility.lockAndRequire(id,
                TaskExecutionEligibilityPolicy.Action.TASK_START).task();
        if ("NOT_STARTED".equals(task.getLifecycleStatus()) || "PAUSED".equals(task.getLifecycleStatus())
                || "BLOCKED".equals(task.getLifecycleStatus()))
            applyTransitionLocked(task, "IN_PROGRESS", "开始执行", null, false, null);
        else if (!"IN_PROGRESS".equals(task.getLifecycleStatus()))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "当前任务不能开始");
        return startTimer ? sessions.startForLockedTask(task) : null;
    }

    private void applyTransitionLocked(LearningTaskEntity task, String target, String reason,
                                       CompletionInput summary, boolean confirmed,
                                       TaskAcceptancePolicy.Confirmation acceptance) {
        String from = task.getLifecycleStatus();
        TaskStatusPolicy.require(from, target);
        if ("PAUSED".equals(target)) {
            if ("COMPLETED".equals(from)) {
                if (!confirmed || reason == null || reason.isBlank())
                    throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED,
                            "撤销完成必须确认并说明原因");
                requireNoStartedSuccessors(task);
                task.setCompletedAt(null);
                task.setProgressPercent(BigDecimal.ZERO);
            } else {
                sessions.pauseRunningForTaskLocked(task.getId(), task.getUserId());
            }
        }
        if ("COMPLETED".equals(target)) {
            TaskAcceptancePolicy.Snapshot acceptanceSnapshot = acceptancePolicy.snapshot(task.getAcceptanceJson());
            acceptancePolicy.requireConfirmed(acceptanceSnapshot, acceptance);
            CompletionInput validatedSummary = validatedSummary(summary);
            learningBlocks.markAssessmentRequired(task.getId());
            sessions.stopOpenForTaskLocked(task.getId(), task.getUserId());
            saveSummary(task, validatedSummary);
            task.setProgressPercent(new BigDecimal("100"));
            task.setCompletedAt(Instant.now());
        }
        task.setLifecycleStatus(target);
        if (taskMapper.updateById(task) != 1) conflict();
        String correlationId = UUID.randomUUID().toString();
        if (jdbc.update("INSERT INTO task_status_history(id,task_id,from_status,to_status,reason,event_at,operator_type,correlation_id) VALUES(?,?,?,?,?,?,?,?)",
                IdWorker.getId(), task.getId(), from, target, reason, Instant.now(), "USER", correlationId) != 1)
            conflict();
        event(task, "TaskStatusChanged", Map.of("from", from, "to", target, "source", "USER"));
        audit.record("TASK_" + target, "LEARNING_TASK", task.getPublicId(), from,
                target + ":" + reason, "SUCCESS");
    }

    private void requireNoStartedSuccessors(LearningTaskEntity task) {
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_dependency WHERE predecessor_task_id=?", Integer.class, task.getId());
        List<String> validStatuses = jdbc.query("""
                SELECT successor.lifecycle_status
                FROM task_dependency dependency
                JOIN learning_task successor ON successor.id=dependency.successor_task_id
                WHERE dependency.predecessor_task_id=? AND successor.user_id=?
                  AND successor.deleted_at IS NULL
                ORDER BY successor.id FOR UPDATE
                """, (rs, row) -> rs.getString(1), task.getId(), task.getUserId());
        if (total == null || total != validStatuses.size())
            throw new BusinessException(ErrorCode.DEPENDENCY_DATA_INVALID,
                    "后继任务依赖数据异常，不能撤销完成");
        if (validStatuses.stream().anyMatch(status -> !Set.of("NOT_STARTED", "BLOCKED", "CANCELED").contains(status)))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    "后继任务已经开始，不能撤销前置任务完成状态");
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

    private CompletionInput validatedSummary(CompletionInput input) {
        if (input == null || input.learnedText() == null) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "完成总结不能为空");
        }
        String learned = input.learnedText().trim();
        if (learned.isEmpty() || learned.length() > 3000) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "完成总结长度必须为 1 至 3000 字");
        }
        if (input.qualityLevel() != null && (input.qualityLevel() < 1 || input.qualityLevel() > 5)
                || input.confidenceLevel() != null
                && (input.confidenceLevel() < 1 || input.confidenceLevel() > 5)) {
            bad();
        }
        String difficulty = trimOptional(input.difficultyText());
        String questions = trimOptional(input.remainingQuestions());
        if (difficulty != null && difficulty.length() > 3000
                || questions != null && questions.length() > 3000) bad();
        return new CompletionInput(learned, difficulty, input.qualityLevel(),
                input.confidenceLevel(), questions);
    }

    private String trimOptional(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private TaskView view(LearningTaskEntity t) {
        long seconds = Optional.ofNullable(jdbc.queryForObject("SELECT COALESCE(SUM(effective_seconds),0) FROM study_session WHERE task_id=? AND status='COMPLETED' AND deleted_at IS NULL", Long.class, t.getId())).orElse(0L);
        Integer paused = jdbc.queryForObject("SELECT COUNT(*) FROM learning_goal WHERE id=? AND status='PAUSED'", Integer.class, t.getGoalId());
        TaskExecutionEligibilityService.Evaluation evaluation = eligibility.evaluateReadOnly(t);
        List<DependencyView> prerequisites = "DEPENDENCY_DATA_INVALID".equals(evaluation.decision().code())
                ? List.of() : jdbc.query("""
                SELECT p.public_id,p.title,
                       CASE WHEN p.lifecycle_status='COMPLETED' AND block.id IS NOT NULL AND block.status<>'COMPLETED'
                            THEN 'ASSESSMENT_REQUIRED' ELSE p.lifecycle_status END AS effective_status
                FROM task_dependency d
                JOIN learning_task p ON p.id=d.predecessor_task_id
                LEFT JOIN learning_block block ON block.task_id=p.id AND block.deleted_at IS NULL
                WHERE d.successor_task_id=? AND p.user_id=? AND p.deleted_at IS NULL
                ORDER BY p.scheduled_start,p.id
                """, (rs, row) -> new DependencyView(rs.getString(1), rs.getString(2), rs.getString(3)),
                t.getId(), t.getUserId());
        List<KnowledgeSourceView> knowledgeSources = jdbc.query("""
                SELECT chunk.id,document.public_id,document.display_name,chunk.chunk_no,
                       chunk.text,chunk.page_from,chunk.page_to
                FROM task_knowledge_source source
                JOIN knowledge_chunk chunk ON chunk.id=source.chunk_id
                JOIN document_version version ON version.id=chunk.document_version_id
                JOIN knowledge_document document ON document.id=version.document_id
                WHERE source.task_id=? AND document.deleted_at IS NULL
                ORDER BY document.display_name,chunk.chunk_no
                """, (rs, row) -> {
            String preview = Optional.ofNullable(rs.getString("text")).orElse("")
                    .replaceAll("\\s+", " ").trim();
            if (preview.length() > 300) preview = preview.substring(0, 300);
            return new KnowledgeSourceView(String.valueOf(rs.getLong("id")), rs.getString("public_id"),
                    rs.getString("display_name"), rs.getInt("chunk_no"), preview,
                    (Integer) rs.getObject("page_from"), (Integer) rs.getObject("page_to"));
        }, t.getId());
        ParentContextView project = projectContext(t);
        ParentContextView milestone = milestoneContext(t);
        TaskCompletionSummaryEntity completion = summaryMapper.selectOne(
                new LambdaQueryWrapper<TaskCompletionSummaryEntity>()
                        .eq(TaskCompletionSummaryEntity::getTaskId, t.getId())
                        .eq(TaskCompletionSummaryEntity::getUserId, t.getUserId()));
        CompletionSummaryView completionView = completion == null ? null : new CompletionSummaryView(
                completion.getLearnedText(), completion.getDifficultyText(), completion.getQualityLevel(),
                completion.getConfidenceLevel(), completion.getRemainingQuestions(),
                completion.getRevisionNo(), completion.getCreatedAt());
        return new TaskView(t, schedule(t), paused != null && paused > 0, seconds, prerequisites,
                knowledgeSources, learningBlocks.summaryForTask(t.getId()), evaluation.decision().code(),
                evaluation.decision().replanRequired(), evaluation.decision().allowed(), project, milestone,
                acceptancePolicy.snapshot(t.getAcceptanceJson()), completionView);
    }

    private ParentContextView projectContext(LearningTaskEntity task) {
        if (task.getProjectId() == null) return null;
        List<ParentContextView> rows = jdbc.query("""
                SELECT public_id,name,status FROM learning_project
                WHERE id=? AND user_id=? AND deleted_at IS NULL
                """, (rs, row) -> new ParentContextView(rs.getString(1), rs.getString(2), rs.getString(3)),
                task.getProjectId(), task.getUserId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ParentContextView milestoneContext(LearningTaskEntity task) {
        if (task.getMilestoneId() == null) return null;
        List<ParentContextView> rows = jdbc.query("""
                SELECT milestone.public_id,milestone.name,milestone.status
                FROM milestone
                JOIN learning_project project ON project.id=milestone.project_id
                WHERE milestone.id=? AND project.user_id=?
                  AND milestone.deleted_at IS NULL AND project.deleted_at IS NULL
                """, (rs, row) -> new ParentContextView(rs.getString(1), rs.getString(2), rs.getString(3)),
                task.getMilestoneId(), task.getUserId());
        return rows.isEmpty() ? null : rows.get(0);
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
        if (outboxMapper.insert(e) != 1) conflict();
    }

    public TaskChatHistory chatHistory(String id) {
        LearningTaskEntity task = owned(id);
        TutoringSessionEntity session = activeTutoringSession(task.getId());
        if (session == null) return new TaskChatHistory(null, List.of());
        List<TutoringMessageEntity> messages = tutoringMessageMapper.selectList(
                new LambdaQueryWrapper<TutoringMessageEntity>()
                        .eq(TutoringMessageEntity::getSessionId, session.getId())
                        .orderByAsc(TutoringMessageEntity::getCreatedAt));
        return new TaskChatHistory(session.getPublicId(), messages.stream().map(this::chatMessageView).toList());
    }

    @Transactional
    public void clearChat(String id) {
        LearningTaskEntity task = owned(id);
        TutoringSessionEntity session = activeTutoringSession(task.getId());
        if (session == null) return;
        session.setStatus("CLOSED");
        session.setEndedAt(Instant.now());
        tutoringSessionMapper.updateById(session);
    }

    @Transactional
    public TaskChatResponse chat(String id, String message,
                                 List<PythonAiServiceClient.TaskChatTurn> history) {
        LearningTaskEntity t = owned(id);
        if (message == null || message.isBlank() || message.length() > 2000)
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "消息不能为空且不能超过 2000 字");
        if (!pythonAi.isConfigured()) throw new AiModelException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE);
        long userId = SecurityUtils.currentUserId();
        List<Long> spaceIds = jdbc.query("SELECT id FROM knowledge_space WHERE (user_id=? OR visibility='PUBLIC') AND deleted_at IS NULL",
                (rs, row) -> rs.getLong(1), userId);
        TutoringSessionEntity session = getOrCreateTutoringSession(t, spaceIds);
        List<TutoringMessageEntity> persisted = tutoringMessageMapper.selectList(
                new LambdaQueryWrapper<TutoringMessageEntity>()
                        .eq(TutoringMessageEntity::getSessionId, session.getId())
                        .orderByDesc(TutoringMessageEntity::getCreatedAt)
                        .last("LIMIT " + Math.max(1, Math.min(taskChatHistoryMaxMessages, 1000))));
        Collections.reverse(persisted);
        List<PythonAiServiceClient.TaskChatTurn> cleaned = persisted.stream()
                .map(item -> new PythonAiServiceClient.TaskChatTurn(item.getRole(), item.getContent()))
                .toList();
        if (cleaned.isEmpty()) cleaned = (history == null ? List.<PythonAiServiceClient.TaskChatTurn>of() : history).stream()
                .filter(turn -> turn != null && turn.content() != null && !turn.content().isBlank())
                .filter(turn -> "USER".equals(turn.role()) || "ASSISTANT".equals(turn.role()))
                .map(turn -> new PythonAiServiceClient.TaskChatTurn(turn.role(),
                        turn.content().length() > 2000 ? turn.content().substring(0, 2000) : turn.content()))
                .toList();
        cleaned = fitTaskChatHistory(cleaned);
        PythonAiServiceClient.TaskChatResult result = pythonAi.taskChat(new PythonAiServiceClient.TaskChatRequest(
                userId, t.getTitle(), t.getTaskType(), message.trim(), cleaned, spaceIds, 5));
        saveTutoringMessage(session.getId(), "USER", message.trim(), null);
        saveTutoringMessage(session.getId(), "ASSISTANT", result.answer(),
                json(Map.of("mode", result.mode(), "citations", result.citations())));
        return new TaskChatResponse(session.getPublicId(), result.answer(), result.mode(), result.citations());
    }

    private List<PythonAiServiceClient.TaskChatTurn> fitTaskChatHistory(
            List<PythonAiServiceClient.TaskChatTurn> history) {
        int maxMessages = Math.max(1, Math.min(taskChatHistoryMaxMessages, 1000));
        int maxCharacters = Math.max(10_000, Math.min(taskChatHistoryMaxCharacters, 900_000));
        List<PythonAiServiceClient.TaskChatTurn> selected = new ArrayList<>();
        int characters = 0;
        for (int index = history.size() - 1; index >= 0 && selected.size() < maxMessages; index--) {
            PythonAiServiceClient.TaskChatTurn turn = history.get(index);
            if (turn == null || turn.content() == null || turn.content().isBlank()) continue;
            int next = turn.content().length();
            if (!selected.isEmpty() && characters + next > maxCharacters) break;
            if (next > maxCharacters) {
                String tail = turn.content().substring(turn.content().length() - maxCharacters);
                selected.add(new PythonAiServiceClient.TaskChatTurn(turn.role(), tail));
                break;
            }
            selected.add(turn);
            characters += next;
        }
        Collections.reverse(selected);
        return List.copyOf(selected);
    }

    private TutoringSessionEntity getOrCreateTutoringSession(LearningTaskEntity task, List<Long> spaceIds) {
        TutoringSessionEntity session = activeTutoringSession(task.getId());
        if (session != null) return session;
        session = new TutoringSessionEntity();
        session.setPublicId(UUID.randomUUID().toString());
        session.setUserId(task.getUserId());
        session.setTaskId(task.getId());
        session.setMode("TASK_CONTEXT");
        session.setStatus("ACTIVE");
        session.setKnowledgeScopeJson(json(spaceIds));
        session.setStartedAt(Instant.now());
        tutoringSessionMapper.insert(session);
        return session;
    }

    private TutoringSessionEntity activeTutoringSession(long taskId) {
        return tutoringSessionMapper.selectOne(new LambdaQueryWrapper<TutoringSessionEntity>()
                .eq(TutoringSessionEntity::getTaskId, taskId)
                .eq(TutoringSessionEntity::getUserId, SecurityUtils.currentUserId())
                .eq(TutoringSessionEntity::getStatus, "ACTIVE")
                .orderByDesc(TutoringSessionEntity::getStartedAt)
                .last("LIMIT 1"));
    }

    private void saveTutoringMessage(long sessionId, String role, String content, String metadataJson) {
        TutoringMessageEntity item = new TutoringMessageEntity();
        item.setPublicId(UUID.randomUUID().toString());
        item.setSessionId(sessionId);
        item.setRole(role);
        item.setContent(content);
        item.setGuidanceLevel(1);
        item.setMetadataJson(metadataJson);
        item.setCreatedAt(Instant.now());
        tutoringMessageMapper.insert(item);
    }

    private Map<String, Object> chatMessageView(TutoringMessageEntity item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("publicId", item.getPublicId());
        result.put("role", item.getRole());
        result.put("content", item.getContent());
        result.put("createdAt", item.getCreatedAt());
        if (item.getMetadataJson() != null && !item.getMetadataJson().isBlank()) {
            try {
                Map<String, Object> metadata = objectMapper.readValue(item.getMetadataJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                result.putAll(metadata);
            } catch (JsonProcessingException ignored) {
                result.put("citations", List.of());
            }
        }
        result.putIfAbsent("citations", List.of());
        return result;
    }

    private LearningTaskEntity owned(String id) {
        LearningTaskEntity t = taskMapper.selectOne(new LambdaQueryWrapper<LearningTaskEntity>().eq(LearningTaskEntity::getPublicId, id).eq(LearningTaskEntity::getUserId, SecurityUtils.currentUserId()));
        if (t == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资源不存在");
        return t;
    }

    private List<String> changedFields(UpdateInput input) {
        List<String> fields = new ArrayList<>();
        if (input.title() != null) fields.add("title");
        if (input.description() != null) fields.add("description");
        if (input.priority() != null) fields.add("priority");
        if (input.estimatedMinutes() != null) fields.add("estimatedMinutes");
        if (input.scheduledStart() != null) fields.add("scheduledStart");
        if (input.dueAt() != null) fields.add("dueAt");
        return fields;
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
