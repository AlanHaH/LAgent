package com.adaptivelearning.execution.application;

import com.adaptivelearning.execution.domain.LearningTaskEntity;
import com.adaptivelearning.execution.domain.StudySessionEntity;
import com.adaptivelearning.execution.domain.StudySessionPauseEntity;
import com.adaptivelearning.execution.infrastructure.ExecutionMappers.SessionMapper;
import com.adaptivelearning.execution.infrastructure.ExecutionMappers.SessionPauseMapper;
import com.adaptivelearning.execution.infrastructure.LearningTaskMapper;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.support.infrastructure.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StudySessionService {
    private final SessionMapper sessionMapper;
    private final SessionPauseMapper pauseMapper;
    private final LearningTaskMapper taskMapper;
    private final UserMapper userMapper;
    private final TaskExecutionEligibilityService eligibility;
    private final ExecutionIntegrityAuditService integrityAudit;
    private final JdbcTemplate jdbc;

    public record DayAllocation(LocalDate date, long effectiveSeconds) { }
    public record SessionView(StudySessionEntity session, List<DayAllocation> dayAllocations) { }
    public record ActiveSessionView(String sessionId, String taskId, String taskTitle, String status,
                                    Instant startedAt, Instant pausedAt, long effectiveSeconds,
                                    Instant serverNow) { }

    public List<ActiveSessionView> active() {
        long userId = SecurityUtils.currentUserId();
        List<StudySessionEntity> active = sessionMapper.findActiveByUser(userId);
        if (active.stream().filter(item -> "RUNNING".equals(item.getStatus())).count() > 1) {
            integrityAudit.activeSessionInvalid(String.valueOf(userId));
            dependencyInvalid();
        }
        Instant now = Instant.now();
        List<ActiveSessionView> result = new ArrayList<>();
        for (StudySessionEntity session : active) {
            LearningTaskEntity task = taskMapper.selectById(session.getTaskId());
            if (task == null || !task.getUserId().equals(userId)) {
                integrityAudit.activeSessionInvalid(String.valueOf(userId));
                dependencyInvalid();
            }
            List<StudySessionPauseEntity> openPauses = pauseMapper.selectList(
                    new LambdaQueryWrapper<StudySessionPauseEntity>()
                            .eq(StudySessionPauseEntity::getSessionId, session.getId())
                            .isNull(StudySessionPauseEntity::getResumedAt));
            boolean paused = "PAUSED".equals(session.getStatus());
            if ((paused && openPauses.size() != 1) || (!paused && !openPauses.isEmpty())) {
                integrityAudit.activeSessionInvalid(String.valueOf(userId));
                dependencyInvalid();
            }
            Instant pausedAt = paused ? openPauses.get(0).getPausedAt() : null;
            Instant effectiveEnd = paused ? pausedAt : now;
            long pauseSeconds = session.getPauseSeconds() == null ? 0L : session.getPauseSeconds();
            long effective = Math.max(0L,
                    Duration.between(session.getStartedAt(), effectiveEnd).getSeconds() - pauseSeconds);
            result.add(new ActiveSessionView(session.getPublicId(), task.getPublicId(), task.getTitle(),
                    session.getStatus(), session.getStartedAt(), pausedAt, effective, now));
        }
        return List.copyOf(result);
    }

    @Transactional
    public StudySessionEntity start(String taskPublicId) {
        LearningTaskEntity task = eligibility.lockAndRequire(taskPublicId,
                TaskExecutionEligibilityPolicy.Action.SESSION_START).task();
        return startForLockedTask(task);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StudySessionEntity startForLockedTask(LearningTaskEntity task) {
        if (!"IN_PROGRESS".equals(task.getLifecycleStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    "任务必须先通过开始执行校验才能计时");
        }
        List<StudySessionEntity> userRunning = sessionMapper.lockRunningByUser(task.getUserId());
        if (userRunning.size() > 1) conflict("用户存在多条运行中的学习会话");
        List<StudySessionEntity> taskOpen = sessionMapper.lockOpenByTask(task.getId());
        if (taskOpen.size() > 1) conflict("当前任务存在多条未结束会话");
        if (!userRunning.isEmpty()) {
            StudySessionEntity running = userRunning.get(0);
            if (running.getTaskId().equals(task.getId()) && taskOpen.size() == 1
                    && taskOpen.get(0).getId().equals(running.getId())) return running;
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT,
                    "已有其他任务正在计时，请先暂停或停止",
                    java.util.Map.of("runningSessionId", running.getPublicId()));
        }
        if (!taskOpen.isEmpty()) {
            conflict("当前任务已有未结束会话，请恢复或结束原会话");
        }

        StudySessionEntity session = new StudySessionEntity();
        session.setPublicId(UUID.randomUUID().toString());
        session.setSessionGroupId(UUID.randomUUID().toString());
        session.setUserId(task.getUserId());
        session.setTaskId(task.getId());
        session.setSource("AUTO");
        session.setStartedAt(Instant.now());
        session.setPauseSeconds(0L);
        session.setEffectiveSeconds(0L);
        session.setStatus("RUNNING");
        if (sessionMapper.insert(session) != 1) conflict("学习会话创建冲突");
        return session;
    }

    @Transactional
    public StudySessionEntity pause(String publicId) {
        LockedSession locked = lockForCleanup(publicId);
        return pauseLocked(locked.session());
    }

    @Transactional
    public StudySessionEntity resume(String publicId) {
        StudySessionEntity preview = owned(publicId);
        LearningTaskEntity task = eligibility.lockAndRequire(preview.getTaskId(), preview.getUserId(),
                TaskExecutionEligibilityPolicy.Action.SESSION_RESUME).task();

        List<StudySessionEntity> userRunning = sessionMapper.lockRunningByUser(preview.getUserId());
        if (!userRunning.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "已有其他任务正在计时");
        }
        List<StudySessionEntity> taskOpen = sessionMapper.lockOpenByTask(task.getId());
        if (taskOpen.size() != 1 || !taskOpen.get(0).getId().equals(preview.getId())) {
            conflict("当前任务的未结束会话状态冲突");
        }
        StudySessionEntity session = sessionMapper.lockOwnedById(preview.getId(), preview.getUserId());
        if (session == null || !session.getTaskId().equals(task.getId())) notFound();
        if ("RUNNING".equals(session.getStatus())) return session;
        if (!"PAUSED".equals(session.getStatus())) invalid();

        StudySessionPauseEntity pause = openPauseLocked(session.getId());
        Instant now = Instant.now();
        long seconds = Math.max(0, Duration.between(pause.getPausedAt(), now).getSeconds());
        pause.setResumedAt(now);
        pause.setSeconds(seconds);
        if (pauseMapper.updateById(pause) != 1) conflict("暂停区间已经变化");
        session.setPauseSeconds(session.getPauseSeconds() + seconds);
        session.setStatus("RUNNING");
        if (sessionMapper.updateById(session) != 1) conflict("学习会话已经变化");
        return session;
    }

    @Transactional
    public SessionView stop(String publicId) {
        LockedSession locked = lockForCleanup(publicId);
        return view(stopLocked(locked.session(), true));
    }

    /** Caller already owns the user/parent/task lock interval. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void pauseRunningForTaskLocked(long taskId, long userId) {
        List<StudySessionEntity> open = sessionMapper.lockOpenByTask(taskId);
        if (open.size() > 1) {
            for (StudySessionEntity session : open) {
                if (!session.getUserId().equals(userId)) dependencyInvalid();
                stopLocked(session, false);
            }
            return;
        }
        for (StudySessionEntity session : open) {
            if (!session.getUserId().equals(userId)) dependencyInvalid();
            if ("RUNNING".equals(session.getStatus())) pauseLocked(session);
        }
    }

    /** Caller already owns the user/parent/task lock interval. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void stopOpenForTaskLocked(long taskId, long userId) {
        for (StudySessionEntity session : sessionMapper.lockOpenByTask(taskId)) {
            if (!session.getUserId().equals(userId)) dependencyInvalid();
            stopLocked(session, false);
        }
    }

    @Transactional
    public SessionView manual(String taskPublicId, Instant startedAt, Instant endedAt, String reason) {
        LearningTaskEntity task = ownedTask(taskPublicId);
        if (reason == null || reason.isBlank() || startedAt == null || endedAt == null || !endedAt.isAfter(startedAt))
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "手工补录必须提供合法时间和原因");
        if (endedAt.isAfter(Instant.now()))
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "手工补录的结束时间不能晚于当前时间");
        if ("CANCELED".equals(task.getLifecycleStatus()))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "已取消任务不能补录学习时间");
        long seconds = Duration.between(startedAt, endedAt).getSeconds();
        if (seconds < 60 || seconds > 24 * 3600)
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "手工补录时长必须为 1 分钟至 24 小时");
        Integer overlaps = jdbc.queryForObject("SELECT COUNT(*) FROM study_session WHERE user_id=? AND status<>'DISCARDED' AND started_at<? AND COALESCE(ended_at,UTC_TIMESTAMP(6))>? AND deleted_at IS NULL", Integer.class, task.getUserId(), endedAt, startedAt);
        if (overlaps != null && overlaps > 0)
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "手工补录与已有会话重叠");
        StudySessionEntity session = new StudySessionEntity();
        session.setPublicId(UUID.randomUUID().toString());
        session.setSessionGroupId(UUID.randomUUID().toString());
        session.setUserId(task.getUserId());
        session.setTaskId(task.getId());
        session.setSource("MANUAL");
        session.setStartedAt(startedAt);
        session.setEndedAt(endedAt);
        session.setPauseSeconds(0L);
        session.setEffectiveSeconds(seconds);
        session.setStatus("COMPLETED");
        session.setManualReason(reason);
        if (sessionMapper.insert(session) != 1) conflict("手工学习记录创建冲突");
        return view(session);
    }

    public SessionView get(String publicId) {
        return view(owned(publicId));
    }

    public SessionView view(StudySessionEntity session) {
        String timezone = jdbc.queryForObject("SELECT timezone FROM sys_user WHERE id=?", String.class,
                session.getUserId());
        return new SessionView(session, allocate(session, ZoneId.of(timezone)));
    }

    public static List<DayAllocation> allocate(StudySessionEntity session, ZoneId zone) {
        if (session.getEndedAt() == null) return List.of();
        ZonedDateTime cursor = session.getStartedAt().atZone(zone), end = session.getEndedAt().atZone(zone);
        long totalWall = Duration.between(session.getStartedAt(), session.getEndedAt()).getSeconds();
        if (totalWall <= 0) return List.of();
        List<DayAllocation> result = new ArrayList<>();
        long assigned = 0;
        while (cursor.isBefore(end)) {
            ZonedDateTime boundary = cursor.toLocalDate().plusDays(1).atStartOfDay(zone);
            ZonedDateTime segmentEnd = boundary.isBefore(end) ? boundary : end;
            long wall = Duration.between(cursor, segmentEnd).getSeconds();
            long effective = segmentEnd.equals(end) ? session.getEffectiveSeconds() - assigned
                    : Math.round((double) session.getEffectiveSeconds() * wall / totalWall);
            result.add(new DayAllocation(cursor.toLocalDate(), Math.max(0, effective)));
            assigned += effective;
            cursor = segmentEnd;
        }
        return result;
    }

    private StudySessionEntity pauseLocked(StudySessionEntity session) {
        if ("PAUSED".equals(session.getStatus())) return session;
        if (!"RUNNING".equals(session.getStatus())) invalid();
        if (!pauseMapper.lockOpenBySession(session.getId()).isEmpty()) dependencyInvalid();

        StudySessionPauseEntity pause = new StudySessionPauseEntity();
        pause.setSessionId(session.getId());
        pause.setPausedAt(Instant.now());
        pause.setSeconds(0L);
        if (pauseMapper.insert(pause) != 1) conflict("暂停区间创建冲突");
        session.setStatus("PAUSED");
        if (sessionMapper.updateById(session) != 1) conflict("学习会话已经变化");
        return session;
    }

    private StudySessionEntity stopLocked(StudySessionEntity session, boolean enforceDurationLimit) {
        if ("COMPLETED".equals(session.getStatus())) return session;
        if (!Set.of("RUNNING", "PAUSED").contains(session.getStatus())) invalid();
        Instant now = Instant.now();
        if ("PAUSED".equals(session.getStatus())) {
            StudySessionPauseEntity pause = openPauseLocked(session.getId());
            long seconds = Math.max(0, Duration.between(pause.getPausedAt(), now).getSeconds());
            pause.setResumedAt(now);
            pause.setSeconds(seconds);
            if (pauseMapper.updateById(pause) != 1) conflict("暂停区间已经变化");
            session.setPauseSeconds(session.getPauseSeconds() + seconds);
        } else if (!pauseMapper.lockOpenBySession(session.getId()).isEmpty()) {
            dependencyInvalid();
        }
        long effective = Math.max(0,
                Duration.between(session.getStartedAt(), now).getSeconds() - session.getPauseSeconds());
        if (enforceDurationLimit && effective > 240 * 60)
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "单段有效学习时长超过 240 分钟，请确认并拆分记录");
        session.setEndedAt(now);
        session.setEffectiveSeconds(effective);
        session.setStatus("COMPLETED");
        if (sessionMapper.updateById(session) != 1) conflict("学习会话已经变化");
        return session;
    }

    private StudySessionPauseEntity openPauseLocked(long sessionId) {
        List<StudySessionPauseEntity> pauses = pauseMapper.lockOpenBySession(sessionId);
        if (pauses.size() != 1)
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "活动暂停区间状态异常");
        return pauses.get(0);
    }

    private LockedSession lockForCleanup(String publicId) {
        StudySessionEntity preview = owned(publicId);
        if (userMapper.lockById(preview.getUserId()) == null) notFound();
        LearningTaskEntity task = taskMapper.lockById(preview.getTaskId());
        if (task == null || !task.getUserId().equals(preview.getUserId())) notFound();
        List<StudySessionEntity> open = sessionMapper.lockOpenByTask(task.getId());
        if (open.size() > 1) dependencyInvalid();
        StudySessionEntity session = open.stream().filter(item -> item.getId().equals(preview.getId()))
                .findFirst().orElseGet(() -> sessionMapper.lockOwnedById(preview.getId(), preview.getUserId()));
        if (session == null || !session.getTaskId().equals(task.getId())) notFound();
        return new LockedSession(task, session);
    }

    private StudySessionEntity owned(String publicId) {
        StudySessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<StudySessionEntity>()
                .eq(StudySessionEntity::getPublicId, publicId)
                .eq(StudySessionEntity::getUserId, SecurityUtils.currentUserId()));
        if (session == null) notFound();
        return session;
    }

    private LearningTaskEntity ownedTask(String publicId) {
        LearningTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<LearningTaskEntity>()
                .eq(LearningTaskEntity::getPublicId, publicId)
                .eq(LearningTaskEntity::getUserId, SecurityUtils.currentUserId()));
        if (task == null) notFound();
        return task;
    }

    private void dependencyInvalid() {
        throw new BusinessException(ErrorCode.DEPENDENCY_DATA_INVALID, "学习会话数据异常，已拒绝操作");
    }

    private void invalid() {
        throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "学习会话状态不允许该操作");
    }

    private void conflict(String message) {
        throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, message);
    }

    private void notFound() {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资源不存在");
    }

    private record LockedSession(LearningTaskEntity task, StudySessionEntity session) { }
}
