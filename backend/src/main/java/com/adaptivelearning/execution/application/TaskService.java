package com.adaptivelearning.execution.application;

import com.adaptivelearning.execution.domain.*;
import com.adaptivelearning.execution.infrastructure.ExecutionMappers.CompletionSummaryMapper;
import com.adaptivelearning.execution.infrastructure.ExecutionMappers.NoteMapper;
import com.adaptivelearning.execution.infrastructure.ExecutionMappers.NoteVersionMapper;
import com.adaptivelearning.execution.infrastructure.ExecutionMappers.SessionMapper;
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
    private final TutoringSessionMapper tutoringSessionMapper;
    private final TutoringMessageMapper tutoringMessageMapper;
    private final OutboxMapper outboxMapper;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final HashingService hashing;
    private final AuditService audit;
    private final PythonAiServiceClient pythonAi;
    private final LearningBlockService learningBlocks;

    @Value("${app.task-chat.history-max-messages:400}")
    private int taskChatHistoryMaxMessages;
    @Value("${app.task-chat.history-max-characters:600000}")
    private int taskChatHistoryMaxCharacters;

    public record DependencyView(String publicId, String title, String status) {
    }

    public record KnowledgeSourceView(long chunkId, String documentId, String documentName,
                                      int chunkNo, String quotePreview,
                                      Integer pageFrom, Integer pageTo) {
    }

    public record TaskView(LearningTaskEntity task, String scheduleStatus, boolean ownerGoalPaused,
                           long effectiveSeconds, List<DependencyView> prerequisites,
                           List<KnowledgeSourceView> knowledgeSources,
                           Map<String, Object> learningBlock) {
    }

    public record TaskGraphNode(String publicId, String title, String goalId, String goalName,
                                String taskType, String priority, int estimatedMinutes,
                                Instant scheduledStart, Instant dueAt, String status,
                                boolean availableToday, String temporalState) {
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
                SELECT t.public_id,t.title,g.public_id,g.name,t.task_type,t.priority,t.estimated_minutes,
                       t.scheduled_start,t.due_at,t.lifecycle_status,g.status AS goal_status,
                       NOT EXISTS (
                           SELECT 1
                           FROM task_dependency dependency
                           JOIN learning_task predecessor ON predecessor.id=dependency.predecessor_task_id
                           LEFT JOIN learning_block predecessor_block ON predecessor_block.task_id=predecessor.id
                               AND predecessor_block.deleted_at IS NULL
                           WHERE dependency.successor_task_id=t.id
                             AND (predecessor.lifecycle_status<>'COMPLETED'
                                  OR (predecessor_block.id IS NOT NULL AND predecessor_block.status<>'COMPLETED'))
                       ) AS prerequisites_ready
                FROM learning_task t
                JOIN learning_goal g ON g.id=t.goal_id AND g.deleted_at IS NULL
                WHERE t.user_id=?
                  AND t.origin_plan_version_id IS NOT NULL
                  AND t.deleted_at IS NULL
                  AND t.lifecycle_status<>'CANCELED'
                ORDER BY g.created_at,t.scheduled_start,t.due_at,t.id
                """, (rs, row) -> {
            var scheduledTimestamp = rs.getTimestamp("scheduled_start");
            var dueTimestamp = rs.getTimestamp("due_at");
            Instant scheduledStart = scheduledTimestamp == null ? null : scheduledTimestamp.toInstant();
            Instant dueAt = dueTimestamp == null ? null : dueTimestamp.toInstant();
            Instant taskTime = scheduledStart != null ? scheduledStart : dueAt;
            LocalDate taskDate = taskTime == null ? null : taskTime.atZone(zone).toLocalDate();
            boolean availableToday = today.equals(taskDate)
                    && "ACTIVE".equals(rs.getString("goal_status"))
                    && rs.getBoolean("prerequisites_ready")
                    && !"COMPLETED".equals(rs.getString("lifecycle_status"));
            String temporalState = taskDate == null ? "UNSCHEDULED"
                    : taskDate.isAfter(today) ? "FUTURE"
                    : taskDate.isBefore(today) ? "PAST"
                    : "TODAY";
            return new TaskGraphNode(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getInt(7),
                    scheduledStart, dueAt, rs.getString(10), availableToday, temporalState);
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
                  AND predecessor.lifecycle_status<>'CANCELED'
                  AND successor.lifecycle_status<>'CANCELED'
                ORDER BY predecessor.scheduled_start,successor.scheduled_start
                """, (rs, row) -> new TaskGraphEdge(rs.getString(1), rs.getString(2)), user, user);
        return new TaskGraphView(today, timezone, nodes, edges);
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
        if ("IN_PROGRESS".equals(target)) {
            requireExecutableToday(t);
            requirePredecessorsComplete(t);
        }
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
            learningBlocks.markAssessmentRequired(t.getId());
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
        requireExecutableToday(t);
        if ("NOT_STARTED".equals(t.getLifecycleStatus()) || "PAUSED".equals(t.getLifecycleStatus()))
            transition(id, "IN_PROGRESS", "开始执行", null, false);
        else if (!"IN_PROGRESS".equals(t.getLifecycleStatus()))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "当前任务不能开始");
        return startTimer ? sessions.start(id) : null;
    }

    private void requireExecutableToday(LearningTaskEntity task) {
        String timezone = jdbc.queryForObject("SELECT timezone FROM sys_user WHERE id=?", String.class,
                task.getUserId());
        ZoneId zone = ZoneId.of(timezone);
        Instant anchor = task.getScheduledStart() != null ? task.getScheduledStart() : task.getDueAt();
        if (anchor == null || !LocalDate.now(zone).equals(anchor.atZone(zone).toLocalDate())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "只能开始今天排期的任务");
        }
        String goalStatus = jdbc.queryForObject(
                "SELECT status FROM learning_goal WHERE id=? AND user_id=? AND deleted_at IS NULL",
                String.class, task.getGoalId(), task.getUserId());
        if (!"ACTIVE".equals(goalStatus)) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "目标未处于进行中，不能执行任务");
        }
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
        List<DependencyView> prerequisites = jdbc.query("""
                SELECT p.public_id,p.title,
                       CASE WHEN p.lifecycle_status='COMPLETED' AND block.id IS NOT NULL AND block.status<>'COMPLETED'
                            THEN 'ASSESSMENT_REQUIRED' ELSE p.lifecycle_status END AS effective_status
                FROM task_dependency d
                JOIN learning_task p ON p.id=d.predecessor_task_id
                LEFT JOIN learning_block block ON block.task_id=p.id AND block.deleted_at IS NULL
                WHERE d.successor_task_id=? AND p.deleted_at IS NULL
                ORDER BY p.scheduled_start,p.id
                """, (rs, row) -> new DependencyView(rs.getString(1), rs.getString(2), rs.getString(3)), t.getId());
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
            return new KnowledgeSourceView(rs.getLong("id"), rs.getString("public_id"),
                    rs.getString("display_name"), rs.getInt("chunk_no"), preview,
                    (Integer) rs.getObject("page_from"), (Integer) rs.getObject("page_to"));
        }, t.getId());
        return new TaskView(t, schedule(t), paused != null && paused > 0, seconds, prerequisites,
                knowledgeSources, learningBlocks.summaryForTask(t.getId()));
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
        // 同一任务可能残留多条 PAUSED 会话（每次开始计时都会新建会话），必须取最新一条，
        // selectOne 匹配多条会抛 TooManyResultsException 导致完成/取消事务整体回滚
        return sessionMapper.selectList(new LambdaQueryWrapper<StudySessionEntity>()
                        .eq(StudySessionEntity::getTaskId, task)
                        .in(StudySessionEntity::getStatus, "RUNNING", "PAUSED")
                        .orderByDesc(StudySessionEntity::getCreatedAt))
                .stream().findFirst().orElse(null);
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

    private void requirePredecessorsComplete(LearningTaskEntity task) {
        List<String> blockers = jdbc.query("""
                SELECT p.title
                FROM task_dependency d
                JOIN learning_task p ON p.id=d.predecessor_task_id
                LEFT JOIN learning_block block ON block.task_id=p.id AND block.deleted_at IS NULL
                WHERE d.successor_task_id=? AND p.deleted_at IS NULL
                  AND (p.lifecycle_status<>'COMPLETED' OR block.id IS NOT NULL AND block.status<>'COMPLETED')
                ORDER BY p.scheduled_start,p.id
                """, (rs, row) -> rs.getString(1), task.getId());
        if (!blockers.isEmpty())
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    "请先完成前置任务：" + String.join("、", blockers));
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
