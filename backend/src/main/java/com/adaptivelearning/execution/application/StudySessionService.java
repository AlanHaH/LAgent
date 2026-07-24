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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class StudySessionService {
    private final SessionMapper sessionMapper;
    private final SessionPauseMapper pauseMapper;
    private final LearningTaskMapper taskMapper;
    private final JdbcTemplate jdbc;

    public record DayAllocation(LocalDate date, long effectiveSeconds) {
    }

    public record SessionView(StudySessionEntity session, List<DayAllocation> dayAllocations) {
    }

    @Transactional
    public StudySessionEntity start(String taskPublicId) {
        LearningTaskEntity task = ownedTask(taskPublicId);
        if (Set.of("CANCELED", "COMPLETED").contains(task.getLifecycleStatus()))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "当前任务不能开始计时");
        StudySessionEntity running = sessionMapper.selectOne(new LambdaQueryWrapper<StudySessionEntity>().eq(StudySessionEntity::getUserId, task.getUserId()).eq(StudySessionEntity::getStatus, "RUNNING"));
        if (running != null) {
            if (running.getTaskId().equals(task.getId())) return running;
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "已有任务正在计时，请先暂停或停止", Map.of("runningSessionId", running.getPublicId()));
        }
        StudySessionEntity s = new StudySessionEntity();
        s.setPublicId(UUID.randomUUID().toString());
        s.setSessionGroupId(UUID.randomUUID().toString());
        s.setUserId(task.getUserId());
        s.setTaskId(task.getId());
        s.setSource("AUTO");
        s.setStartedAt(Instant.now());
        s.setPauseSeconds(0L);
        s.setEffectiveSeconds(0L);
        s.setStatus("RUNNING");
        sessionMapper.insert(s);
        return s;
    }

    @Transactional
    public StudySessionEntity pause(String publicId) {
        StudySessionEntity s = owned(publicId);
        if ("PAUSED".equals(s.getStatus())) return s;
        if (!"RUNNING".equals(s.getStatus())) invalid();
        StudySessionPauseEntity p = new StudySessionPauseEntity();
        p.setSessionId(s.getId());
        p.setPausedAt(Instant.now());
        p.setSeconds(0L);
        pauseMapper.insert(p);
        s.setStatus("PAUSED");
        sessionMapper.updateById(s);
        return s;
    }

    @Transactional
    public StudySessionEntity resume(String publicId) {
        StudySessionEntity s = owned(publicId);
        if ("RUNNING".equals(s.getStatus())) return s;
        if (!"PAUSED".equals(s.getStatus())) invalid();
        StudySessionEntity another = sessionMapper.selectOne(new LambdaQueryWrapper<StudySessionEntity>().eq(StudySessionEntity::getUserId, s.getUserId()).eq(StudySessionEntity::getStatus, "RUNNING"));
        if (another != null) throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "已有其他任务正在计时");
        StudySessionPauseEntity p = openPause(s.getId());
        Instant now = Instant.now();
        long seconds = Duration.between(p.getPausedAt(), now).getSeconds();
        p.setResumedAt(now);
        p.setSeconds(seconds);
        pauseMapper.updateById(p);
        s.setPauseSeconds(s.getPauseSeconds() + seconds);
        s.setStatus("RUNNING");
        sessionMapper.updateById(s);
        return s;
    }

    @Transactional
    public SessionView stop(String publicId) {
        StudySessionEntity s = owned(publicId);
        if ("COMPLETED".equals(s.getStatus())) return view(s);
        if (!Set.of("RUNNING", "PAUSED").contains(s.getStatus())) invalid();
        Instant now = Instant.now();
        if ("PAUSED".equals(s.getStatus())) {
            StudySessionPauseEntity p = openPause(s.getId());
            long seconds = Duration.between(p.getPausedAt(), now).getSeconds();
            p.setResumedAt(now);
            p.setSeconds(seconds);
            pauseMapper.updateById(p);
            s.setPauseSeconds(s.getPauseSeconds() + seconds);
        }
        long effective = Math.max(0, Duration.between(s.getStartedAt(), now).getSeconds() - s.getPauseSeconds());
        if (effective > 240 * 60)
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "单段有效学习时长超过 240 分钟，请确认并拆分记录");
        s.setEndedAt(now);
        s.setEffectiveSeconds(effective);
        s.setStatus("COMPLETED");
        sessionMapper.updateById(s);
        return view(s);
    }

    @Transactional
    public SessionView manual(String taskPublicId, Instant startedAt, Instant endedAt, String reason) {
        LearningTaskEntity task = ownedTask(taskPublicId);
        if (reason == null || reason.isBlank() || startedAt == null || endedAt == null || !endedAt.isAfter(startedAt))
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "手工补录必须提供合法时间和原因");
        long seconds = Duration.between(startedAt, endedAt).getSeconds();
        if (seconds < 60 || seconds > 24 * 3600)
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "手工补录时长必须为 1 分钟～24 小时");
        Integer overlaps = jdbc.queryForObject("SELECT COUNT(*) FROM study_session WHERE user_id=? AND status<>'DISCARDED' AND started_at<? AND COALESCE(ended_at,UTC_TIMESTAMP(6))>? AND deleted_at IS NULL", Integer.class, task.getUserId(), endedAt, startedAt);
        if (overlaps != null && overlaps > 0)
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "手工补录与已有会话重叠");
        StudySessionEntity s = new StudySessionEntity();
        s.setPublicId(UUID.randomUUID().toString());
        s.setSessionGroupId(UUID.randomUUID().toString());
        s.setUserId(task.getUserId());
        s.setTaskId(task.getId());
        s.setSource("MANUAL");
        s.setStartedAt(startedAt);
        s.setEndedAt(endedAt);
        s.setPauseSeconds(0L);
        s.setEffectiveSeconds(seconds);
        s.setStatus("COMPLETED");
        s.setManualReason(reason);
        sessionMapper.insert(s);
        return view(s);
    }

    public SessionView get(String publicId) {
        return view(owned(publicId));
    }

    public SessionView view(StudySessionEntity s) {
        String tz = jdbc.queryForObject("SELECT timezone FROM sys_user WHERE id=?", String.class, s.getUserId());
        return new SessionView(s, allocate(s, ZoneId.of(tz)));
    }

    public static List<DayAllocation> allocate(StudySessionEntity s, ZoneId zone) {
        if (s.getEndedAt() == null) return List.of();
        ZonedDateTime cursor = s.getStartedAt().atZone(zone), end = s.getEndedAt().atZone(zone);
        long totalWall = Duration.between(s.getStartedAt(), s.getEndedAt()).getSeconds();
        if (totalWall <= 0) return List.of();
        List<DayAllocation> out = new ArrayList<>();
        long assigned = 0;
        while (cursor.isBefore(end)) {
            ZonedDateTime boundary = cursor.toLocalDate().plusDays(1).atStartOfDay(zone);
            ZonedDateTime segmentEnd = boundary.isBefore(end) ? boundary : end;
            long wall = Duration.between(cursor, segmentEnd).getSeconds();
            long effective = segmentEnd.equals(end) ? s.getEffectiveSeconds() - assigned : Math.round((double) s.getEffectiveSeconds() * wall / totalWall);
            out.add(new DayAllocation(cursor.toLocalDate(), Math.max(0, effective)));
            assigned += effective;
            cursor = segmentEnd;
        }
        return out;
    }

    private StudySessionPauseEntity openPause(long id) {
        StudySessionPauseEntity p = pauseMapper.selectOne(new LambdaQueryWrapper<StudySessionPauseEntity>().eq(StudySessionPauseEntity::getSessionId, id).isNull(StudySessionPauseEntity::getResumedAt));
        if (p == null) throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "找不到活动暂停段");
        return p;
    }

    private StudySessionEntity owned(String id) {
        StudySessionEntity s = sessionMapper.selectOne(new LambdaQueryWrapper<StudySessionEntity>().eq(StudySessionEntity::getPublicId, id).eq(StudySessionEntity::getUserId, SecurityUtils.currentUserId()));
        if (s == null) notFound();
        return s;
    }

    private LearningTaskEntity ownedTask(String id) {
        LearningTaskEntity t = taskMapper.selectOne(new LambdaQueryWrapper<LearningTaskEntity>().eq(LearningTaskEntity::getPublicId, id).eq(LearningTaskEntity::getUserId, SecurityUtils.currentUserId()));
        if (t == null) notFound();
        return t;
    }

    private void invalid() {
        throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "学习会话状态不允许该操作");
    }

    private void notFound() {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资源不存在");
    }
}

