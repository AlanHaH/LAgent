package com.adaptivelearning.evaluation.application;

import com.adaptivelearning.execution.application.StudySessionService;
import com.adaptivelearning.execution.domain.StudySessionEntity;
import com.adaptivelearning.execution.infrastructure.ExecutionMappers.SessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LearningAggregationService {
    private static final String METRIC_VERSION = "1.0";
    private final JdbcTemplate jdbc;
    private final SessionMapper sessionMapper;
    private final String owner = "learning-rollup-" + UUID.randomUUID();

    @Scheduled(cron = "${app.analytics.rollup-cron:0 */15 * * * *}")
    public void scheduledRollup() {
        if (!acquire("daily-study-stat", "all-users")) return;
        List<UserZone> users = jdbc.query(
                "SELECT id,timezone FROM sys_user WHERE status='ACTIVE' AND deleted_at IS NULL",
                (rs, row) -> new UserZone(rs.getLong(1), ZoneId.of(rs.getString(2))));
        for (UserZone user : users) {
            LocalDate today = LocalDate.now(user.zone());
            rollup(user.id(), user.zone(), today.minusDays(1), today);
        }
    }

    public void rollup(long userId, ZoneId zone, LocalDate start, LocalDate end) {
        Instant from = start.atStartOfDay(zone).toInstant();
        Instant to = end.plusDays(1).atStartOfDay(zone).toInstant();
        List<StudySessionEntity> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<StudySessionEntity>()
                        .eq(StudySessionEntity::getUserId, userId)
                        .eq(StudySessionEntity::getStatus, "COMPLETED")
                        .lt(StudySessionEntity::getStartedAt, to)
                        .gt(StudySessionEntity::getEndedAt, from));
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            long autoSeconds = 0;
            long manualSeconds = 0;
            for (StudySessionEntity session : sessions) {
                for (StudySessionService.DayAllocation allocation : StudySessionService.allocate(session, zone)) {
                    if (!day.equals(allocation.date())) continue;
                    if ("MANUAL".equals(session.getSource())) manualSeconds += allocation.effectiveSeconds();
                    else autoSeconds += allocation.effectiveSeconds();
                }
            }
            Instant dayFrom = day.atStartOfDay(zone).toInstant();
            Instant dayTo = day.plusDays(1).atStartOfDay(zone).toInstant();
            int planned = count("""
                    SELECT COUNT(*) FROM learning_task
                    WHERE user_id=? AND deleted_at IS NULL AND lifecycle_status<>'CANCELED'
                      AND scheduled_start>=? AND scheduled_start<?
                    """, userId, dayFrom, dayTo);
            int completed = count("""
                    SELECT COUNT(*) FROM learning_task
                    WHERE user_id=? AND deleted_at IS NULL AND lifecycle_status='COMPLETED'
                      AND completed_at>=? AND completed_at<?
                    """, userId, dayFrom, dayTo);
            int overdue = count("""
                    SELECT COUNT(*) FROM learning_task
                    WHERE user_id=? AND deleted_at IS NULL
                      AND due_at<? AND lifecycle_status NOT IN ('COMPLETED','CANCELED')
                    """, userId, dayTo);
            jdbc.update("""
                    INSERT INTO daily_study_stat
                    (id,user_id,local_date,timezone,auto_seconds,manual_seconds,planned_tasks,completed_tasks,overdue_tasks,metric_version)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE timezone=VALUES(timezone),auto_seconds=VALUES(auto_seconds),
                      manual_seconds=VALUES(manual_seconds),planned_tasks=VALUES(planned_tasks),
                      completed_tasks=VALUES(completed_tasks),overdue_tasks=VALUES(overdue_tasks)
                    """, IdWorker.getId(), userId, day, zone.getId(), autoSeconds, manualSeconds,
                    planned, completed, overdue, METRIC_VERSION);
        }
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private boolean acquire(String jobName, String shard) {
        Instant now = Instant.now();
        Instant until = now.plusSeconds(14 * 60);
        int updated = jdbc.update("""
                UPDATE scheduled_job_lock SET locked_until=?,owner=?
                WHERE job_name=? AND shard_key=? AND locked_until<?
                """, until, owner, jobName, shard, now);
        if (updated == 1) return true;
        try {
            jdbc.update("""
                    INSERT INTO scheduled_job_lock(job_name,shard_key,locked_until,owner)
                    VALUES(?,?,?,?)
                    """, jobName, shard, until, owner);
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    private record UserZone(long id, ZoneId zone) {
    }
}
