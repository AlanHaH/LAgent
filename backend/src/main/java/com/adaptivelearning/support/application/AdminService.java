package com.adaptivelearning.support.application;

import com.adaptivelearning.evaluation.application.AssessmentService;
import com.adaptivelearning.evaluation.domain.QuestionEntity;
import com.adaptivelearning.goalproject.domain.DependencyGraphPolicy;
import com.adaptivelearning.shared.api.PageResponse;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.support.infrastructure.AuditLogMapper;
import com.adaptivelearning.support.infrastructure.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {
    private final UserMapper users;
    private final AuditLogMapper audits;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final AssessmentService assessment;
    private final PythonAiServiceClient aiService;
    private final ModelSecretCipher modelSecrets;
    private final Environment environment;

    public PageResponse<Map<String, Object>> users(int page, int size, String status, String keyword) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = (safePage - 1) * safeSize;
        String normalizedStatus = blankToNull(status);
        String normalizedKeyword = blankToNull(keyword);
        String pattern = normalizedKeyword == null ? null : "%" + normalizedKeyword + "%";
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM sys_user u
                WHERE u.deleted_at IS NULL
                  AND (? IS NULL OR u.status=?)
                  AND (? IS NULL OR u.username LIKE ? OR u.email LIKE ?)
                """, Long.class, normalizedStatus, normalizedStatus, normalizedKeyword, pattern, pattern);
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT u.public_id AS publicId,u.username,u.email,u.status,u.timezone,
                       u.login_failed_count AS loginFailedCount,u.locked_until AS lockedUntil,
                       u.last_login_at AS lastLoginAt,u.created_at AS createdAt,u.version,
                       COALESCE(GROUP_CONCAT(r.code ORDER BY r.code SEPARATOR ','),'') AS roleCodes
                FROM sys_user u
                LEFT JOIN sys_user_role ur ON ur.user_id=u.id
                LEFT JOIN sys_role r ON r.id=ur.role_id
                WHERE u.deleted_at IS NULL
                  AND (? IS NULL OR u.status=?)
                  AND (? IS NULL OR u.username LIKE ? OR u.email LIKE ?)
                GROUP BY u.id,u.public_id,u.username,u.email,u.status,u.timezone,
                         u.login_failed_count,u.locked_until,u.last_login_at,u.created_at,u.version
                ORDER BY u.created_at DESC
                LIMIT ? OFFSET ?
                """, normalizedStatus, normalizedStatus, normalizedKeyword, pattern, pattern, safeSize, offset);
        items.forEach(item -> item.put("roles", splitCodes(item.remove("roleCodes"))));
        return new PageResponse<>(items, total == null ? 0 : total, safePage, safeSize);
    }

    public List<Map<String, Object>> roles() {
        return jdbc.queryForList("""
                SELECT code,name,status
                FROM sys_role
                WHERE status='ACTIVE'
                ORDER BY code
                """);
    }

    public Map<String, Object> learningFile(String publicId) {
        Map<String, Object> user = firstRow("""
                SELECT u.id,u.public_id AS publicId,u.username,u.email,u.status,u.timezone,
                       u.last_login_at AS lastLoginAt,u.created_at AS createdAt
                FROM sys_user u
                WHERE u.public_id=? AND u.deleted_at IS NULL
                """, publicId);
        if (user == null) notFound();
        long userId = ((Number) user.remove("id")).longValue();

        Map<String, Object> summary = firstRow("""
                SELECT
                  (SELECT COUNT(*) FROM profile_version pv JOIN user_profile p ON p.id=pv.profile_id
                    WHERE p.user_id=? AND p.deleted_at IS NULL) AS profileVersionCount,
                  (SELECT COUNT(*) FROM goal_recommendation_batch grb WHERE grb.user_id=?) AS recommendationBatchCount,
                  (SELECT COUNT(*) FROM learning_goal g WHERE g.user_id=? AND g.deleted_at IS NULL) AS goalCount,
                  (SELECT COUNT(*) FROM learning_goal g WHERE g.user_id=? AND g.status='ACTIVE' AND g.deleted_at IS NULL) AS activeGoalCount,
                  (SELECT COUNT(*) FROM learning_plan p WHERE p.user_id=? AND p.deleted_at IS NULL) AS planCount,
                  (SELECT COUNT(*) FROM learning_task t WHERE t.user_id=? AND t.deleted_at IS NULL) AS taskCount,
                  (SELECT COUNT(*) FROM learning_task t WHERE t.user_id=? AND t.lifecycle_status='COMPLETED' AND t.deleted_at IS NULL) AS completedTaskCount,
                  (SELECT COALESCE(SUM(s.effective_seconds),0) FROM study_session s WHERE s.user_id=? AND s.deleted_at IS NULL) AS totalStudySeconds,
                  (SELECT COUNT(*) FROM assessment_attempt a WHERE a.user_id=? AND a.deleted_at IS NULL) AS assessmentAttemptCount,
                  (SELECT COUNT(*) FROM knowledge_mastery m WHERE m.user_id=?) AS masteryCount
                """, userId, userId, userId, userId, userId, userId, userId, userId, userId, userId);

        Map<String, Object> currentProfile = firstRow("""
                SELECT p.id,p.timezone,p.week_start AS weekStart,p.plan_start_date AS planStartDate,
                       p.plan_end_date AS planEndDate,p.plan_period_days AS planPeriodDays,
                       p.background_text AS backgroundText,p.profile_status AS profileStatus,
                       p.current_version_no AS currentVersionNo,p.updated_at AS updatedAt,p.version
                FROM user_profile p
                WHERE p.user_id=? AND p.deleted_at IS NULL
                """, userId);
        Long profileId = currentProfile == null ? null : ((Number) currentProfile.remove("id")).longValue();

        List<Map<String, Object>> profileDirections = profileId == null ? List.of() : jdbc.queryForList("""
                SELECT pd.source_type AS sourceType,COALESCE(d.name,pd.custom_direction) AS directionName,
                       d.code AS directionCode,(pd.direction_id IS NOT NULL) AS knowledgeBaseDirection,
                       pd.current_stage AS currentStage,
                       pd.is_primary AS primaryDirection,pd.status,pd.updated_at AS updatedAt
                FROM user_profile_direction pd
                LEFT JOIN learning_direction d ON d.id=pd.direction_id
                WHERE pd.profile_id=? AND pd.deleted_at IS NULL
                ORDER BY pd.is_primary DESC,pd.created_at
                """, profileId);
        Map<String, Object> preference = firstRow("""
                SELECT content_modes_json AS contentModesJson,guidance_style AS guidanceStyle,
                       task_granularity AS taskGranularity,focus_minutes AS focusMinutes,
                       capacity_ratio AS capacityRatio,difficulty_min AS difficultyMin,
                       difficulty_max AS difficultyMax,reminder_json AS reminderJson,updated_at AS updatedAt
                FROM learning_preference
                WHERE user_id=? AND deleted_at IS NULL
                """, userId);
        if (preference != null) {
            preference.put("contentModes", readStoredJson(preference.remove("contentModesJson")));
            preference.put("reminders", readStoredJson(preference.remove("reminderJson")));
        }
        List<Map<String, Object>> availability = jdbc.queryForList("""
                SELECT weekday,start_time AS startTime,end_time AS endTime,
                       available_minutes AS availableMinutes,energy_level AS energyLevel
                FROM availability_rule
                WHERE user_id=? AND deleted_at IS NULL
                ORDER BY weekday,start_time
                """, userId);
        List<Map<String, Object>> exceptions = jdbc.queryForList("""
                SELECT local_date AS localDate, available_minutes AS availableMinutes, reason
                FROM availability_exception
                WHERE user_id=? AND deleted_at IS NULL AND local_date >= CURRENT_DATE
                ORDER BY local_date
                """, userId);
        List<Map<String, Object>> profileVersions = profileId == null ? List.of() : jdbc.queryForList("""
                SELECT version_no AS versionNo,confidence,trigger_type AS triggerType,
                       trigger_event_id AS triggerEventId,snapshot_json AS snapshotJson,created_at AS createdAt
                FROM profile_version
                WHERE profile_id=?
                ORDER BY version_no DESC
                """, profileId);
        profileVersions.forEach(row -> row.put("snapshot", readStoredJson(row.remove("snapshotJson"))));
        List<Map<String, Object>> interviews = jdbc.queryForList("""
                SELECT public_id AS publicId,status,completeness_percent AS completenessPercent,
                       assistant_mode AS assistantMode,confirmed_at AS confirmedAt,
                       created_at AS createdAt,updated_at AS updatedAt,version
                FROM profile_interview_session
                WHERE user_id=? AND deleted_at IS NULL
                ORDER BY updated_at DESC
                LIMIT 30
                """, userId);

        List<Map<String, Object>> recommendations = jdbc.queryForList("""
                SELECT public_id AS batchId,profile_version_no AS profileVersionNo,source,
                       response_json AS responseJson,generated_at AS generatedAt
                FROM goal_recommendation_batch
                WHERE user_id=?
                ORDER BY generated_at DESC,id DESC
                LIMIT 30
                """, userId);
        recommendations.forEach(row -> row.put("response", readStoredJson(row.remove("responseJson"))));

        List<Map<String, Object>> goals = jdbc.queryForList("""
                SELECT g.public_id AS publicId,g.name,g.type,g.description,g.priority,
                       COALESCE(d.name,g.custom_direction) AS directionName,
                       g.start_date AS startDate,g.due_date AS dueDate,
                       g.weekly_budget_minutes AS weeklyBudgetMinutes,g.status,g.source_type AS sourceType,
                       pv.version_no AS profileVersionNo,
                       g.recommendation_snapshot_json AS recommendationSnapshotJson,
                       g.success_criteria_json AS successCriteriaJson,g.created_at AS createdAt,
                       g.updated_at AS updatedAt,g.version
                FROM learning_goal g
                LEFT JOIN learning_direction d ON d.id=g.direction_id
                LEFT JOIN profile_version pv ON pv.id=g.profile_version_id
                WHERE g.user_id=? AND g.deleted_at IS NULL
                ORDER BY g.created_at DESC
                """, userId);
        goals.forEach(row -> {
            row.put("recommendation", readStoredJson(row.remove("recommendationSnapshotJson")));
            row.put("successCriteria", readStoredJson(row.remove("successCriteriaJson")));
        });

        List<Map<String, Object>> plans = jdbc.queryForList("""
                SELECT p.public_id AS publicId,p.name,p.status,g.public_id AS goalId,g.name AS goalName,
                       (SELECT MAX(pv.version_no) FROM plan_version pv
                         WHERE pv.plan_id=p.id AND pv.deleted_at IS NULL) AS currentVersionNo,
                       p.created_at AS createdAt,p.updated_at AS updatedAt,p.version
                FROM learning_plan p
                JOIN learning_goal g ON g.id=p.goal_id
                WHERE p.user_id=? AND p.deleted_at IS NULL
                ORDER BY p.updated_at DESC
                """, userId);
        List<Map<String, Object>> planVersions = jdbc.queryForList("""
                SELECT pv.public_id AS publicId,p.public_id AS planId,p.name AS planName,
                       pv.version_no AS versionNo,pv.base_version_no AS baseVersionNo,
                       pv.status,pv.trigger_type AS triggerType,pv.risk_level AS riskLevel,
                       pv.summary_json AS summaryJson,pv.created_at AS createdAt
                FROM plan_version pv
                JOIN learning_plan p ON p.id=pv.plan_id
                WHERE p.user_id=? AND p.deleted_at IS NULL AND pv.deleted_at IS NULL
                ORDER BY pv.created_at DESC
                LIMIT 50
                """, userId);
        planVersions.forEach(row -> row.put("summary", readStoredJson(row.remove("summaryJson"))));
        List<Map<String, Object>> tasks = jdbc.queryForList("""
                SELECT t.public_id AS publicId,t.title,t.task_type AS taskType,t.priority,
                       t.estimated_minutes AS estimatedMinutes,t.scheduled_start AS scheduledStart,
                       t.due_at AS dueAt,t.lifecycle_status AS status,t.progress_percent AS progressPercent,
                       t.completed_at AS completedAt,t.reschedule_count AS rescheduleCount,
                       g.public_id AS goalId,g.name AS goalName,pv.version_no AS originPlanVersionNo,
                       t.created_at AS createdAt,t.updated_at AS updatedAt
                FROM learning_task t
                JOIN learning_goal g ON g.id=t.goal_id
                LEFT JOIN plan_version pv ON pv.id=t.origin_plan_version_id
                WHERE t.user_id=? AND t.deleted_at IS NULL
                ORDER BY COALESCE(t.due_at,t.created_at) DESC
                LIMIT 100
                """, userId);

        List<Map<String, Object>> mastery = jdbc.queryForList("""
                SELECT kp.code AS knowledgeCode,kp.name AS knowledgeName,d.name AS directionName,
                       m.score,m.confidence,m.level,m.evidence_count AS evidenceCount,
                       m.calculated_at AS calculatedAt,m.calc_version AS calcVersion
                FROM knowledge_mastery m
                JOIN knowledge_point kp ON kp.id=m.knowledge_point_id
                JOIN learning_direction d ON d.id=kp.direction_id
                WHERE m.user_id=?
                ORDER BY m.calculated_at DESC,m.score
                LIMIT 100
                """, userId);
        List<Map<String, Object>> selfAssessments = jdbc.queryForList("""
                SELECT kp.code AS knowledgeCode,kp.name AS knowledgeName,d.name AS directionName,
                       sa.level,sa.last_studied_at AS lastStudiedAt,sa.note,sa.assessed_at AS assessedAt
                FROM self_assessment sa
                JOIN knowledge_point kp ON kp.id=sa.knowledge_point_id
                JOIN learning_direction d ON d.id=kp.direction_id
                WHERE sa.user_id=? AND sa.deleted_at IS NULL
                ORDER BY sa.assessed_at DESC
                LIMIT 50
                """, userId);
        List<Map<String, Object>> assessments = jdbc.queryForList("""
                SELECT aa.public_id AS publicId,a.title,a.type,aa.attempt_no AS attemptNo,
                       aa.status,aa.total_score AS totalScore,a.total_score AS maxScore,
                       aa.started_at AS startedAt,aa.submitted_at AS submittedAt
                FROM assessment_attempt aa
                JOIN assessment a ON a.id=aa.assessment_id
                WHERE aa.user_id=? AND aa.deleted_at IS NULL
                ORDER BY aa.started_at DESC
                LIMIT 50
                """, userId);
        List<Map<String, Object>> sessions = jdbc.queryForList("""
                SELECT s.public_id AS publicId,t.title AS taskTitle,s.source,s.started_at AS startedAt,
                       s.ended_at AS endedAt,s.pause_seconds AS pauseSeconds,
                       s.effective_seconds AS effectiveSeconds,s.status
                FROM study_session s
                JOIN learning_task t ON t.id=s.task_id
                WHERE s.user_id=? AND s.deleted_at IS NULL
                ORDER BY s.started_at DESC
                LIMIT 50
                """, userId);
        List<Map<String, Object>> dailyStats = jdbc.queryForList("""
                SELECT local_date AS localDate,auto_seconds AS autoSeconds,manual_seconds AS manualSeconds,
                       planned_tasks AS plannedTasks,completed_tasks AS completedTasks,
                       overdue_tasks AS overdueTasks
                FROM daily_study_stat
                WHERE user_id=?
                ORDER BY local_date DESC
                LIMIT 30
                """, userId);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("current", currentProfile);
        profile.put("directions", profileDirections);
        profile.put("preference", preference);
        profile.put("availability", availability);
        profile.put("exceptions", exceptions);
        profile.put("versions", profileVersions);
        profile.put("interviews", interviews);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("mastery", mastery);
        evidence.put("selfAssessments", selfAssessments);
        evidence.put("assessments", assessments);
        evidence.put("studySessions", sessions);
        evidence.put("dailyStats", dailyStats);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", user);
        result.put("summary", summary);
        result.put("profile", profile);
        result.put("recommendations", recommendations);
        result.put("goals", goals);
        result.put("plans", plans);
        result.put("planVersions", planVersions);
        result.put("tasks", tasks);
        result.put("evidence", evidence);
        return result;
    }

    @Transactional
    public void userStatus(String publicId, String status, Integer version, String reason) {
        if (!Set.of("ACTIVE", "DISABLED", "LOCKED").contains(status) || reason == null || reason.isBlank())
            bad("用户状态或原因不合法");
        Map<String, Object> user = ownedAdminUser(publicId);
        long targetId = ((Number) user.get("id")).longValue();
        if (targetId == SecurityUtils.currentUserId() && !"ACTIVE".equals(status))
            bad("不能禁用或锁定当前登录的管理员账号");
        if (version == null || !version.equals(((Number) user.get("version")).intValue())) conflict();
        int changed = jdbc.update("""
                UPDATE sys_user
                SET status=?,login_failed_count=CASE WHEN ?='ACTIVE' THEN 0 ELSE login_failed_count END,
                    locked_until=CASE WHEN ?='ACTIVE' THEN NULL ELSE locked_until END,
                    updated_at=?,updated_by=?,version=version+1
                WHERE id=? AND version=? AND deleted_at IS NULL
                """, status, status, status, Instant.now(), SecurityUtils.currentUserId(), targetId, version);
        if (changed != 1) conflict();
        audit.record("ADMIN_USER_STATUS", "SYS_USER", publicId,
                String.valueOf(user.get("status")), status + ":" + reason.trim(), "SUCCESS");
    }

    @Transactional
    public void updateUserRoles(String publicId, Set<String> roleCodes, String reason) {
        if (roleCodes == null || roleCodes.isEmpty() || reason == null || reason.isBlank())
            bad("用户至少需要一个角色，并填写变更原因");
        Map<String, Object> user = ownedAdminUser(publicId);
        long targetId = ((Number) user.get("id")).longValue();
        Set<String> normalized = new TreeSet<>();
        roleCodes.forEach(code -> normalized.add(code == null ? "" : code.trim().toUpperCase(Locale.ROOT)));
        List<Map<String, Object>> selected = jdbc.queryForList(
                "SELECT id,code FROM sys_role WHERE status='ACTIVE' AND code IN (" +
                        String.join(",", Collections.nCopies(normalized.size(), "?")) + ")",
                normalized.toArray());
        if (selected.size() != normalized.size()) bad("包含不存在或已停用的角色");
        if (targetId == SecurityUtils.currentUserId() && !normalized.contains("ADMIN"))
            bad("不能移除当前登录账号的 ADMIN 角色");
        List<String> before = jdbc.queryForList("""
                SELECT r.code FROM sys_role r
                JOIN sys_user_role ur ON ur.role_id=r.id
                WHERE ur.user_id=? ORDER BY r.code
                """, String.class, targetId);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", targetId);
        for (Map<String, Object> role : selected)
            jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", targetId, role.get("id"));
        audit.record("ADMIN_USER_ROLES", "SYS_USER", publicId, before.toString(),
                normalized + ":" + reason.trim(), "SUCCESS");
    }

    public List<Map<String, Object>> directions() {
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT d.id,d.parent_id AS parentId,d.code,d.name,d.status,d.sort_no AS sortNo,d.version,
                       p.name AS parentName,
                       (SELECT COUNT(*) FROM knowledge_point k WHERE k.direction_id=d.id AND k.deleted_at IS NULL) AS knowledgeCount
                FROM learning_direction d
                LEFT JOIN learning_direction p ON p.id=d.parent_id
                WHERE d.deleted_at IS NULL
                ORDER BY d.sort_no,d.id
                """);
        items.forEach(item -> stringifyIds(item, "id", "parentId"));
        return items;
    }

    public List<Map<String, Object>> catalogDirections() {
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT id,parent_id AS parentId,code,name,status,sort_no AS sortNo,version
                FROM learning_direction
                WHERE deleted_at IS NULL
                ORDER BY sort_no,id
                """);
        items.forEach(item -> stringifyIds(item, "id", "parentId"));
        return items;
    }

    @Transactional
    public Map<String, Object> saveDirection(Long id, Long parentId, String code, String name,
                                             String status, int sortNo, Integer version) {
        if (code == null || !code.matches("[A-Z0-9_]{2,80}") || name == null || name.isBlank()
                || !Set.of("ACTIVE", "DISABLED", "DRAFT").contains(status))
            bad("方向字段不合法");
        if (id != null && Objects.equals(id, parentId)) bad("方向不能把自己设为父级");
        long entityId = id == null ? IdWorker.getId() : id;
        try {
            if (id == null) {
                jdbc.update("""
                        INSERT INTO learning_direction
                        (id,parent_id,code,name,status,sort_no,created_at,created_by,updated_at,updated_by,version)
                        VALUES(?,?,?,?,?,?,?,?,?,?,0)
                        """, entityId, parentId, code, name.trim(), status, sortNo, Instant.now(),
                        SecurityUtils.currentUserId(), Instant.now(), SecurityUtils.currentUserId());
                audit.record("DIRECTION_CREATE", "LEARNING_DIRECTION", String.valueOf(entityId),
                        null, code + ":" + name.trim(), "SUCCESS");
            } else {
                int changed = jdbc.update("""
                        UPDATE learning_direction
                        SET parent_id=?,code=?,name=?,status=?,sort_no=?,updated_at=?,updated_by=?,version=version+1
                        WHERE id=? AND version=? AND deleted_at IS NULL
                        """, parentId, code, name.trim(), status, sortNo, Instant.now(),
                        SecurityUtils.currentUserId(), id, version);
                if (changed != 1) conflict();
                audit.record("DIRECTION_UPDATE", "LEARNING_DIRECTION", String.valueOf(entityId),
                        null, code + ":" + name.trim() + ":" + status, "SUCCESS");
            }
        } catch (DuplicateKeyException exception) {
            bad("方向编码已存在");
        }
        return directions().stream().filter(item -> Objects.equals(number(item.get("id")), entityId))
                .findFirst().orElse(Map.of());
    }

    public List<Map<String, Object>> knowledgePoints(Long directionId, String keyword) {
        String normalizedKeyword = blankToNull(keyword);
        String pattern = normalizedKeyword == null ? null : "%" + normalizedKeyword + "%";
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT k.id,k.direction_id AS directionId,k.parent_id AS parentId,k.code,k.name,k.level,
                       k.default_weight AS defaultWeight,k.status,k.version,d.name AS directionName,
                       p.name AS parentName
                FROM knowledge_point k
                JOIN learning_direction d ON d.id=k.direction_id
                LEFT JOIN knowledge_point p ON p.id=k.parent_id
                WHERE k.deleted_at IS NULL
                  AND (? IS NULL OR k.direction_id=?)
                  AND (? IS NULL OR k.name LIKE ? OR k.code LIKE ?)
                ORDER BY d.sort_no,k.level,k.id
                """, directionId, directionId, normalizedKeyword, pattern, pattern);
        items.forEach(item -> stringifyIds(item, "id", "directionId", "parentId"));
        return items;
    }

    public List<Map<String, Object>> knowledgePoints() {
        return knowledgePoints(null, null);
    }

    @Transactional
    public Map<String, Object> saveKnowledge(Long id, long directionId, Long parentId, String code, String name,
                                             int level, double weight, String status, Integer version) {
        if (code == null || !code.matches("[A-Z0-9_]{2,80}") || name == null || name.isBlank()
                || level < 1 || weight <= 0 || !Set.of("ACTIVE", "DISABLED", "DRAFT").contains(status))
            bad("知识点字段不合法");
        if (id != null && Objects.equals(id, parentId)) bad("知识点不能把自己设为父级");
        Long directionExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM learning_direction WHERE id=? AND deleted_at IS NULL", Long.class, directionId);
        if (directionExists == null || directionExists == 0) bad("学习方向不存在");
        if (parentId != null) {
            Long parentDirection = jdbc.query(
                    "SELECT direction_id FROM knowledge_point WHERE id=? AND deleted_at IS NULL",
                    rs -> rs.next() ? rs.getLong(1) : null, parentId);
            if (!Objects.equals(parentDirection, directionId)) bad("父知识点必须属于同一学习方向");
        }
        long entityId = id == null ? IdWorker.getId() : id;
        try {
            if (id == null) {
                jdbc.update("""
                        INSERT INTO knowledge_point
                        (id,direction_id,parent_id,code,name,level,default_weight,status,
                         created_at,created_by,updated_at,updated_by,version)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,0)
                        """, entityId, directionId, parentId, code, name.trim(), level, weight, status,
                        Instant.now(), SecurityUtils.currentUserId(), Instant.now(), SecurityUtils.currentUserId());
                audit.record("KNOWLEDGE_POINT_CREATE", "KNOWLEDGE_POINT", String.valueOf(entityId),
                        null, code + ":" + name.trim(), "SUCCESS");
            } else {
                int changed = jdbc.update("""
                        UPDATE knowledge_point
                        SET direction_id=?,parent_id=?,code=?,name=?,level=?,default_weight=?,status=?,
                            updated_at=?,updated_by=?,version=version+1
                        WHERE id=? AND version=? AND deleted_at IS NULL
                        """, directionId, parentId, code, name.trim(), level, weight, status, Instant.now(),
                        SecurityUtils.currentUserId(), id, version);
                if (changed != 1) conflict();
                audit.record("KNOWLEDGE_POINT_UPDATE", "KNOWLEDGE_POINT", String.valueOf(entityId),
                        null, code + ":" + name.trim() + ":" + status, "SUCCESS");
            }
        } catch (DuplicateKeyException exception) {
            bad("该方向下的知识点编码已存在");
        }
        return knowledgePoints(directionId, null).stream()
                .filter(item -> Objects.equals(number(item.get("id")), entityId))
                .findFirst().orElse(Map.of());
    }

    public List<Map<String, Object>> dependencies(Long directionId) {
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT kd.predecessor_id AS predecessorId,p.name AS predecessorName,
                       kd.successor_id AS successorId,s.name AS successorName,kd.type,
                       p.direction_id AS directionId,d.name AS directionName
                FROM knowledge_dependency kd
                JOIN knowledge_point p ON p.id=kd.predecessor_id
                JOIN knowledge_point s ON s.id=kd.successor_id
                JOIN learning_direction d ON d.id=p.direction_id
                WHERE (? IS NULL OR p.direction_id=?)
                ORDER BY d.sort_no,p.level,p.id,s.level,s.id
                """, directionId, directionId);
        items.forEach(item -> stringifyIds(item, "predecessorId", "successorId", "directionId"));
        return items;
    }

    @Transactional
    public void dependency(long predecessor, long successor) {
        if (predecessor == successor) bad("前置和后续知识点不能相同");
        List<Map<String, Object>> points = jdbc.queryForList(
                "SELECT id,direction_id FROM knowledge_point WHERE id IN (?,?) AND deleted_at IS NULL",
                predecessor, successor);
        if (points.size() != 2) bad("知识点不存在");
        Set<Object> directionIds = new HashSet<>();
        points.forEach(point -> directionIds.add(point.get("direction_id")));
        if (directionIds.size() != 1) bad("知识依赖必须位于同一学习方向");
        List<DependencyGraphPolicy.Edge> edges = jdbc.query(
                "SELECT predecessor_id,successor_id FROM knowledge_dependency",
                (rs, row) -> new DependencyGraphPolicy.Edge(rs.getLong(1), rs.getLong(2)));
        edges = new ArrayList<>(edges);
        edges.add(new DependencyGraphPolicy.Edge(predecessor, successor));
        DependencyGraphPolicy.requireAcyclic(edges);
        try {
            jdbc.update("""
                    INSERT INTO knowledge_dependency(predecessor_id,successor_id,type)
                    VALUES(?,?,'PREREQUISITE')
                    """, predecessor, successor);
        } catch (DuplicateKeyException exception) {
            bad("该知识依赖已经存在");
        }
        audit.record("KNOWLEDGE_DEPENDENCY_CREATE", "KNOWLEDGE_DEPENDENCY",
                predecessor + "->" + successor, null, "PREREQUISITE", "SUCCESS");
    }

    @Transactional
    public void deleteDependency(long predecessor, long successor) {
        int changed = jdbc.update(
                "DELETE FROM knowledge_dependency WHERE predecessor_id=? AND successor_id=?",
                predecessor, successor);
        if (changed == 0) notFound();
        audit.record("KNOWLEDGE_DEPENDENCY_DELETE", "KNOWLEDGE_DEPENDENCY",
                predecessor + "->" + successor, "PREREQUISITE", null, "SUCCESS");
    }

    public QuestionEntity publicQuestion(AssessmentService.QuestionInput input) {
        QuestionEntity question = assessment.createQuestion(input, true);
        audit.record("QUESTION_CREATE", "QUESTION", question.getPublicId(), null, question.getStatus(), "SUCCESS");
        return question;
    }

    public PageResponse<Map<String, Object>> questions(int page, int size, String type, String status, String keyword) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = (safePage - 1) * safeSize;
        String normalizedType = blankToNull(type);
        String normalizedStatus = blankToNull(status);
        String normalizedKeyword = blankToNull(keyword);
        String pattern = normalizedKeyword == null ? null : "%" + normalizedKeyword + "%";
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM question q
                JOIN question_version qv ON qv.question_id=q.id AND qv.version_no=q.current_version_no
                WHERE q.deleted_at IS NULL
                  AND (? IS NULL OR qv.type=?)
                  AND (? IS NULL OR q.status=?)
                  AND (? IS NULL OR qv.stem LIKE ?)
                """, Long.class, normalizedType, normalizedType, normalizedStatus, normalizedStatus,
                normalizedKeyword, pattern);
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT q.public_id AS publicId,q.visibility,q.status,q.source_type AS sourceType,
                       q.current_version_no AS currentVersionNo,q.version,q.updated_at AS updatedAt,
                       qv.type,qv.stem,qv.options_json AS optionsJson,qv.answer_json AS answerJson,
                       qv.rubric_json AS rubricJson,qv.analysis,qv.difficulty,
                       COALESCE(GROUP_CONCAT(kp.name ORDER BY kp.id SEPARATOR '、'),'') AS knowledgePointNames
                FROM question q
                JOIN question_version qv ON qv.question_id=q.id AND qv.version_no=q.current_version_no
                LEFT JOIN question_knowledge_point qkp ON qkp.question_version_id=qv.id
                LEFT JOIN knowledge_point kp ON kp.id=qkp.knowledge_point_id
                WHERE q.deleted_at IS NULL
                  AND (? IS NULL OR qv.type=?)
                  AND (? IS NULL OR q.status=?)
                  AND (? IS NULL OR qv.stem LIKE ?)
                GROUP BY q.id,q.public_id,q.visibility,q.status,q.source_type,q.current_version_no,
                         q.version,q.updated_at,qv.type,qv.stem,qv.options_json,qv.answer_json,
                         qv.rubric_json,qv.analysis,qv.difficulty
                ORDER BY q.updated_at DESC
                LIMIT ? OFFSET ?
                """, normalizedType, normalizedType, normalizedStatus, normalizedStatus,
                normalizedKeyword, pattern, safeSize, offset);
        return new PageResponse<>(items, total == null ? 0 : total, safePage, safeSize);
    }

    public List<Map<String, Object>> modelConfigs() {
        return jdbc.queryForList("""
                SELECT mc.public_id AS publicId,mc.purpose,mc.model_name AS modelName,
                       mc.parameters_json AS parametersJson,mc.timeout_seconds AS timeoutSeconds,
                       mc.daily_limit AS dailyLimit,mc.status,mc.version,
                       mp.provider,mp.name AS providerName,mp.base_url AS baseUrl,
                       CASE WHEN mp.encrypted_secret_ref IS NULL THEN NULL ELSE '********' END AS secretMask
                FROM model_config mc
                JOIN model_provider_config mp ON mp.id=mc.provider_id
                WHERE mc.deleted_at IS NULL
                ORDER BY mc.purpose,mc.model_name
                """);
    }

    @Transactional
    public Map<String, Object> saveModel(String provider, String providerName, String baseUrl, String secretRef,
                                         String purpose, String modelName, Map<String, Object> params,
                                         int timeout, long dailyLimit) {
        validateModelInput(provider, providerName, baseUrl, secretRef, purpose, modelName, timeout, dailyLimit);
        long providerId = IdWorker.getId();
        long modelId = IdWorker.getId();
        String publicId = UUID.randomUUID().toString();
        String protectedSecret = modelSecrets.protect(secretRef);
        try {
            jdbc.update("""
                    INSERT INTO model_provider_config
                    (id,public_id,provider,name,encrypted_secret_ref,base_url,status,
                    created_at,created_by,updated_at,updated_by,version)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,0)
                    """, providerId, UUID.randomUUID().toString(), provider, providerName.trim(),
                    blankToNull(protectedSecret), baseUrl, "ACTIVE", Instant.now(), SecurityUtils.currentUserId(),
                    Instant.now(), SecurityUtils.currentUserId());
            jdbc.update("""
                    INSERT INTO model_config
                    (id,public_id,provider_id,purpose,model_name,parameters_json,timeout_seconds,daily_limit,
                     status,created_at,created_by,updated_at,updated_by,version)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,0)
                    """, modelId, publicId, providerId, purpose.trim(), modelName.trim(),
                    toJson(params == null ? Map.of() : params), timeout, dailyLimit, "DISABLED",
                    Instant.now(), SecurityUtils.currentUserId(), Instant.now(), SecurityUtils.currentUserId());
        } catch (DuplicateKeyException exception) {
            bad("相同用途和模型名称的配置已存在");
        }
        audit.record("MODEL_CONFIG_CREATE", "MODEL_CONFIG", publicId, null,
                purpose + ":" + modelName, "SUCCESS");
        return modelConfigs().stream().filter(item -> publicId.equals(item.get("publicId")))
                .findFirst().orElse(Map.of());
    }

    @Transactional
    public Map<String, Object> updateModel(String publicId, String provider, String providerName,
                                           String baseUrl, String secretRef, String purpose, String modelName,
                                           Map<String, Object> params, int timeout, long dailyLimit,
                                           Integer version) {
        validateModelInput(provider, providerName, baseUrl, secretRef, purpose, modelName, timeout, dailyLimit);
        Map<String, Object> selected = runtimeModel(publicId);
        if (version == null || version.intValue() != ((Number) selected.get("version")).intValue()) conflict();
        Long duplicate = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM model_config
                WHERE purpose=? AND model_name=? AND public_id<>? AND deleted_at IS NULL
                """, Long.class, purpose.trim(), modelName.trim(), publicId);
        if (duplicate != null && duplicate > 0) bad("相同用途和模型名称的配置已存在");

        String retainedSecretRef = secretRef == null || secretRef.isBlank()
                ? Objects.toString(selected.get("secretRef"), null) : modelSecrets.protect(secretRef);
        Map<String, Object> candidate = new HashMap<>(selected);
        candidate.put("provider", provider.trim());
        candidate.put("providerName", providerName.trim());
        candidate.put("baseUrl", baseUrl.trim());
        candidate.put("secretRef", retainedSecretRef);
        candidate.put("purpose", purpose.trim());
        candidate.put("modelName", modelName.trim());
        candidate.put("parametersJson", params == null ? Map.of() : params);
        candidate.put("timeoutSeconds", timeout);
        candidate.put("dailyLimit", dailyLimit);

        if ("ACTIVE".equals(selected.get("status"))) {
            aiService.applyRuntimeModel(runtimeConfiguration(candidate));
        }
        Instant now = Instant.now();
        long operator = SecurityUtils.currentUserId();
        int providerChanged = jdbc.update("""
                UPDATE model_provider_config
                SET provider=?,name=?,encrypted_secret_ref=?,base_url=?,
                    updated_at=?,updated_by=?,version=version+1
                WHERE id=? AND deleted_at IS NULL
                """, provider.trim(), providerName.trim(), blankToNull(retainedSecretRef), baseUrl.trim(),
                now, operator, selected.get("providerId"));
        int modelChanged;
        try {
            modelChanged = jdbc.update("""
                    UPDATE model_config
                    SET purpose=?,model_name=?,parameters_json=?,timeout_seconds=?,daily_limit=?,
                        updated_at=?,updated_by=?,version=version+1
                    WHERE public_id=? AND version=? AND deleted_at IS NULL
                    """, purpose.trim(), modelName.trim(), toJson(params == null ? Map.of() : params),
                    timeout, dailyLimit, now, operator, publicId, version);
        } catch (DuplicateKeyException exception) {
            bad("相同用途和模型名称的配置已存在");
            return Map.of();
        }
        if (providerChanged != 1 || modelChanged != 1) conflict();
        audit.record("MODEL_CONFIG_UPDATE", "MODEL_CONFIG", publicId,
                selected.get("modelName") + ":" + selected.get("baseUrl"),
                modelName.trim() + ":" + baseUrl.trim(), "SUCCESS");
        return modelConfigs().stream().filter(item -> publicId.equals(item.get("publicId")))
                .findFirst().orElse(Map.of());
    }

    @Transactional
    public void deleteModel(String publicId, Integer version) {
        Map<String, Object> selected = runtimeModel(publicId);
        if (version == null || version.intValue() != ((Number) selected.get("version")).intValue()) conflict();
        if ("ACTIVE".equals(selected.get("status"))) {
            bad("当前运行模型不能删除，请先启用另一个模型");
        }
        Instant now = Instant.now();
        long operator = SecurityUtils.currentUserId();
        int changed = jdbc.update("""
                UPDATE model_config
                SET status='DELETED',deleted_at=?,updated_at=?,updated_by=?,version=version+1,
                    model_name=CONCAT(LEFT(model_name,100),'#',LEFT(public_id,8))
                WHERE public_id=? AND version=? AND deleted_at IS NULL
                """, now, now, operator, publicId, version);
        if (changed != 1) conflict();
        jdbc.update("""
                UPDATE model_provider_config
                SET status='DISABLED',encrypted_secret_ref=NULL,deleted_at=?,
                    updated_at=?,updated_by=?,version=version+1
                WHERE id=? AND deleted_at IS NULL
                """, now, now, operator, selected.get("providerId"));
        audit.record("MODEL_CONFIG_DELETE", "MODEL_CONFIG", publicId,
                selected.get("modelName") + ":" + selected.get("baseUrl"), "DELETED", "SUCCESS");
    }

    private void validateModelInput(String provider, String providerName, String baseUrl, String secretRef,
                                    String purpose, String modelName, int timeout, long dailyLimit) {
        if (provider == null || provider.isBlank() || providerName == null || providerName.isBlank()
                || baseUrl == null || baseUrl.isBlank() || purpose == null || purpose.isBlank()
                || modelName == null || modelName.isBlank() || timeout < 1 || timeout > 300 || dailyLimit < 1) {
            bad("模型配置字段不完整或超出范围");
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (Exception exception) {
            bad("模型 Base URL 不合法");
        }
        if (secretRef != null && !secretRef.isBlank()) {
            String normalized = secretRef.trim();
            if ((normalized.startsWith("env:") || normalized.startsWith("property:"))
                    && normalized.substring(normalized.indexOf(':') + 1).isBlank()) {
                bad("密钥引用名称不能为空");
            }
            if (!normalized.startsWith("env:") && !normalized.startsWith("property:")
                    && normalized.length() < 8) {
                bad("API Key 长度不能少于 8 个字符");
            }
        }
    }

    @Transactional
    public void updateModelStatus(String publicId, String status, Integer version) {
        if (!Set.of("ACTIVE", "DISABLED").contains(status)) bad("模型状态不合法");
        Map<String, Object> selected = runtimeModel(publicId);
        if (version == null || version.intValue() != ((Number) selected.get("version")).intValue()) conflict();
        String currentStatus = String.valueOf(selected.get("status"));
        if ("DISABLED".equals(status) && "ACTIVE".equals(currentStatus)) {
            bad("当前运行模型不能直接停用，请先启用另一个模型");
        }
        if ("ACTIVE".equals(status)) {
            aiService.applyRuntimeModel(runtimeConfiguration(selected));
            jdbc.update("""
                    UPDATE model_config
                    SET status='DISABLED',updated_at=?,updated_by=?,version=version+1
                    WHERE public_id<>? AND status='ACTIVE' AND deleted_at IS NULL
                    """, Instant.now(), SecurityUtils.currentUserId(), publicId);
        }
        int changed = jdbc.update("""
                UPDATE model_config
                SET status=?,updated_at=?,updated_by=?,version=version+1
                WHERE public_id=? AND version=? AND deleted_at IS NULL
                """, status, Instant.now(), SecurityUtils.currentUserId(), publicId, version);
        if (changed != 1) conflict();
        audit.record("MODEL_CONFIG_STATUS", "MODEL_CONFIG", publicId, null, status, "SUCCESS");
    }

    public Map<String, Object> testModel(String publicId) {
        Map<String, Object> selected = runtimeModel(publicId);
        Map<String, Object> result = aiService.testRuntimeModel(runtimeConfiguration(selected));
        audit.record("MODEL_CONFIG_TEST", "MODEL_CONFIG", publicId, null,
                selected.get("modelName") + ":SUCCESS", "SUCCESS");
        return result;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreActiveRuntimeModel() {
        if (!aiService.isConfigured()) return;
        try {
            List<String> active = jdbc.queryForList("""
                    SELECT public_id
                    FROM model_config
                    WHERE status='ACTIVE' AND deleted_at IS NULL
                    ORDER BY updated_at DESC
                    LIMIT 1
                    """, String.class);
            if (active.isEmpty()) return;
            Map<String, Object> selected = runtimeModel(active.get(0));
            aiService.applyRuntimeModel(runtimeConfiguration(selected));
            log.info("Restored runtime AI model {}", selected.get("modelName"));
        } catch (RuntimeException error) {
            log.warn("Could not restore runtime AI model: {}", error.getClass().getSimpleName());
        }
    }

    private Map<String, Object> runtimeModel(String publicId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT mc.public_id AS publicId,mc.provider_id AS providerId,mc.purpose,
                       mc.model_name AS modelName,mc.daily_limit AS dailyLimit,mc.version,
                       mc.parameters_json AS parametersJson,mc.timeout_seconds AS timeoutSeconds,
                       mc.status,mp.provider,mp.name AS providerName,mp.base_url AS baseUrl,
                       mp.encrypted_secret_ref AS secretRef
                FROM model_config mc
                JOIN model_provider_config mp ON mp.id=mc.provider_id
                WHERE mc.public_id=? AND mc.deleted_at IS NULL
                """, publicId);
        if (rows.isEmpty()) bad("模型配置不存在");
        return rows.get(0);
    }

    private PythonAiServiceClient.RuntimeModelConfiguration runtimeConfiguration(Map<String, Object> model) {
        String provider = String.valueOf(model.get("provider")).trim();
        if (!"OPENAI_COMPATIBLE".equalsIgnoreCase(provider)) {
            bad("当前仅支持 OPENAI_COMPATIBLE 运行时模型");
        }
        Map<String, Object> parameters = parseObject(model.get("parametersJson"));
        int maxOutputTokens = intParameter(parameters, "maxOutputTokens", 1200, 1, 8000);
        String thinking = String.valueOf(parameters.getOrDefault("thinking", "disabled")).trim();
        if (!Set.of("", "enabled", "disabled").contains(thinking)) bad("thinking 参数不合法");
        boolean allowHttp = Boolean.parseBoolean(String.valueOf(parameters.getOrDefault("allowHttp", false)));
        return new PythonAiServiceClient.RuntimeModelConfiguration(
                provider,
                String.valueOf(model.get("baseUrl")).trim(),
                resolveSecret(model.get("secretRef")),
                String.valueOf(model.get("modelName")).trim(),
                ((Number) model.get("timeoutSeconds")).intValue(),
                maxOutputTokens,
                thinking,
                allowHttp);
    }

    private String resolveSecret(Object rawReference) {
        String reference = rawReference == null || String.valueOf(rawReference).isBlank()
                ? "env:MODEL_API_KEY" : String.valueOf(rawReference).trim();
        String value;
        if (reference.startsWith("enc:v1:")) {
            value = modelSecrets.decrypt(reference);
        } else if (reference.startsWith("env:")) {
            String name = reference.substring(4).trim();
            if (!name.matches("[A-Z][A-Z0-9_]{1,79}")) bad("模型密钥环境变量名称不合法");
            value = System.getenv(name);
            if (value == null || value.isBlank()) value = environment.getProperty(name);
        } else if (reference.startsWith("property:")) {
            String name = reference.substring(9).trim();
            value = environment.getProperty(name);
        } else {
            bad("密钥引用仅支持 env:变量名 或 property:配置项");
            return "";
        }
        if (value == null || value.isBlank()) bad("模型密钥引用未解析到有效值：" + reference);
        return value.trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObject(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        if (value == null || String.valueOf(value).isBlank()) return Map.of();
        try {
            return json.readValue(String.valueOf(value), Map.class);
        } catch (JsonProcessingException error) {
            bad("模型参数 JSON 不合法");
            return Map.of();
        }
    }

    private int intParameter(Map<String, Object> values, String key, int fallback, int min, int max) {
        Object raw = values.get(key);
        if (raw == null) return fallback;
        int value;
        try {
            value = raw instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException error) {
            bad(key + " 参数必须是整数");
            return fallback;
        }
        if (value < min || value > max) bad(key + " 参数超出范围");
        return value;
    }

    public List<Map<String, Object>> prompts(String code) {
        String normalizedCode = blankToNull(code);
        return jdbc.queryForList("""
                SELECT public_id AS publicId,code,version_no AS versionNo,content,
                       schema_json AS schemaJson,status,created_at AS createdAt,created_by AS createdBy
                FROM prompt_template
                WHERE (? IS NULL OR code=?)
                ORDER BY code,version_no DESC
                """, normalizedCode, normalizedCode);
    }

    /** Python AI 服务通过内部端点拉取的运行时系统提示词：仅当前 ACTIVE 版本。 */
    public List<PromptTemplateDto> activePromptTemplates() {
        return jdbc.query("""
                SELECT code, version_no, content
                FROM prompt_template
                WHERE status='ACTIVE'
                ORDER BY code
                """, (rs, rowNum) -> new PromptTemplateDto(
                rs.getString("code"),
                rs.getInt("version_no"),
                rs.getString("content")));
    }

    /** 内部端点返回的提示词行。用 record 固定 JSON 键名（低版本 H2 会小写化 SQL 别名）。 */
    public record PromptTemplateDto(String code, int versionNo, String content) {}

    @Transactional
    public Map<String, Object> savePrompt(String code, String content, Object schema) {
        if (code == null || !code.matches("[A-Z0-9_\\-]{2,100}") || content == null || content.isBlank())
            bad("提示词编码或内容不合法");
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM prompt_template WHERE code=?",
                Integer.class, code);
        String publicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO prompt_template
                (id,public_id,code,version_no,content,schema_json,status,created_at,created_by)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, IdWorker.getId(), publicId, code, next, content, toJson(schema), "DRAFT",
                Instant.now(), SecurityUtils.currentUserId());
        audit.record("PROMPT_TEMPLATE_CREATE", "PROMPT_TEMPLATE", publicId, null,
                code + ":V" + next, "SUCCESS");
        return prompts(code).stream().filter(item -> publicId.equals(item.get("publicId")))
                .findFirst().orElse(Map.of());
    }

    @Transactional
    public void updatePromptStatus(String publicId, String status) {
        if (!Set.of("DRAFT", "ACTIVE", "ARCHIVED").contains(status)) bad("提示词状态不合法");
        Map<String, Object> prompt = jdbc.query("""
                SELECT id,code,version_no,status FROM prompt_template WHERE public_id=?
                """, rs -> {
            if (!rs.next()) return null;
            return Map.of("id", rs.getLong(1), "code", rs.getString(2), "version", rs.getInt(3),
                    "status", rs.getString(4));
        }, publicId);
        if (prompt == null) notFound();
        if ("ACTIVE".equals(status)) {
            jdbc.update("UPDATE prompt_template SET status='ARCHIVED' WHERE code=? AND status='ACTIVE'",
                    prompt.get("code"));
        }
        jdbc.update("UPDATE prompt_template SET status=? WHERE id=?", status, prompt.get("id"));
        audit.record("PROMPT_TEMPLATE_STATUS", "PROMPT_TEMPLATE", publicId,
                String.valueOf(prompt.get("status")), status, "SUCCESS");
    }

    public PageResponse<Map<String, Object>> auditLogs(int page, int size, String action,
                                                       String result, String keyword) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = (safePage - 1) * safeSize;
        String normalizedAction = blankToNull(action);
        String normalizedResult = blankToNull(result);
        String normalizedKeyword = blankToNull(keyword);
        String pattern = normalizedKeyword == null ? null : "%" + normalizedKeyword + "%";
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log a
                WHERE (? IS NULL OR a.action=?)
                  AND (? IS NULL OR a.result=?)
                  AND (? IS NULL OR a.resource_id LIKE ? OR a.resource_type LIKE ?)
                """, Long.class, normalizedAction, normalizedAction, normalizedResult, normalizedResult,
                normalizedKeyword, pattern, pattern);
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT a.request_id AS requestId,a.operator_id AS operatorId,
                       COALESCE(u.username,a.operator_type) AS operatorName,a.operator_type AS operatorType,
                       a.action,a.resource_type AS resourceType,a.resource_id AS resourceId,
                       a.before_summary AS beforeSummary,a.after_summary AS afterSummary,
                       a.result,a.ip,a.created_at AS createdAt
                FROM audit_log a
                LEFT JOIN sys_user u ON u.id=a.operator_id
                WHERE (? IS NULL OR a.action=?)
                  AND (? IS NULL OR a.result=?)
                  AND (? IS NULL OR a.resource_id LIKE ? OR a.resource_type LIKE ?)
                ORDER BY a.created_at DESC
                LIMIT ? OFFSET ?
                """, normalizedAction, normalizedAction, normalizedResult, normalizedResult,
                normalizedKeyword, pattern, pattern, safeSize, offset);
        return new PageResponse<>(items, total == null ? 0 : total, safePage, safeSize);
    }

    public Map<String, Object> jobs(String status, String keyword) {
        String s = status == null ? "" : status.trim();
        String kw = keyword == null ? "" : keyword.trim();
        // 运行记录筛选：status 用语义分类（RUNNING/SUCCESS/FAILED），各表状态码不同，分别映射
        StringBuilder planningSql = new StringBuilder("""
                SELECT pj.public_id AS publicId,pj.job_type AS jobType,pj.status,pj.error_code AS errorCode,
                       pj.error_message AS errorMessage,pj.started_at AS startedAt,pj.finished_at AS finishedAt,
                       u.username,g.name AS goalName
                FROM planning_job pj
                JOIN sys_user u ON u.id=pj.user_id
                JOIN learning_goal g ON g.id=pj.goal_id
                WHERE pj.deleted_at IS NULL
                """);
        StringBuilder documentsSql = new StringBuilder("""
                SELECT dj.public_id AS publicId,dj.job_type AS jobType,dj.status,dj.attempts,
                       dj.error_code AS errorCode,dj.error_message AS errorMessage,dj.updated_at AS updatedAt,
                       d.display_name AS documentName,u.username
                FROM document_job dj
                JOIN document_version dv ON dv.id=dj.document_version_id
                JOIN knowledge_document d ON d.id=dv.document_id
                JOIN sys_user u ON u.id=d.owner_user_id
                WHERE 1=1
                """);
        StringBuilder modelRunsSql = new StringBuilder("""
                SELECT mr.public_id AS publicId,mr.purpose,mr.status,mr.token_in AS tokenIn,
                       mr.token_out AS tokenOut,mr.latency_ms AS latencyMs,mr.error_code AS errorCode,
                       mr.created_at AS createdAt,COALESCE(u.username,'SYSTEM') AS username
                FROM model_run mr
                LEFT JOIN sys_user u ON u.id=mr.user_id
                WHERE 1=1
                """);
        List<Object> planningArgs = new ArrayList<>();
        List<Object> documentsArgs = new ArrayList<>();
        List<Object> modelRunArgs = new ArrayList<>();
        switch (s) {
            case "RUNNING" -> {
                planningSql.append(" AND pj.status IN ('QUEUED','RUNNING')");
                documentsSql.append(" AND dj.status='RUNNING'");
                modelRunsSql.append(" AND mr.status='RUNNING'");
            }
            case "SUCCESS" -> {
                planningSql.append(" AND pj.status='SUCCEEDED'");
                documentsSql.append(" AND dj.status='SUCCEEDED'");
                modelRunsSql.append(" AND mr.status='SUCCESS'");
            }
            case "FAILED" -> {
                planningSql.append(" AND pj.status='FAILED'");
                documentsSql.append(" AND dj.status='FAILED'");
                modelRunsSql.append(" AND mr.status='FAILED'");
            }
            default -> { }
        }
        if (!kw.isEmpty()) {
            String like = "%" + kw + "%";
            planningSql.append(" AND (u.username LIKE ? OR g.name LIKE ?)");
            planningArgs.add(like);
            planningArgs.add(like);
            documentsSql.append(" AND (u.username LIKE ? OR d.display_name LIKE ?)");
            documentsArgs.add(like);
            documentsArgs.add(like);
            modelRunsSql.append(" AND (u.username LIKE ? OR mr.purpose LIKE ? OR mr.error_code LIKE ?)");
            modelRunArgs.add(like);
            modelRunArgs.add(like);
            modelRunArgs.add(like);
        }
        planningSql.append(" ORDER BY pj.created_at DESC LIMIT 100");
        documentsSql.append(" ORDER BY dj.created_at DESC LIMIT 100");
        modelRunsSql.append(" ORDER BY mr.created_at DESC LIMIT 100");
        List<Map<String, Object>> planning = jdbc.queryForList(planningSql.toString(), planningArgs.toArray());
        List<Map<String, Object>> documents = jdbc.queryForList(documentsSql.toString(), documentsArgs.toArray());
        List<Map<String, Object>> modelRuns = jdbc.queryForList(modelRunsSql.toString(), modelRunArgs.toArray());
        long running = planning.stream().filter(item -> Set.of("QUEUED", "RUNNING").contains(item.get("status"))).count()
                + documents.stream().filter(item -> "RUNNING".equals(item.get("status"))).count();
        long failed = planning.stream().filter(item -> "FAILED".equals(item.get("status"))).count()
                + documents.stream().filter(item -> "FAILED".equals(item.get("status"))).count()
                + modelRuns.stream().filter(item -> "FAILED".equals(item.get("status"))).count();
        return Map.of(
                "planning", planning,
                "documents", documents,
                "modelRuns", modelRuns,
                "running", running,
                "failed", failed,
                "outboxPending", Optional.ofNullable(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM outbox_event WHERE status='PENDING'", Long.class)).orElse(0L));
    }

    public Map<String, Object> metrics() {
        return Map.ofEntries(
                Map.entry("userCount", count("SELECT COUNT(*) FROM sys_user WHERE deleted_at IS NULL")),
                Map.entry("activeUsers", count("SELECT COUNT(*) FROM sys_user WHERE status='ACTIVE' AND deleted_at IS NULL")),
                Map.entry("activeGoals", count("SELECT COUNT(*) FROM learning_goal WHERE status='ACTIVE' AND deleted_at IS NULL")),
                Map.entry("indexedDocuments", count("SELECT COUNT(*) FROM knowledge_document WHERE status='INDEXED'")),
                Map.entry("publishedQuestions", count("SELECT COUNT(*) FROM question WHERE status='PUBLISHED' AND deleted_at IS NULL")),
                Map.entry("pendingJobs", count("""
                        SELECT (SELECT COUNT(*) FROM planning_job WHERE status IN ('QUEUED','RUNNING'))
                             +(SELECT COUNT(*) FROM document_job WHERE status='RUNNING')
                        """)),
                Map.entry("modelCalls24h", count("SELECT COUNT(*) FROM model_run WHERE created_at>=UTC_TIMESTAMP()-INTERVAL 1 DAY")),
                Map.entry("modelFailures24h", count("""
                        SELECT COUNT(*) FROM model_run
                        WHERE status='FAILED' AND created_at>=UTC_TIMESTAMP()-INTERVAL 1 DAY
                        """)),
                Map.entry("studySeconds7d", count("""
                        SELECT COALESCE(SUM(auto_seconds+manual_seconds),0) FROM daily_study_stat
                        WHERE local_date>=CURRENT_DATE-INTERVAL 6 DAY
                        """)),
                Map.entry("generatedAt", Instant.now()));
    }

    public List<Map<String, Object>> activeGoals() {
        return jdbc.queryForList("""
                SELECT g.public_id AS publicId,
                       u.public_id AS userPublicId,u.username,u.email,
                       g.name,g.type,g.description,g.priority,g.status,
                       COALESCE(d.name,g.custom_direction) AS directionName,
                       g.source_type AS sourceType,g.start_date AS startDate,g.due_date AS dueDate,
                       g.weekly_budget_minutes AS weeklyBudgetMinutes,
                       (SELECT COUNT(*) FROM learning_task t
                         WHERE t.goal_id=g.id AND t.deleted_at IS NULL) AS taskCount,
                       (SELECT COUNT(*) FROM learning_task t
                         WHERE t.goal_id=g.id AND t.lifecycle_status='COMPLETED' AND t.deleted_at IS NULL) AS completedTaskCount,
                       (SELECT p.status FROM learning_plan p
                         WHERE p.goal_id=g.id AND p.deleted_at IS NULL
                         ORDER BY p.updated_at DESC,p.id DESC LIMIT 1) AS planStatus,
                       g.created_at AS createdAt,g.updated_at AS updatedAt
                FROM learning_goal g
                JOIN sys_user u ON u.id=g.user_id AND u.deleted_at IS NULL
                LEFT JOIN learning_direction d ON d.id=g.direction_id
                WHERE g.status='ACTIVE' AND g.deleted_at IS NULL
                ORDER BY
                  CASE g.priority
                    WHEN 'URGENT' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4
                  END,
                  g.due_date,u.username,g.updated_at DESC
                """);
    }

    /** 总览可视化：近 14 日学习/任务趋势、近 7 日模型调用、目标状态分布、学习时长 Top5。 */
    public Map<String, Object> dashboardCharts() {
        List<Map<String, Object>> studyDaily = jdbc.queryForList("""
                SELECT local_date AS date,
                       COALESCE(SUM(auto_seconds),0) AS autoSeconds,
                       COALESCE(SUM(manual_seconds),0) AS manualSeconds
                FROM daily_study_stat
                WHERE local_date>=CURRENT_DATE-INTERVAL 13 DAY
                GROUP BY local_date ORDER BY local_date
                """);
        List<Map<String, Object>> taskDaily = jdbc.queryForList("""
                SELECT local_date AS date,
                       COALESCE(SUM(planned_tasks),0) AS planned,
                       COALESCE(SUM(completed_tasks),0) AS completed,
                       COALESCE(SUM(overdue_tasks),0) AS overdue
                FROM daily_study_stat
                WHERE local_date>=CURRENT_DATE-INTERVAL 13 DAY
                GROUP BY local_date ORDER BY local_date
                """);
        List<Map<String, Object>> modelDaily = jdbc.queryForList("""
                SELECT DATE(created_at) AS date,
                       SUM(CASE WHEN status='SUCCESS' THEN 1 ELSE 0 END) AS success,
                       SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END) AS failed
                FROM model_run
                WHERE created_at>=UTC_TIMESTAMP()-INTERVAL 6 DAY
                GROUP BY DATE(created_at) ORDER BY date
                """);
        List<Map<String, Object>> goalStatus = jdbc.queryForList("""
                SELECT status,COUNT(*) AS count FROM learning_goal
                WHERE deleted_at IS NULL
                GROUP BY status ORDER BY count DESC
                """);
        List<Map<String, Object>> topLearners = jdbc.queryForList("""
                SELECT u.username,u.email,
                       COALESCE(SUM(d.auto_seconds+d.manual_seconds),0) AS seconds
                FROM daily_study_stat d
                JOIN sys_user u ON u.id=d.user_id AND u.deleted_at IS NULL
                WHERE d.local_date>=CURRENT_DATE-INTERVAL 6 DAY
                GROUP BY u.id,u.username,u.email
                ORDER BY seconds DESC LIMIT 5
                """);
        return Map.of(
                "studyDaily", studyDaily,
                "taskDaily", taskDaily,
                "modelDaily", modelDaily,
                "goalStatus", goalStatus,
                "topLearners", topLearners);
    }

    /** 全部用户的知识空间（含所有者与文档/索引/分类/占用统计），按用户名、邮箱或空间名搜索。 */
    public List<Map<String, Object>> knowledgeSpaces(String keyword, String userId) {
        String pattern = blankToNull(keyword) == null ? null : "%" + keyword.trim() + "%";
        String userPattern = blankToNull(userId);
        return jdbc.queryForList("""
                SELECT s.public_id AS publicId,s.name,s.status,s.visibility,
                       COALESCE(d.name,'') AS directionName,
                       s.created_at AS createdAt,s.updated_at AS updatedAt,
                       u.public_id AS userPublicId,u.username,u.email,
                       (SELECT COUNT(*) FROM knowledge_document doc
                         WHERE doc.space_id=s.id AND doc.deleted_at IS NULL) AS documentCount,
                       (SELECT COUNT(*) FROM knowledge_document doc
                         WHERE doc.space_id=s.id AND doc.status='INDEXED' AND doc.deleted_at IS NULL) AS indexedCount,
                       (SELECT COUNT(*) FROM resource_category c
                         WHERE c.space_id=s.id AND c.deleted_at IS NULL) AS categoryCount,
                       (SELECT COALESCE(SUM(o.file_size),0) FROM knowledge_document doc
                         JOIN document_version v ON v.document_id=doc.id AND v.version_no=doc.active_version_no
                         JOIN stored_object o ON o.id=v.stored_object_id
                         WHERE doc.space_id=s.id AND doc.deleted_at IS NULL) AS totalSize
                FROM knowledge_space s
                JOIN sys_user u ON u.id=s.user_id AND u.deleted_at IS NULL
                LEFT JOIN learning_direction d ON d.id=s.direction_id
                WHERE s.deleted_at IS NULL
                  AND (? IS NULL OR u.username LIKE ? OR u.email LIKE ? OR s.name LIKE ?)
                  AND (? IS NULL OR s.user_id=?)
                ORDER BY s.updated_at DESC
                """, pattern, pattern, pattern, pattern, userPattern, userPattern);
    }

    /** 指定知识空间（任意用户）的文档列表，附带分类路径、文件大小与解析方式。 */
    public List<Map<String, Object>> adminSpaceDocuments(String spacePublicId) {
        return jdbc.queryForList("""
                SELECT doc.public_id AS publicId,doc.display_name AS displayName,
                       doc.status,doc.active_version_no AS activeVersionNo,doc.visibility,
                       doc.created_at AS createdAt,doc.updated_at AS updatedAt,doc.version,
                       COALESCE(c.path,'') AS categoryPath,
                       COALESCE(o.file_size,0) AS fileSize,
                       COALESCE(v.parser_version,'') AS parserVersion
                FROM knowledge_document doc
                JOIN knowledge_space s ON s.id=doc.space_id AND s.deleted_at IS NULL
                LEFT JOIN resource_category c ON c.id=doc.category_id AND c.deleted_at IS NULL
                LEFT JOIN document_version v ON v.document_id=doc.id AND v.version_no=doc.active_version_no
                LEFT JOIN stored_object o ON o.id=v.stored_object_id
                WHERE s.public_id=? AND doc.deleted_at IS NULL
                ORDER BY doc.updated_at DESC
                """, spacePublicId);
    }

    /** 指定文档（任意用户）当前版本的全部切块内容，用于管理员预览。 */
    public List<Map<String, Object>> adminDocumentContent(String documentPublicId) {
        return jdbc.queryForList("""
                SELECT c.chunk_no AS chunkNo,c.text,c.token_count AS tokenCount,
                       c.page_from AS pageFrom,c.page_to AS pageTo,
                       c.paragraph_from AS paragraphFrom,c.paragraph_to AS paragraphTo,
                       c.title_path_json AS titlePath
                FROM knowledge_chunk c
                JOIN document_version v ON v.id=c.document_version_id
                JOIN knowledge_document doc ON doc.id=v.document_id
                WHERE doc.public_id=? AND v.version_no=doc.active_version_no
                ORDER BY c.chunk_no
                """, documentPublicId);
    }

    private Map<String, Object> firstRow(String sql, Object... arguments) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, arguments);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Object readStoredJson(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> || value instanceof List<?>) return value;
        String raw = value instanceof byte[] bytes
                ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                : String.valueOf(value);
        if (raw.isBlank()) return null;
        try {
            return json.readValue(raw, Object.class);
        } catch (JsonProcessingException error) {
            log.warn("Could not parse stored JSON for admin learning file");
            return raw;
        }
    }

    private Map<String, Object> ownedAdminUser(String publicId) {
        Map<String, Object> user = jdbc.query("""
                SELECT id,status,version FROM sys_user WHERE public_id=? AND deleted_at IS NULL
                """, rs -> {
            if (!rs.next()) return null;
            return Map.of("id", rs.getLong(1), "status", rs.getString(2), "version", rs.getInt(3));
        }, publicId);
        if (user == null) notFound();
        return user;
    }

    private long count(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        return value == null ? 0 : value.longValue();
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private List<String> splitCodes(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return List.of();
        return Arrays.stream(String.valueOf(value).split(",")).filter(item -> !item.isBlank()).toList();
    }

    private void stringifyIds(Map<String, Object> item, String... keys) {
        for (String key : keys) {
            Object value = item.get(key);
            if (value != null) item.put(key, String.valueOf(value));
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void bad(String message) {
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, message);
    }

    private void conflict() {
        throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "资源版本冲突，请刷新后重试");
    }

    private void notFound() {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资源不存在");
    }
}
