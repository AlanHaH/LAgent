package com.adaptivelearning.goalproject.application;

import com.adaptivelearning.evaluation.application.AssessmentService;
import com.adaptivelearning.execution.application.TaskCancellationService;
import com.adaptivelearning.goalproject.domain.*;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.*;
import com.adaptivelearning.shared.api.PageResponse;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.support.application.AuditService;
import com.adaptivelearning.support.infrastructure.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class GoalProjectService {
    private final GoalMapper goalMapper;
    private final ProjectMapper projectMapper;
    private final MilestoneMapper milestoneMapper;
    private final GoalProjectMapper linkMapper;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final AssessmentService assessments;
    private final GoalRecommendationBatchStore recommendationBatches;
    private final UserMapper userMapper;
    private final TaskCancellationService taskCancellation;

    public record GoalInput(Long directionId, String customDirection, String name, String type, String description, String priority,
                            LocalDate startDate, LocalDate dueDate, int weeklyBudgetMinutes,
                            List<Map<String, Object>> successCriteria, Integer version, String changeReason,
                            String sourceType, Long profileVersionId, String recommendationId,
                            String recommendationReason) {
    }

    public record ProjectInput(Long primaryDirectionId, String name, String description, LocalDate startDate,
                               LocalDate dueDate, String priority, List<Map<String, Object>> deliverables,
                               String repositoryUrl, Integer version) {
    }

    public record MilestoneInput(String name, int sequenceNo, LocalDate dueDate, BigDecimal weight,
                                 List<Map<String, Object>> acceptanceCriteria, Integer version) {
    }

    public record MilestoneCompletionInput(Integer version, String summary,
                                           List<Map<String, Object>> criteria) { }

    public record GoalLinkView(String goalId, String goalName, String goalStatus,
                               BigDecimal contributionWeight) { }

    public record CreateGoalResult(LearningGoalEntity goal, LearningProjectEntity project) {
    }

    private record CatalogDirection(long id, String code, String name) {
    }

    @Transactional
    public LearningGoalEntity createGoal(GoalInput input) {
        validateGoal(input);
        String sourceType = sourceType(input);
        GoalRecommendationBatchStore.VerifiedRecommendation recommendation = "CUSTOM".equals(sourceType)
                ? null : recommendationBatches.verifyForAdoption(SecurityUtils.currentUserId(),
                input.recommendationId(), sourceType, input.profileVersionId());
        requireGoalDirection(input, recommendation);
        LearningGoalEntity goal = new LearningGoalEntity();
        goal.setPublicId(UUID.randomUUID().toString());
        goal.setUserId(SecurityUtils.currentUserId());
        apply(goal, input);
        applySource(goal, input, recommendation);
        goal.setStatus(GoalStatus.DRAFT.name());
        goalMapper.insert(goal);
        audit.record("GOAL_CREATE", "LEARNING_GOAL", goal.getPublicId(), null, goal.getName(), "SUCCESS");
        return goal;
    }

    @Transactional
    public LearningGoalEntity updateGoal(String publicId, GoalInput input) {
        LearningGoalEntity goal = ownedGoal(publicId);
        validateVersion(goal.getVersion(), input.version());
        if (Set.of(GoalStatus.COMPLETED.name(), GoalStatus.CANCELED.name()).contains(goal.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "已结束目标不可修改");
        }
        validateGoal(input);
        requireGoalDirectionForUpdate(goal, input);
        if (!GoalStatus.DRAFT.name().equals(goal.getStatus()) && (input.changeReason() == null || input.changeReason().isBlank())) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "活动目标的关键修改必须填写变更原因");
        }
        String before = goal.getName() + ",due=" + goal.getDueDate();
        apply(goal, input);
        if (goalMapper.updateById(goal) != 1) conflict();
        audit.record("GOAL_UPDATE", "LEARNING_GOAL", publicId, before,
            goal.getName() + ",due=" + goal.getDueDate() + ",reason=" + input.changeReason(), "SUCCESS");
        return ownedGoal(publicId);
    }

    public PageResponse<LearningGoalEntity> goals(int page, int pageSize, String status) {
        LambdaQueryWrapper<LearningGoalEntity> query = new LambdaQueryWrapper<LearningGoalEntity>()
            .eq(LearningGoalEntity::getUserId, SecurityUtils.currentUserId())
            .eq(status != null, LearningGoalEntity::getStatus, status)
            .orderByAsc(LearningGoalEntity::getDueDate);
        Page<LearningGoalEntity> result = goalMapper.selectPage(Page.of(page, Math.min(pageSize, 100)), query);
        return new PageResponse<>(result.getRecords(), result.getTotal(), page, Math.min(pageSize, 100));
    }

    public LearningGoalEntity getGoal(String publicId) {
        return ownedGoal(publicId);
    }

    @Transactional
    public LearningGoalEntity setGoalCriterion(String publicId, int index, boolean completed, Integer version) {
        LearningGoalEntity goal = ownedGoal(publicId);
        validateVersion(goal.getVersion(), version);
        if (Set.of(GoalStatus.COMPLETED.name(), GoalStatus.CANCELED.name()).contains(goal.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "已结束目标不能修改成功标准");
        }
        JsonNode parsed = readJson(goal.getSuccessCriteriaJson());
        if (!parsed.isArray() || index < 0 || index >= parsed.size()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "成功标准序号无效");
        }
        ArrayNode criteria = ((ArrayNode) parsed).deepCopy();
        JsonNode item = criteria.get(index);
        if (!item.isObject()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "成功标准格式无效");
        }
        ((ObjectNode) item).put("completed", completed);
        goal.setSuccessCriteriaJson(json(criteria));
        if (goalMapper.updateById(goal) != 1) conflict();
        audit.record("GOAL_CRITERION_UPDATE", "LEARNING_GOAL", publicId, null,
                "index=" + index + ",completed=" + completed, "SUCCESS");
        return ownedGoal(publicId);
    }

    @Transactional
    public LearningGoalEntity transitionGoal(String publicId, GoalStatus target, String reason, boolean exceptionConfirmed) {
        long userId = SecurityUtils.currentUserId();
        lockUser(userId);
        LearningGoalEntity goal = goalMapper.lockOwnedByPublicId(publicId, userId);
        if (goal == null) notFound();
        GoalStatus current = GoalStatus.valueOf(goal.getStatus());
        current.requireCanTransitionTo(target);
        if (target == GoalStatus.ACTIVE) validateGoalActivation(goal);
        // 项目型目标首次「开始推进」时，同一事务联动激活配套 DRAFT 项目；
        // PAUSED→ACTIVE（resume）不联动，避免覆盖用户主动暂停项目的意图。
        List<LearningProjectEntity> draftProjects = target == GoalStatus.ACTIVE && current == GoalStatus.DRAFT
                && "PROJECT".equals(goal.getType())
                ? projectsForGoal(publicId).stream().filter(p -> ProjectStatus.DRAFT.name().equals(p.getStatus())).toList()
                : List.of();
        if (target == GoalStatus.COMPLETED && !criteriaComplete(goal.getSuccessCriteriaJson()) && !exceptionConfirmed) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "成功标准尚未全部满足，需要明确例外确认");
        }
        if (target == GoalStatus.CANCELED && (reason == null || reason.isBlank())) {
            throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED, "取消目标必须二次确认并填写原因");
        }
        goal.setStatus(target.name());
        if (target == GoalStatus.COMPLETED) goal.setAcceptanceSnapshotJson(json(Map.of(
            "criteria", readJson(goal.getSuccessCriteriaJson()), "exceptionConfirmed", exceptionConfirmed,
            "acceptedAt", Instant.now(), "reason", reason == null ? "" : reason)));
        if (goalMapper.updateById(goal) != 1) conflict();
        if (target == GoalStatus.PAUSED) {
            taskCancellation.quiesceGoal(goal.getId(), userId, TaskCancellationService.QuiesceMode.PAUSE);
        } else if (target == GoalStatus.CANCELED || target == GoalStatus.COMPLETED) {
            taskCancellation.quiesceGoal(goal.getId(), userId, TaskCancellationService.QuiesceMode.STOP);
        }
        for (LearningProjectEntity project : draftProjects) {
            transitionProject(project.getPublicId(), ProjectStatus.ACTIVE,
                    "目标「" + goal.getName() + "」开始推进，项目联动激活", false);
        }
        audit.record("GOAL_" + target, "LEARNING_GOAL", publicId, current.name(), target + ":" + reason, "SUCCESS");
        return ownedGoal(publicId);
    }

    @Transactional
    public LearningProjectEntity createProject(ProjectInput input) {
        validateProject(input);
        LearningProjectEntity project = new LearningProjectEntity();
        project.setPublicId(UUID.randomUUID().toString());
        project.setUserId(SecurityUtils.currentUserId());
        apply(project, input);
        project.setStatus(ProjectStatus.DRAFT.name());
        projectMapper.insert(project);
        return project;
    }

    @Transactional
    public LearningProjectEntity createConfiguredProject(ProjectInput input, String goalPublicId,
                                                          List<String> milestoneNames) {
        if (goalPublicId == null || goalPublicId.isBlank() || milestoneNames == null || milestoneNames.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "实践项目必须关联目标并至少设置一个里程碑");
        }
        List<String> names = milestoneNames.stream().map(String::trim).filter(value -> !value.isBlank())
                .distinct().limit(10).toList();
        if (names.isEmpty()) throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "里程碑不能为空");
        LearningProjectEntity project = createProject(input);
        linkGoal(project.getPublicId(), goalPublicId, BigDecimal.ONE);
        BigDecimal regular = BigDecimal.ONE.divide(BigDecimal.valueOf(names.size()), 4, RoundingMode.DOWN);
        BigDecimal allocated = BigDecimal.ZERO;
        long totalDays = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(input.startDate(), input.dueDate()));
        for (int index = 0; index < names.size(); index++) {
            BigDecimal weight = index == names.size() - 1 ? BigDecimal.ONE.subtract(allocated) : regular;
            allocated = allocated.add(weight);
            long dayOffset = Math.max(0, Math.round(totalDays * ((index + 1d) / names.size())));
            LocalDate dueDate = input.startDate().plusDays(dayOffset);
            addMilestone(project.getPublicId(), new MilestoneInput(names.get(index), index + 1, dueDate, weight,
                    List.of(Map.of("description", names.get(index) + "验收通过", "completed", false)), null));
        }
        return ownedProject(project.getPublicId());
    }

    @Transactional
    public CreateGoalResult createGoalWithProject(GoalInput input, List<String> milestoneNames, String repositoryUrl) {
        LearningGoalEntity goal = createGoal(input);
        LearningProjectEntity project = null;
        if ("PROJECT".equalsIgnoreCase(input.type())) {
            List<String> names = milestoneNames == null ? List.of()
                    : milestoneNames.stream().map(String::trim).filter(value -> !value.isBlank()).toList();
            if (names.isEmpty()) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "项目型目标必须至少填写一个里程碑");
            }
            ProjectInput projectInput = new ProjectInput(input.directionId(), input.name(), input.description(),
                    input.startDate(), input.dueDate(), input.priority(),
                    List.of(Map.<String, Object>of("name", "项目成果")), repositoryUrl, null);
            project = createConfiguredProject(projectInput, goal.getPublicId(), names);
        }
        return new CreateGoalResult(goal, project);
    }

    @Transactional
    public LearningProjectEntity updateProject(String publicId, ProjectInput input) {
        LearningProjectEntity project = ownedProjectForUpdate(publicId);
        requireDraftProject(project);
        validateVersion(project.getVersion(), input.version());
        validateProject(input);
        apply(project, input);
        if (projectMapper.updateById(project) != 1) conflict();
        return ownedProject(publicId);
    }

    public PageResponse<LearningProjectEntity> projects(int page, int pageSize, String status) {
        var query = new LambdaQueryWrapper<LearningProjectEntity>()
            .eq(LearningProjectEntity::getUserId, SecurityUtils.currentUserId())
            .eq(status != null, LearningProjectEntity::getStatus, status)
            .orderByAsc(LearningProjectEntity::getDueDate);
        Page<LearningProjectEntity> result = projectMapper.selectPage(Page.of(page, Math.min(pageSize, 100)), query);
        return new PageResponse<>(result.getRecords(), result.getTotal(), page, Math.min(pageSize, 100));
    }

    public List<LearningProjectEntity> projectsForGoal(String goalPublicId) {
        LearningGoalEntity goal = ownedGoal(goalPublicId);
        List<Long> ids = jdbc.query("""
                SELECT p.id
                FROM learning_project p
                JOIN goal_project gp ON gp.project_id=p.id
                WHERE gp.goal_id=? AND p.user_id=? AND p.deleted_at IS NULL
                ORDER BY p.due_date,p.id
                """, (rs, row) -> rs.getLong(1), goal.getId(), goal.getUserId());
        return ids.isEmpty() ? List.of() : ids.stream().map(projectMapper::selectById)
                .filter(Objects::nonNull).toList();
    }

    public LearningProjectEntity getProject(String publicId) {
        return ownedProject(publicId);
    }

    @Transactional
    public void linkGoal(String projectPublicId, String goalPublicId, BigDecimal weight) {
        LearningProjectEntity project = ownedProjectForUpdate(projectPublicId);
        LearningGoalEntity goal = ownedGoal(goalPublicId);
        if (Set.of("CANCELED", "COMPLETED").contains(goal.getStatus())
                || !ProjectStatus.DRAFT.name().equals(project.getStatus()))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "已结束目标或项目不能建立新关联");
        BigDecimal current = Optional.ofNullable(jdbc.queryForObject(
                "SELECT COALESCE(SUM(contribution_weight),0) FROM goal_project WHERE goal_id=?", BigDecimal.class, goal.getId()))
            .orElse(BigDecimal.ZERO);
        if (weight == null || weight.signum() <= 0 || current.add(weight).compareTo(BigDecimal.ONE) > 0)
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "目标的项目贡献权重合计不能超过 100%");
        GoalProjectEntity link = new GoalProjectEntity();
        link.setGoalId(goal.getId());
        link.setProjectId(project.getId());
        link.setContributionWeight(weight);
        linkMapper.insert(link);
    }

    @Transactional
    public void unlinkGoal(String projectPublicId, String goalPublicId) {
        LearningProjectEntity project = ownedProjectForUpdate(projectPublicId);
        requireDraftProject(project);
        LearningGoalEntity goal = ownedGoal(goalPublicId);
        linkMapper.unlink(goal.getId(), project.getId());
    }

    public List<GoalLinkView> goalLinks(String projectPublicId) {
        LearningProjectEntity project = ownedProject(projectPublicId);
        return jdbc.query("""
                SELECT g.public_id,g.name,g.status,gp.contribution_weight
                FROM goal_project gp JOIN learning_goal g ON g.id=gp.goal_id
                WHERE gp.project_id=? AND g.user_id=? AND g.deleted_at IS NULL
                ORDER BY g.due_date,g.id
                """, (rs, row) -> new GoalLinkView(rs.getString("public_id"), rs.getString("name"),
                rs.getString("status"), rs.getBigDecimal("contribution_weight")),
                project.getId(), project.getUserId());
    }

    @Transactional
    public void updateGoalLink(String projectPublicId, String goalPublicId, BigDecimal weight) {
        LearningProjectEntity project = ownedProjectForUpdate(projectPublicId);
        requireDraftProject(project);
        LearningGoalEntity goal = ownedGoal(goalPublicId);
        BigDecimal other = Optional.ofNullable(jdbc.queryForObject("""
                SELECT COALESCE(SUM(contribution_weight),0) FROM goal_project
                WHERE goal_id=? AND project_id<>?
                """, BigDecimal.class, goal.getId(), project.getId())).orElse(BigDecimal.ZERO);
        if (weight == null || weight.signum() <= 0 || other.add(weight).compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "目标的项目贡献权重合计不能超过 100%");
        }
        int updated = jdbc.update("""
                UPDATE goal_project SET contribution_weight=? WHERE goal_id=? AND project_id=?
                """, weight, goal.getId(), project.getId());
        if (updated != 1) notFound();
    }

    public List<MilestoneEntity> milestones(String projectPublicId) {
        LearningProjectEntity project = ownedProject(projectPublicId);
        return milestoneMapper.selectList(new LambdaQueryWrapper<MilestoneEntity>()
            .eq(MilestoneEntity::getProjectId, project.getId()).orderByAsc(MilestoneEntity::getSequenceNo));
    }

    @Transactional
    public MilestoneEntity addMilestone(String projectPublicId, MilestoneInput input) {
        LearningProjectEntity project = ownedProjectForUpdate(projectPublicId);
        requireDraftProject(project);
        validateMilestone(project, input);
        MilestoneEntity m = new MilestoneEntity();
        m.setPublicId(UUID.randomUUID().toString());
        m.setProjectId(project.getId());
        apply(m, input);
        m.setStatus("NOT_STARTED");
        milestoneMapper.insert(m);
        return m;
    }

    @Transactional
    public MilestoneEntity updateMilestone(String publicId, MilestoneInput input) {
        MilestoneEntity observed = ownedMilestone(publicId);
        LearningProjectEntity project = ownedProjectForUpdateById(observed.getProjectId());
        MilestoneEntity milestone = ownedMilestoneForUpdate(publicId);
        requireDraftProject(project);
        validateVersion(milestone.getVersion(), input.version());
        validateMilestone(project, input);
        apply(milestone, input);
        if (milestoneMapper.updateById(milestone) != 1) conflict();
        return ownedMilestone(publicId);
    }

    @Transactional
    public MilestoneEntity completeMilestone(String publicId, MilestoneCompletionInput input) {
        long userId = SecurityUtils.currentUserId();
        lockUser(userId);
        MilestoneEntity observed = ownedMilestone(publicId);
        LearningProjectEntity project = ownedProjectForUpdateById(observed.getProjectId());
        MilestoneEntity milestone = ownedMilestoneForUpdate(publicId);
        if ("COMPLETED".equals(milestone.getStatus())) return milestone;
        if (!ProjectStatus.ACTIVE.name().equals(project.getStatus()))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "只有进行中的项目可以验收里程碑");
        if ("CANCELED".equals(milestone.getStatus()))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "已取消里程碑不能完成");
        validateVersion(milestone.getVersion(), input == null ? null : input.version());
        Map<String, Object> evidence = verifiedMilestoneEvidence(milestone, input);
        milestone.setStatus("COMPLETED");
        milestone.setCompletionEvidenceJson(json(evidence));
        milestone.setCompletedAt(Instant.now());
        if (milestoneMapper.updateById(milestone) != 1) conflict();
        taskCancellation.quiesceMilestone(milestone.getId(), userId,
                TaskCancellationService.QuiesceMode.STOP);
        assessments.recordProjectMilestoneEvidence(project.getUserId(), project.getId(), milestone.getId(),
                milestone.getWeight(), milestone.getCompletedAt());
        emitProjectMilestoneEvent(project, milestone);
        return milestone;
    }

    private void emitProjectMilestoneEvent(LearningProjectEntity project, MilestoneEntity milestone) {
        jdbc.update("""
                INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,correlation_id,
                                         status,attempts,next_retry_at,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), "PROJECT_MILESTONE",
                milestone.getPublicId(), "MilestoneCompleted",
                json(Map.of("userId", project.getUserId(), "projectId", project.getId(),
                        "milestoneId", milestone.getId())), UUID.randomUUID().toString(),
                "PENDING", 0, Instant.now(), Instant.now());
    }

    @Transactional
    public MilestoneEntity cancelMilestone(String publicId) {
        long userId = SecurityUtils.currentUserId();
        lockUser(userId);
        MilestoneEntity observed = ownedMilestone(publicId);
        LearningProjectEntity project = ownedProjectForUpdateById(observed.getProjectId());
        MilestoneEntity milestone = ownedMilestoneForUpdate(publicId);
        requireDraftProject(project);
        if ("COMPLETED".equals(milestone.getStatus()))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "已完成里程碑不能取消");
        milestone.setStatus("CANCELED");
        if (milestoneMapper.updateById(milestone) != 1) conflict();
        taskCancellation.quiesceMilestone(milestone.getId(), userId,
                TaskCancellationService.QuiesceMode.STOP);
        return ownedMilestone(publicId);
    }

    @Transactional
    public LearningProjectEntity transitionProject(String publicId, ProjectStatus target, String reason, boolean impactConfirmed) {
        long userId = SecurityUtils.currentUserId();
        lockUser(userId);
        LearningProjectEntity project = ownedProjectForUpdate(publicId);
        ProjectStatus current = ProjectStatus.valueOf(project.getStatus());
        current.requireCanTransitionTo(target);
        if (target == ProjectStatus.ACTIVE) validateProjectActivation(project, impactConfirmed);
        if (target == ProjectStatus.COMPLETED) {
            long open = milestoneMapper.selectCount(new LambdaQueryWrapper<MilestoneEntity>().eq(MilestoneEntity::getProjectId, project.getId())
                .notIn(MilestoneEntity::getStatus, "COMPLETED", "CANCELED"));
            if (open > 0 && !impactConfirmed)
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "仍有未验收里程碑");
        }
        if (target == ProjectStatus.CANCELED && (reason == null || reason.isBlank()))
            throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED, "取消项目必须确认并填写原因");
        project.setStatus(target.name());
        if (projectMapper.updateById(project) != 1) conflict();
        if (target == ProjectStatus.PAUSED) {
            taskCancellation.quiesceProject(project.getId(), userId,
                    TaskCancellationService.QuiesceMode.PAUSE);
        } else if (target == ProjectStatus.CANCELED || target == ProjectStatus.COMPLETED
                || target == ProjectStatus.ARCHIVED) {
            taskCancellation.quiesceProject(project.getId(), userId,
                    TaskCancellationService.QuiesceMode.STOP);
        }
        audit.record("PROJECT_" + target, "LEARNING_PROJECT", publicId, current.name(), target + ":" + reason, "SUCCESS");
        return project;
    }

    public Map<String, Object> progress(String goalPublicId) {
        LearningGoalEntity goal = ownedGoal(goalPublicId);
        List<Map<String, Object>> projects = jdbc.query("""
            SELECT gp.contribution_weight, p.id, p.public_id,p.name,p.status,
                   CASE WHEN p.status='COMPLETED' THEN 1 ELSE
                   COALESCE(SUM(CASE WHEN m.status='COMPLETED' THEN m.weight ELSE 0 END)
                     / NULLIF(SUM(m.weight),0),0) project_progress
                   END project_progress
            FROM goal_project gp JOIN learning_project p ON p.id=gp.project_id
            LEFT JOIN milestone m ON m.project_id=p.id AND m.status<>'CANCELED' AND m.deleted_at IS NULL
            WHERE gp.goal_id=? AND p.deleted_at IS NULL AND p.status NOT IN ('CANCELED','ARCHIVED')
            GROUP BY gp.contribution_weight,p.id,p.public_id,p.name,p.status
            """, (rs, row) -> Map.of("weight", rs.getBigDecimal("contribution_weight"), "projectId", rs.getString("public_id"),
            "name", rs.getString("name"), "progress", rs.getBigDecimal("project_progress")), goal.getId());
        BigDecimal projectContribution = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (Map<String, Object> p : projects) {
            BigDecimal w = (BigDecimal) p.get("weight"), progress = (BigDecimal) p.get("progress");
            projectContribution = projectContribution.add(w.multiply(progress));
            totalWeight = totalWeight.add(w);
        }
        Map<String, Object> taskProgress = directTaskProgress(goal.getId(), totalWeight.signum() == 0);
        BigDecimal t = (BigDecimal) taskProgress.get("ratio");
        BigDecimal value = totalWeight.signum() == 0 ? t : projectContribution.add(BigDecimal.ONE.subtract(totalWeight).multiply(t));
        value = value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return Map.of("value", value.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP),
            "numerator", value, "denominator", BigDecimal.ONE, "calculatedAt", Instant.now(),
            "projectComponents", projects, "nonProjectTasks", taskProgress, "metricVersion", "1.0");
    }

    private Map<String, Object> directTaskProgress(long goalId, boolean includeProjectTasks) {
        String filter = includeProjectTasks ? "" : " AND task.project_id IS NULL";
        Map<String, Object> value = jdbc.queryForMap("""
            SELECT COALESCE(SUM(CASE WHEN task.lifecycle_status='COMPLETED' AND NOT EXISTS (
                                         SELECT 1 FROM learning_block block
                                         WHERE block.task_id=task.id AND block.deleted_at IS NULL AND block.status<>'COMPLETED')
                                         THEN GREATEST(task.estimated_minutes,0) ELSE 0 END),0) done_minutes,
                   COALESCE(SUM(GREATEST(task.estimated_minutes,0)),0) total_minutes,
                   COALESCE(SUM(CASE WHEN task.lifecycle_status='COMPLETED' AND NOT EXISTS (
                                         SELECT 1 FROM learning_block block
                                         WHERE block.task_id=task.id AND block.deleted_at IS NULL AND block.status<>'COMPLETED')
                            THEN 1 ELSE 0 END),0) done_count, COUNT(*) total_count
            FROM learning_task task
            WHERE task.goal_id=? AND task.lifecycle_status<>'CANCELED' AND task.deleted_at IS NULL
              AND (task.project_id IS NULL OR EXISTS (
                    SELECT 1 FROM learning_project valid_project
                    WHERE valid_project.id=task.project_id AND valid_project.deleted_at IS NULL
                      AND valid_project.status NOT IN ('CANCELED','ARCHIVED')
              ))
            """ + filter, goalId);
        BigDecimal total = new BigDecimal(value.get("total_minutes").toString()), done = new BigDecimal(value.get("done_minutes").toString());
        if (total.signum() == 0) {
            total = new BigDecimal(value.get("total_count").toString());
            done = new BigDecimal(value.get("done_count").toString());
        }
        BigDecimal ratio = total.signum() == 0 ? BigDecimal.ZERO : done.divide(total, 6, RoundingMode.HALF_UP);
        return Map.of("ratio", ratio, "numerator", done, "denominator", total);
    }

    private void validateGoalActivation(LearningGoalEntity goal) {
        if (!criteriaPresent(goal.getSuccessCriteriaJson()))
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "目标至少需要一项成功标准");
        Integer weekly = jdbc.queryForObject("SELECT COALESCE(SUM(available_minutes),0) FROM availability_rule WHERE user_id=? AND deleted_at IS NULL", Integer.class, goal.getUserId());
        BigDecimal ratio = jdbc.query("SELECT capacity_ratio FROM learning_preference WHERE user_id=? AND deleted_at IS NULL",
            rs -> rs.next() ? rs.getBigDecimal(1) : new BigDecimal("0.85"), goal.getUserId());
        int capacity = BigDecimal.valueOf(weekly == null ? 0 : weekly).multiply(ratio).intValue();
        if (capacity <= 0 || goal.getWeeklyBudgetMinutes() > capacity)
            throw new BusinessException(ErrorCode.PLAN_CAPACITY_EXCEEDED, "目标每周预算超过可规划容量",
                Map.of("weeklyBudgetMinutes", goal.getWeeklyBudgetMinutes(), "capacityMinutes", capacity));
    }

    private void validateProjectActivation(LearningProjectEntity project, boolean impactConfirmed) {
        Integer links = jdbc.queryForObject("""
                SELECT COUNT(*) FROM goal_project gp JOIN learning_goal g ON g.id=gp.goal_id
                WHERE gp.project_id=? AND g.status IN ('ACTIVE','PAUSED') AND g.deleted_at IS NULL
                """, Integer.class, project.getId());
        if (links == null || links == 0)
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "项目激活前至少关联一个正在推进的目标");
        BigDecimal total = jdbc.queryForObject("SELECT COALESCE(SUM(weight),0) FROM milestone WHERE project_id=? AND status<>'CANCELED' AND deleted_at IS NULL", BigDecimal.class, project.getId());
        if (total == null || total.compareTo(BigDecimal.ONE) != 0)
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "非取消里程碑权重合计必须为 100%");
        LocalDate earliest = jdbc.query("SELECT MIN(g.due_date) FROM learning_goal g JOIN goal_project gp ON gp.goal_id=g.id WHERE gp.project_id=? AND g.status<>'CANCELED'",
            rs -> rs.next() ? rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate() : null, project.getId());
        if (earliest != null && project.getDueDate().isAfter(earliest) && !impactConfirmed)
            throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED, "项目结束日晚于关联目标最早截止日，需要确认影响");
    }

    private void validateGoal(GoalInput i) {
        if (i.name() == null || i.name().trim().length() < 2 || i.name().length() > 100 || i.startDate() == null || i.dueDate() == null || i.dueDate().isBefore(i.startDate())
            || i.weeklyBudgetMinutes() < 10 || i.weeklyBudgetMinutes() > 6720 || i.successCriteria() == null || i.successCriteria().isEmpty()
            || i.type() == null || !Set.of("SKILL", "PROJECT", "EXAM").contains(i.type())
            || i.priority() == null || !Set.of("LOW", "MEDIUM", "HIGH", "URGENT").contains(i.priority())
            || i.description() != null && i.description().length() > 2000
            || i.successCriteria().size() > 5 || i.successCriteria().stream().anyMatch(item ->
                Objects.toString(item.get("description"), "").trim().length() < 2
                        || Objects.toString(item.get("description"), "").length() > 500))
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "目标名称、日期、预算或成功标准不合法");
        boolean catalog = i.directionId() != null;
        boolean custom = i.customDirection() != null && !i.customDirection().isBlank();
        if (catalog == custom || custom && i.customDirection().trim().length() > 120)
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "目录方向和自定义方向必须且只能填写一个");
    }

    private void validateProject(ProjectInput i) {
        if (i.name() == null || i.name().isBlank() || i.name().length() > 120 || i.startDate() == null || i.dueDate() == null || i.dueDate().isBefore(i.startDate()))
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "项目名称或日期不合法");
        if (i.priority() == null || !Set.of("LOW", "MEDIUM", "HIGH", "URGENT").contains(i.priority())
                || i.description() != null && i.description().length() > 2000) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "项目优先级或说明不合法");
        }
        if (i.repositoryUrl() != null && !i.repositoryUrl().isBlank()) try {
            String scheme = URI.create(i.repositoryUrl()).getScheme();
            if (!Set.of("https", "http").contains(scheme)) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "项目链接仅允许 HTTP/HTTPS");
        }
        if (i.deliverables() != null && (i.deliverables().size() > 10 || i.deliverables().stream().anyMatch(item ->
                Objects.toString(item.getOrDefault("name", item.get("description")), "").trim().isBlank()))) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "项目交付物不合法");
        }
        if (i.primaryDirectionId() != null) requireCatalogDirection(i.primaryDirectionId());
    }

    private void validateMilestone(LearningProjectEntity p, MilestoneInput i) {
        if (i.name() == null || i.name().isBlank() || i.name().length() > 120 || i.sequenceNo() < 1
            || i.dueDate() == null || i.dueDate().isBefore(p.getStartDate()) || i.dueDate().isAfter(p.getDueDate())
            || i.weight() == null || i.weight().signum() <= 0 || i.weight().compareTo(BigDecimal.ONE) > 0 || i.acceptanceCriteria() == null || i.acceptanceCriteria().isEmpty()
            || i.acceptanceCriteria().size() > 10 || i.acceptanceCriteria().stream().anyMatch(item ->
                Objects.toString(item.getOrDefault("description", item.get("name")), "").trim().isBlank()))
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "里程碑名称、日期、权重或验收清单不合法");
    }

    private void requireDraftProject(LearningProjectEntity project) {
        if (!ProjectStatus.DRAFT.name().equals(project.getStatus()))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "项目启动后不能修改目标关联或里程碑结构");
    }

    private LearningGoalEntity ownedGoal(String publicId) {
        LearningGoalEntity e = goalMapper.selectOne(new LambdaQueryWrapper<LearningGoalEntity>().eq(LearningGoalEntity::getPublicId, publicId)
            .eq(LearningGoalEntity::getUserId, SecurityUtils.currentUserId()));
        if (e == null) notFound();
        return e;
    }

    private LearningProjectEntity ownedProject(String publicId) {
        LearningProjectEntity e = projectMapper.selectOne(new LambdaQueryWrapper<LearningProjectEntity>().eq(LearningProjectEntity::getPublicId, publicId)
            .eq(LearningProjectEntity::getUserId, SecurityUtils.currentUserId()));
        if (e == null) notFound();
        return e;
    }

    private LearningProjectEntity ownedProjectForUpdate(String publicId) {
        LearningProjectEntity e = projectMapper.lockByPublicId(publicId);
        if (e == null || !Objects.equals(e.getUserId(), SecurityUtils.currentUserId())) notFound();
        return e;
    }

    private LearningProjectEntity ownedProjectForUpdateById(long id) {
        LearningProjectEntity e = projectMapper.lockById(id);
        if (e == null || !Objects.equals(e.getUserId(), SecurityUtils.currentUserId())) notFound();
        return e;
    }

    private LearningProjectEntity ownedProjectById(long id) {
        LearningProjectEntity e = projectMapper.selectOne(new LambdaQueryWrapper<LearningProjectEntity>().eq(LearningProjectEntity::getId, id)
            .eq(LearningProjectEntity::getUserId, SecurityUtils.currentUserId()));
        if (e == null) notFound();
        return e;
    }

    private MilestoneEntity ownedMilestone(String publicId) {
        MilestoneEntity m = milestoneMapper.selectOne(new LambdaQueryWrapper<MilestoneEntity>().eq(MilestoneEntity::getPublicId, publicId));
        if (m == null) notFound();
        ownedProjectById(m.getProjectId());
        return m;
    }

    private MilestoneEntity ownedMilestoneForUpdate(String publicId) {
        MilestoneEntity milestone = milestoneMapper.lockByPublicId(publicId);
        if (milestone == null) notFound();
        ownedProjectById(milestone.getProjectId());
        return milestone;
    }

    private void requireGoalDirection(GoalInput input,
                                      GoalRecommendationBatchStore.VerifiedRecommendation recommendation) {
        if (recommendation == null) {
            if (input.directionId() == null) {
                if (GoalRecommendationContext.normalize(input.customDirection()).isBlank()) {
                    throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                            "请选择目录方向或填写自定义学习方向");
                }
            } else requireCatalogDirection(input.directionId());
            return;
        }
        GoalRecommendationContext.Direction direction = recommendation.context()
                .requireDirection(input.directionId(), input.customDirection());
        if (direction.id() != null) requireCatalogDirection(direction.id());
    }

    private void requireGoalDirectionForUpdate(LearningGoalEntity existing, GoalInput input) {
        if (input.directionId() != null) requireCatalogDirection(input.directionId());
        else if (GoalRecommendationContext.normalize(input.customDirection()).isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "请选择目录方向或填写自定义学习方向");
        }
        // 来源链路不可由更新请求改写；推荐目标编辑仍按正式字段规则重新校验。
        if (input.sourceType() != null && !input.sourceType().equals(existing.getSourceType())) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "目标来源不可修改");
        }
    }

    private void requireCatalogDirection(long id) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM learning_direction WHERE id=? AND status='ACTIVE' AND deleted_at IS NULL",
                Integer.class, id);
        if (n == null || n == 0)
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "目标方向必须是有效学习目录");
    }

    private void apply(LearningGoalEntity e, GoalInput i) {
        e.setDirectionId(i.directionId());
        e.setCustomDirection(i.directionId() == null ? i.customDirection().trim() : null);
        e.setName(i.name().trim());
        e.setType(i.type());
        e.setDescription(i.description());
        e.setPriority(i.priority());
        e.setStartDate(i.startDate());
        e.setDueDate(i.dueDate());
        e.setWeeklyBudgetMinutes(i.weeklyBudgetMinutes());
        e.setSuccessCriteriaJson(json(i.successCriteria()));
    }

    private void applySource(LearningGoalEntity goal, GoalInput input,
                             GoalRecommendationBatchStore.VerifiedRecommendation recommendation) {
        String sourceType = sourceType(input);
        if (!Set.of("CUSTOM", "AI_RECOMMENDED", "RULE_RECOMMENDED").contains(sourceType)) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "目标来源不合法");
        }
        if ("CUSTOM".equals(sourceType) && (input.profileVersionId() != null
                || input.recommendationId() != null && !input.recommendationId().isBlank())) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "自定义目标不能伪造推荐来源");
        }
        goal.setSourceType(sourceType);
        goal.setProfileVersionId(recommendation == null ? null : recommendation.profileVersionId());
        if (recommendation == null) {
            goal.setRecommendationSnapshotJson(null);
        } else {
            goal.setRecommendationSnapshotJson(json(Map.of(
                    "recommendationId", recommendation.candidate().id(),
                    "source", recommendation.candidate().source(),
                    "profileVersionNo", recommendation.profileVersionNo(),
                    "originalCandidate", recommendation.candidate(),
                    "acceptedGoal", Map.of(
                            "directionId", input.directionId() == null ? "" : String.valueOf(input.directionId()),
                            "customDirection", Objects.toString(input.customDirection(), ""),
                            "name", input.name(), "type", input.type(), "priority", Objects.toString(input.priority(), ""),
                            "startDate", input.startDate(), "dueDate", input.dueDate(),
                            "weeklyBudgetMinutes", input.weeklyBudgetMinutes(),
                            "successCriteria", input.successCriteria()),
                    "confirmedAt", Instant.now())));
        }
    }

    private String sourceType(GoalInput input) {
        return input.sourceType() == null || input.sourceType().isBlank() ? "CUSTOM" : input.sourceType();
    }

    private void apply(LearningProjectEntity e, ProjectInput i) {
        e.setPrimaryDirectionId(i.primaryDirectionId());
        e.setName(i.name().trim());
        e.setDescription(i.description());
        e.setStartDate(i.startDate());
        e.setDueDate(i.dueDate());
        e.setPriority(i.priority());
        e.setDeliverableJson(json(i.deliverables() == null ? List.of() : i.deliverables()));
        e.setRepositoryUrl(i.repositoryUrl());
    }

    private void apply(MilestoneEntity e, MilestoneInput i) {
        e.setName(i.name());
        e.setSequenceNo(i.sequenceNo());
        e.setDueDate(i.dueDate());
        e.setWeight(i.weight());
        e.setAcceptanceJson(json(i.acceptanceCriteria()));
    }

    private boolean criteriaPresent(String json) {
        return readJson(json).isArray() && readJson(json).size() > 0;
    }

    private boolean criteriaComplete(String json) {
        JsonNode n = readJson(json);
        if (!n.isArray() || n.isEmpty()) return false;
        for (JsonNode x : n) if (!x.path("completed").asBoolean(false)) return false;
        return true;
    }

    private Map<String, Object> verifiedMilestoneEvidence(MilestoneEntity milestone,
                                                           MilestoneCompletionInput input) {
        if (input == null || input.summary() == null || input.summary().isBlank()
                || input.criteria() == null) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "必须逐项确认数据库中的里程碑验收条件");
        }
        JsonNode acceptance = readJson(milestone.getAcceptanceJson());
        if (!acceptance.isArray() || acceptance.isEmpty() || input.criteria().size() != acceptance.size()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "里程碑验收条件存在缺项");
        }
        Map<Integer, Map<String, Object>> submitted = new HashMap<>();
        for (Map<String, Object> item : input.criteria()) {
            Object rawIndex = item.get("index");
            if (!(rawIndex instanceof Number number)
                    || submitted.put(number.intValue(), item) != null) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "里程碑验收条件序号无效");
            }
        }
        List<Map<String, Object>> verified = new ArrayList<>();
        for (int index = 0; index < acceptance.size(); index++) {
            Map<String, Object> confirmation = submitted.get(index);
            if (confirmation == null || !Boolean.TRUE.equals(confirmation.get("confirmed"))) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "必须逐项确认数据库中的里程碑验收条件");
            }
            JsonNode required = acceptance.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", index);
            item.put("description", required.path("description").asText(required.path("name").asText("验收条件")));
            item.put("confirmed", true);
            String evidence = Objects.toString(confirmation.get("evidence"), "").trim();
            item.put("evidence", evidence.isBlank() ? input.summary().trim() : evidence);
            verified.add(item);
        }
        if (submitted.size() != acceptance.size()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "里程碑验收条件包含伪造项");
        }
        return Map.of("summary", input.summary().trim(), "criteria", verified, "acceptedAt", Instant.now());
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object v) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void validateVersion(Integer current, Integer requested) {
        if (requested == null || !requested.equals(current)) conflict();
    }

    private void lockUser(long userId) {
        if (userMapper.lockById(userId) == null) notFound();
    }

    private void conflict() {
        throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "资源版本冲突，请刷新后重试");
    }

    private void notFound() {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资源不存在");
    }
}
