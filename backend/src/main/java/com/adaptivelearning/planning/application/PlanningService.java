package com.adaptivelearning.planning.application;

import com.adaptivelearning.execution.domain.LearningTaskEntity;
import com.adaptivelearning.execution.application.TaskCancellationService;
import com.adaptivelearning.execution.infrastructure.LearningTaskMapper;
import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
import com.adaptivelearning.goalproject.domain.DependencyGraphPolicy;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.GoalMapper;
import com.adaptivelearning.planning.domain.*;
import com.adaptivelearning.planning.infrastructure.PlanningMappers.*;
import com.adaptivelearning.shared.ai.AiModelException;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.shared.web.RequestIdFilter;
import com.adaptivelearning.support.application.AuditService;
import com.adaptivelearning.support.application.HashingService;
import com.adaptivelearning.support.infrastructure.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
public class PlanningService {
    private final GoalMapper goalMapper;
    private final PlanMapper planMapper;
    private final PlanVersionMapper versionMapper;
    private final PlanStageMapper stageMapper;
    private final PlanChangeMapper changeMapper;
    private final PlanValidationMapper validationMapper;
    private final PlanConfirmationMapper confirmationMapper;
    private final PlanningJobMapper jobMapper;
    private final PublicationMapper publicationMapper;
    private final OutboxMapper outboxMapper;
    private final LearningTaskMapper taskMapper;
    private final IdempotencyService idempotency;
    private final RuleBasedPlanner ruleBasedPlanner;
    private final PythonAiServiceClient pythonAi;
    private final HashingService hashing;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final PlatformTransactionManager transactionManager;
    private final UserMapper userMapper;
    private final TaskCancellationService taskCancellation;
    private final KnowledgePrerequisitePolicy prerequisitePolicy = new KnowledgePrerequisitePolicy();
    private final PlanCandidatePolicy candidatePolicy = new PlanCandidatePolicy();
    private final SecureRandom random = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);
    /** 规划作业异步执行器：提交即返回 QUEUED 作业，模型生成在后台线程执行，前端轮询结果。 */
    @Autowired @Qualifier("planningJobExecutor")
    private Executor planningJobExecutor;

    @Value("${app.planning.confirmation-minutes:30}") private long confirmationMinutes;
    @Value("${app.planning.auto-optimization-cooldown:PT24H}") private Duration autoOptimizationCooldown;

    public record JobRequest(String type,String projectId,String userRequirement,List<String> knowledgeSpaceIds){}
    public record JobView(String publicId,String jobType,String status,String planId,String planVersionId,
                          String errorCode,String errorMessage,Instant startedAt,Instant finishedAt){}
    public record ConfirmationToken(String token,Instant expiresAt,String proposalHash){}
    public record PublicationResult(String planId,String versionId,int versionNo,List<String> changedTaskIds,String status){}
    public record VersionDetail(PlanVersionEntity version,List<PlanStageEntity> stages,
                                List<PlanChangeItemEntity> changes,List<PlanValidationResultEntity> validation){}
    public record PlanDetail(LearningPlanEntity plan,PlanVersionEntity currentVersion){}
    public record EffectivePlanView(PlanVersionEntity version,List<PlanStageEntity> stages,Instant publishedAt){}
    private record KnowledgePlanContext(List<Long> spaceIds,List<Long> documentVersionIds,
                                        List<String> publicSpaceIds,String fingerprint,
                                        List<Map<String,Object>> documents){}
    private record KnowledgePrerequisiteContext(
            List<KnowledgePrerequisitePolicy.Dependency> dependencies,
            Set<Long> satisfiedPrerequisiteIds) { }
    private record ProfilePlanningContext(long profileId,String status,long schedulingVersionId,
                                          int schedulingVersionNo,String snapshotJson,String snapshotHash,
                                          Map<String,Object> snapshot,List<RuleBasedPlanner.Slot> slots,
                                          List<RuleBasedPlanner.DayException> exceptions,BigDecimal capacityRatio,
                                          int weekStart,LocalDate planStartDate,LocalDate planEndDate,
                                          int dailyRecommendedTasks,int focusMinutes,
                                          Map<String,Object> learningSignals,String planningFingerprint) { }
    private record SemanticProfileContext(Long versionId,Integer versionNo,String source,
                                          String snapshotHash,Map<String,Object> snapshot) { }
    private record MilestonePlanningContext(Long id,String publicId,Integer sequenceNo,String name,
                                             LocalDate dueDate,String weight,
                                             List<PythonAiServiceClient.PlanCriterion> criteria) { }
    private record ProjectPlanningContext(Long id,String publicId,String status,Integer version,String fingerprint,
                                           String name,String description,String priority,LocalDate startDate,
                                           LocalDate dueDate,Object deliverables,String repositoryUrl,
                                           String contributionWeight,List<MilestonePlanningContext> milestones) { }
    /**
     * 异步提交优化作业：提交后立即返回 QUEUED 作业，模型生成在后台线程执行，
     * 成功或失败都会保留在 optimization_request 中；前端轮询 GET /planning-jobs/{jobId} 获取结果。
     */
    public PlanningJobEntity submitOptimization(String goalPublicId, JobRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "缺少 Idempotency-Key");
        long userId = SecurityUtils.currentUserId();
        LearningGoalEntity goal = ownedGoal(goalPublicId);
        String trigger = idempotencyKey.length() > 120 ? hashing.sha256(idempotencyKey) : idempotencyKey;
        Map<String, Object> existing = jdbc.query("""
                SELECT plan_version_id,status FROM optimization_request
                WHERE user_id=? AND trigger_event_id=? AND type='USER_REQUEST'
                """, rs -> {
            if (!rs.next()) return null;
            Map<String, Object> value = new HashMap<>();
            value.put("planVersionId", rs.getObject(1));
            value.put("status", rs.getString(2));
            return value;
        }, userId, trigger);
        if (existing != null && existing.get("planVersionId") != null) {
            PlanningJobEntity old = jobMapper.selectOne(new LambdaQueryWrapper<PlanningJobEntity>()
                    .eq(PlanningJobEntity::getUserId, userId)
                    .eq(PlanningJobEntity::getPlanVersionId, ((Number) existing.get("planVersionId")).longValue())
                    .orderByDesc(PlanningJobEntity::getStartedAt)
                    .last("LIMIT 1"));
            if (old != null) return old;
        }
        if (existing == null) {
            jdbc.update("""
                    INSERT INTO optimization_request
                    (id,public_id,user_id,goal_id,trigger_event_id,type,evidence_json,status,cooldown_until,created_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, IdWorker.getId(), UUID.randomUUID().toString(), userId, goal.getId(), trigger,
                    "USER_REQUEST", json(Map.of(
                            "userRequirement", request.userRequirement() == null ? "" : request.userRequirement(),
                            "projectId", request.projectId() == null ? "" : request.projectId(),
                            "knowledgeSpaceIds", request.knowledgeSpaceIds() == null ? List.of() : request.knowledgeSpaceIds())),
                    "RUNNING", Instant.now().plus(Duration.ofMinutes(5)), Instant.now());
        }
        JobRequest generationRequest = new JobRequest("OPTIMIZATION", request.projectId(), request.userRequirement(),
                request.knowledgeSpaceIds());
        try {
            PreparedJob prepared = prepareJob(goal, generationRequest, idempotencyKey, userId);
            if (prepared.fresh()) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                String finalTrigger = trigger;
                String auditRequestId = RequestIdFilter.currentRequestId();
                String auditIp = audit.currentClientIp();
                planningJobExecutor.execute(() -> {
                    try {
                        executePlanningJob(prepared.job(), generationRequest, auth, auditRequestId, auditIp);
                        jdbc.update("""
                                UPDATE optimization_request
                                SET status='SUCCEEDED',plan_version_id=?
                                WHERE user_id=? AND trigger_event_id=? AND type='USER_REQUEST'
                                """, prepared.job().getPlanVersionId(), userId, finalTrigger);
                    } catch (RuntimeException exception) {
                        log.error("优化作业 {} 失败", prepared.job().getPublicId(), exception);
                        jdbc.update("""
                                UPDATE optimization_request
                                SET status='FAILED',
                                    evidence_json=JSON_SET(evidence_json,'$.errorType',?,'$.failedAt',?)
                                WHERE user_id=? AND trigger_event_id=? AND type='USER_REQUEST'
                                """, exception.getClass().getSimpleName(), Instant.now().toString(), userId, finalTrigger);
                    }
                });
            }
            return prepared.job();
        } catch (RuntimeException exception) {
            jdbc.update("""
                    UPDATE optimization_request
                    SET status='FAILED',
                        evidence_json=JSON_SET(evidence_json,'$.errorType',?,'$.failedAt',?)
                    WHERE user_id=? AND trigger_event_id=? AND type='USER_REQUEST'
                    """, exception.getClass().getSimpleName(), Instant.now().toString(), userId, trigger);
            throw exception;
        }
    }

    /**
     * 异步提交规划作业：校验后立即返回 QUEUED 作业，模型生成在后台线程执行，
     * 前端轮询 GET /planning-jobs/{jobId} 获取结果。作业失败会持久化为 FAILED，可查询可重试。
     */
    public PlanningJobEntity submitPlanningJob(String goalPublicId, JobRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "缺少 Idempotency-Key");
        long userId = SecurityUtils.currentUserId();
        LearningGoalEntity goal = ownedGoal(goalPublicId);
        PreparedJob prepared = prepareJob(goal, request, idempotencyKey, userId);
        if (prepared.fresh()) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            // 审计字段必须在请求线程内取好：后台线程访问 HttpServletRequest 会抛 No thread-bound request
            String auditRequestId = RequestIdFilter.currentRequestId();
            String auditIp = audit.currentClientIp();
            try {
                planningJobExecutor.execute(() -> {
                    try {
                        executePlanningJob(prepared.job(), request, auth, auditRequestId, auditIp);
                    } catch (RuntimeException failure) {
                        log.error("规划作业 {} 生成失败", prepared.job().getPublicId(), failure);
                    }
                });
            } catch (RuntimeException rejected) {
                markJobFailed(prepared.job(), rejected);
                throw rejected;
            }
        }
        return prepared.job();
    }

    /**
     * 学习反馈只生成待审阅优化提案，不直接修改正式任务；同一目标受冷却时间保护。
     */
    public void submitAutomaticOptimization(long userId,long goalId,String triggerEventId){
        LearningGoalEntity goal=goalMapper.selectById(goalId);
        if(goal==null||!Objects.equals(goal.getUserId(),userId)||!"ACTIVE".equals(goal.getStatus()))return;
        Long published=jdbc.queryForObject("SELECT COUNT(*) FROM learning_plan p JOIN plan_publication pp ON pp.plan_id=p.id WHERE p.goal_id=? AND p.user_id=?",Long.class,goalId,userId);
        if(published==null||published==0)return;
        Long recent=jdbc.queryForObject("SELECT COUNT(*) FROM optimization_request WHERE user_id=? AND goal_id=? AND type='PERFORMANCE_AUTO' AND created_at>=?",Long.class,userId,goalId,Instant.now().minus(autoOptimizationCooldown));
        if(recent!=null&&recent>0)return;
        String trigger=triggerEventId==null?UUID.randomUUID().toString():triggerEventId;
        jdbc.update("""
                INSERT INTO optimization_request
                (id,public_id,user_id,goal_id,trigger_event_id,type,evidence_json,status,cooldown_until,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,IdWorker.getId(),UUID.randomUUID().toString(),userId,goalId,trigger,"PERFORMANCE_AUTO",
                json(Map.of("reason","掌握度或近期执行表现发生变化","requiresUserConfirmation",true)),
                "RUNNING",Instant.now().plus(autoOptimizationCooldown),Instant.now());
        JobRequest request=new JobRequest("OPTIMIZATION",null,
                "根据最新掌握度、逾期与完成趋势生成最小影响的优化建议",List.of());
        PreparedJob prepared;
        try{
            prepared=prepareJob(goal,request,"auto-"+trigger,userId);
        }catch(RuntimeException failure){
            jdbc.update("UPDATE optimization_request SET status='FAILED' WHERE user_id=? AND trigger_event_id=? AND type='PERFORMANCE_AUTO'",userId,trigger);
            throw failure;
        }
        if(!prepared.fresh())return;
        try{
            planningJobExecutor.execute(()->{
                try{
                    executePlanningJob(prepared.job(),request,null,"auto-"+trigger,"system");
                    jdbc.update("UPDATE optimization_request SET status='SUCCEEDED',plan_version_id=? WHERE user_id=? AND trigger_event_id=? AND type='PERFORMANCE_AUTO'",prepared.job().getPlanVersionId(),userId,trigger);
                }catch(RuntimeException failure){
                    jdbc.update("UPDATE optimization_request SET status='FAILED' WHERE user_id=? AND trigger_event_id=? AND type='PERFORMANCE_AUTO'",userId,trigger);
                }
            });
        }catch(RuntimeException rejected){
            markJobFailed(prepared.job(),rejected);
            jdbc.update("UPDATE optimization_request SET status='FAILED' WHERE user_id=? AND trigger_event_id=? AND type='PERFORMANCE_AUTO'",userId,trigger);
            throw rejected;
        }
    }

    /**
     * 同步核心（测试与兼容入口）：与异步路径共用 prepareJob/runGeneration，执行完才返回。
     */
    public PlanningJobEntity createJob(String goalPublicId, JobRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "缺少 Idempotency-Key");
        long userId = SecurityUtils.currentUserId();
        LearningGoalEntity goal = ownedGoal(goalPublicId);
        PreparedJob prepared = prepareJob(goal, request, idempotencyKey, userId, "RUNNING");
        if (prepared.fresh())
            runGeneration(prepared.job(), request, userId, RequestIdFilter.currentRequestId(), audit.currentClientIp());
        return prepared.job();
    }

    private record PreparedJob(PlanningJobEntity job, boolean fresh) {}

    /** 幂等 + 状态校验 + 过期清理 + 并发保护，然后创建作业行；已存在同键作业时原样返回。 */
    private PreparedJob prepareJob(LearningGoalEntity goal, JobRequest request, String idempotencyKey,
                                   long userId, String initialStatus) {
        String requestJson = json(request);
        PlanningJobEntity existing = jobMapper.selectOne(new LambdaQueryWrapper<PlanningJobEntity>()
                .eq(PlanningJobEntity::getUserId, userId).eq(PlanningJobEntity::getIdempotencyKey, idempotencyKey));
        if (existing != null) {
            if (!existing.getRequestHash().equals(hashing.sha256(requestJson)))
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "同一幂等键不能用于不同规划请求");
            return new PreparedJob(existing, false);
        }
        if (!"ACTIVE".equals(goal.getStatus()))
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "只有活动目标可以发起规划");
        profileContext(userId);
        resolveProject(request.projectId(), goal.getId(), true);
        expireStaleRunningJobs(goal.getId(), userId);
        long running = jobMapper.selectCount(new LambdaQueryWrapper<PlanningJobEntity>()
                .eq(PlanningJobEntity::getGoalId, goal.getId())
                .in(PlanningJobEntity::getStatus, "QUEUED", "RUNNING"));
        if (running > 0) throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "该目标已有运行中的规划作业");
        PlanningJobEntity job = new PlanningJobEntity();
        job.setPublicId(UUID.randomUUID().toString()); job.setUserId(userId); job.setGoalId(goal.getId());
        job.setJobType(request.type() == null ? "INITIAL" : request.type()); job.setStatus(initialStatus);
        job.setIdempotencyKey(idempotencyKey); job.setRequestHash(hashing.sha256(requestJson));
        job.setStartedAt(Instant.now()); jobMapper.insert(job);
        return new PreparedJob(job, true);
    }

    private PreparedJob prepareJob(LearningGoalEntity goal, JobRequest request, String idempotencyKey, long userId) {
        return prepareJob(goal, request, idempotencyKey, userId, "QUEUED");
    }

    /** 后台执行体：恢复登录身份，先置 RUNNING，成功/失败由 runGeneration 落库后继续上抛。 */
    private void executePlanningJob(PlanningJobEntity job, JobRequest request, Authentication auth,
                                    String auditRequestId, String auditIp) {
        SecurityContextHolder.getContext().setAuthentication(auth);
        job.setStatus("RUNNING");
        jobMapper.updateById(job);
        try {
            runGeneration(job, request, job.getUserId(), auditRequestId, auditIp);
        } catch (RuntimeException failure) {
            // runGeneration 已把作业标记为 FAILED；继续上抛让外层（如优化请求）同步收尾
            throw failure;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 生成执行核心：读取上下文与调用模型不持事务；写入（计划行、版本、阶段、变更、校验、
     * 作业成功）在单个事务内一次性提交。任何失败都把作业标记为 FAILED 后重新抛出。
     */
    private void runGeneration(PlanningJobEntity job, JobRequest request, long userId,
                               String auditRequestId, String auditIp) {
        try {
            ProfilePlanningContext profile=profileContext(userId);
            List<RuleBasedPlanner.Slot> slots=profile.slots();
            List<RuleBasedPlanner.DayException> exceptions=profile.exceptions();
            BigDecimal capacityRatio=profile.capacityRatio();
            if(slots.isEmpty())throw new BusinessException(ErrorCode.PLAN_CAPACITY_EXCEEDED,
                    "正式画像没有可定位的学习时段，无法生成排期");
            LearningGoalEntity goal=goalMapper.selectById(job.getGoalId());
            if(goal==null)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"目标不存在或已删除");
            if(!"ACTIVE".equals(goal.getStatus()))throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"只有活动目标可以生成计划");
            ProjectPlanningContext project=projectContext(request.projectId(),goal.getId(),true);
            Long projectId=project==null?null:project.id();
            SemanticProfileContext semanticProfile=semanticProfileContext(goal,profile,userId);
            List<Map<String,Object>> knowledge=goal.getDirectionId()==null?List.of():jdbc.query(
                    "SELECT id,name FROM knowledge_point WHERE direction_id=? AND status='ACTIVE' AND deleted_at IS NULL ORDER BY level,id",
                    (rs,row)->Map.of("id",rs.getLong(1),"name",rs.getString(2)),goal.getDirectionId());
            ZoneId zone=ZoneId.of(String.valueOf(profile.snapshot().get("timezone")));
            if(!pythonAi.isConfigured())throw new AiModelException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE);
            String directionName=goal.getDirectionId()==null?goal.getCustomDirection():jdbc.query(
                    "SELECT name FROM learning_direction WHERE id=? AND status='ACTIVE'",
                    rs->rs.next()?rs.getString(1):"当前学习方向",goal.getDirectionId());
            String currentStage=profileDirectionStage(semanticProfile.snapshot(),goal);
            int weeklyMinutes=BigDecimal.valueOf(slots.stream().mapToInt(RuleBasedPlanner.Slot::minutes).sum())
                    .multiply(capacityRatio).intValue();
            int taskCount=Math.max(2,Math.min(10,knowledge.isEmpty()?6:Math.min(knowledge.size()*2,10)));
            List<PythonAiServiceClient.PlanCriterion> goalCriteria=criteria(goal.getSuccessCriteriaJson(),"GC");
            PythonAiServiceClient.PlanProject planProject=project==null?null:new PythonAiServiceClient.PlanProject(
                    project.id(),project.publicId(),project.name(),project.description(),project.priority(),
                    project.startDate(),project.dueDate(),project.deliverables(),project.repositoryUrl(),
                    project.contributionWeight(),project.milestones().stream().map(milestone->
                    new PythonAiServiceClient.PlanMilestone(milestone.id(),milestone.publicId(),milestone.sequenceNo(),
                            milestone.name(),milestone.dueDate(),milestone.weight(),milestone.criteria())).toList());
            KnowledgePlanContext knowledgeContext=knowledgePlanContext(request.knowledgeSpaceIds(),userId);
            List<PythonAiServiceClient.PlanKnowledgePoint> planKnowledgePoints=knowledge.stream()
                    .map(k->new PythonAiServiceClient.PlanKnowledgePoint(
                            ((Number)k.get("id")).longValue(),String.valueOf(k.get("name"))))
                    .toList();
            Set<Long> allowedKnowledgePointIds=planKnowledgePoints.stream()
                    .map(PythonAiServiceClient.PlanKnowledgePoint::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            KnowledgePrerequisiteContext prerequisiteContext=knowledgePrerequisiteContext(
                    goal.getDirectionId(),userId,allowedKnowledgePointIds);
            PythonAiServiceClient.PlanRecommendationResult planResult=pythonAi.planRecommendations(
                    new PythonAiServiceClient.PlanRecommendationRequest(userId,goal.getName(),directionName,currentStage,
                            maxDate(LocalDate.now(zone),profile.planStartDate(),goal.getStartDate(),project==null?null:project.startDate()),
                            minDate(profile.planEndDate(),goal.getDueDate(),project==null?null:project.dueDate()),
                            planningBackground(profile),goal.getDescription(),goal.getType(),goal.getPriority(),
                            goal.getDirectionId(),goal.getCustomDirection(),goal.getStartDate(),goal.getDueDate(),
                            Objects.requireNonNullElse(goal.getWeeklyBudgetMinutes(),weeklyMinutes),goalCriteria,
                            semanticProfile.versionId(),profile.schedulingVersionId(),planProject,
                            planKnowledgePoints,
                            prerequisiteContext.dependencies().stream()
                                    .map(edge->new PythonAiServiceClient.KnowledgeDependency(
                                            edge.predecessorId(),edge.successorId())).toList(),
                            prerequisiteContext.satisfiedPrerequisiteIds().stream().sorted().toList(),
                            knowledgeContext.spaceIds(),knowledgeContext.documentVersionIds(),12,
                            request.userRequirement(),weeklyMinutes,profile.dailyRecommendedTasks(),profile.focusMinutes(),
                            goal.getDirectionId()==null,taskCount));
            Map<Long,RuleBasedPlanner.TaskSource> planSources=resolvePlanSources(
                    planResult,knowledgeContext,userId);
            KnowledgePrerequisitePolicy.Result<PythonAiServiceClient.PlanTaskItem> normalizedPlan=
                    prerequisitePolicy.normalize(
                            planResult.tasks().stream()
                                    .map(task->new KnowledgePrerequisitePolicy.TaskKnowledge<>(
                                            task,task.knowledgePointIds())).toList(),
                            allowedKnowledgePointIds,prerequisiteContext.dependencies(),
                            prerequisiteContext.satisfiedPrerequisiteIds());
            Map<Long,PlanCandidatePolicy.Milestone> milestonePolicyContext=project==null?Map.of():project.milestones().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(MilestonePlanningContext::id,milestone->
                            new PlanCandidatePolicy.Milestone(milestone.id(),milestone.publicId(),milestone.dueDate(),
                                    milestone.criteria().stream().map(PythonAiServiceClient.PlanCriterion::criterionId)
                                            .collect(java.util.stream.Collectors.toUnmodifiableSet()))));
            candidatePolicy.validateCandidates(
                    normalizedPlan.tasks().stream().map(KnowledgePrerequisitePolicy.TaskKnowledge::value).map(task->
                            new PlanCandidatePolicy.Candidate(task.clientRef(),task.title(),task.taskType(),task.priority(),
                                    task.estimatedMinutes(),task.knowledgePointIds(),task.sourceChunkIds(),
                                    task.learningObjective(),task.acceptanceCriteria(),task.milestoneId(),
                                    task.coveredGoalCriterionIds(),task.coveredMilestoneCriterionIds())).toList(),
                    new PlanCandidatePolicy.Context(goal.getDirectionId()==null,allowedKnowledgePointIds,
                            planSources.keySet(),goalCriteria.stream().map(PythonAiServiceClient.PlanCriterion::criterionId)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),milestonePolicyContext));
            LocalDate earliest=maxDate(LocalDate.now(zone),profile.planStartDate(),goal.getStartDate(),project==null?null:project.startDate());
            LocalDate latest=minDate(profile.planEndDate(),goal.getDueDate(),project==null?null:project.dueDate());
            List<RuleBasedPlanner.TaskContent> contents=normalizedPlan.tasks().stream()
                    .map(KnowledgePrerequisitePolicy.TaskKnowledge::value)
                    .map(t->new RuleBasedPlanner.TaskContent(
                    t.title(),t.taskType(),t.priority(),t.estimatedMinutes(),t.knowledgePointIds(),
                    t.sourceChunkIds().stream().map(planSources::get).filter(Objects::nonNull).toList(),
                    t.learningObjective(),t.sourceQueries(),goal.getDirectionId()==null,
                    t.acceptanceCriteria(),t.reason(),t.clientRef(),t.milestoneId(),t.coveredGoalCriterionIds(),
                    t.coveredMilestoneCriterionIds(),t.milestoneId()==null?latest:
                    milestonePolicyContext.get(t.milestoneId()).dueDate())).toList();
            boolean optimization="OPTIMIZATION".equals(job.getJobType());
            List<RuleBasedPlanner.OccupiedTask> occupied=schedulingOccupiedTasks(userId,goal.getId(),projectId,optimization,zone);
            List<RuleBasedPlanner.TaskDraft> drafts=ruleBasedPlanner.schedule(contents,earliest,
                    latest,zone,slots,exceptions,capacityRatio,occupied,profile.dailyRecommendedTasks(),profile.focusMinutes());
            if(drafts.isEmpty())throw new BusinessException(ErrorCode.PLAN_CAPACITY_EXCEEDED,"目标周期太短，无法容纳任何候选任务");

            List<LearningTaskEntity> currentTasks=optimization?scopedTasks(goal.getId(),projectId,false):List.of();
            List<Map<String,Object>> afterList=new ArrayList<>();
            List<String> actions=new ArrayList<>();
            List<Long> targets=new ArrayList<>();
            List<String> reasons=new ArrayList<>();
            for(int i=0;i<drafts.size();i++){
                RuleBasedPlanner.TaskDraft d=drafts.get(i);afterList.add(taskAfter(d,goal,projectId,i+1));
                actions.add(i<currentTasks.size()?"RESCHEDULE_TASK":"ADD_TASK");
                targets.add(i<currentTasks.size()?currentTasks.get(i).getId():null);
                reasons.add(d.reason());
            }
            for(KnowledgePrerequisitePolicy.TaskDependency edge:normalizedPlan.taskDependencies()){
                String predecessor=normalizedPlan.tasks().get(edge.predecessorTaskIndex()).value().clientRef();
                String successor=normalizedPlan.tasks().get(edge.successorTaskIndex()).value().clientRef();
                afterList.stream().filter(item->Objects.equals(item.get("clientRef"),successor)).findFirst()
                        .ifPresent(item->item.put("dependencyTaskIds",List.of(predecessor)));
            }
            for(int i=drafts.size();i<currentTasks.size();i++){
                LearningTaskEntity task=currentTasks.get(i);afterList.add(taskSnapshot(task,zone));
                actions.add("CANCEL_TASK");targets.add(task.getId());
                reasons.add("优化后的学习路径不再需要该任务");
            }
            String proposalHash=hashing.sha256(json(afterList));
            Integer generationBaseVersion=currentPublishedVersionNo(goal.getId(),projectId);
            String generationTaskFingerprint=scopedTaskFingerprint(goal.getId(),projectId);
            String catalogFingerprint=catalogFingerprint(knowledge,prerequisiteContext);
            // 写事务：计划行、版本、阶段、变更、校验、作业成功一次性提交，任一步失败整体回滚
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                lockProfileForPublication(userId);
                ProfilePlanningContext lockedProfile=profileContext(userId);
                if(!Objects.equals(profile.schedulingVersionId(),lockedProfile.schedulingVersionId())
                        ||!Objects.equals(profile.snapshotHash(),lockedProfile.snapshotHash())
                        ||!Objects.equals(profile.planningFingerprint(),lockedProfile.planningFingerprint()))
                    throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"画像在计划生成期间已发生变化");
                LearningGoalEntity lockedGoal=goalMapper.lockById(goal.getId());
                if(lockedGoal==null||!"ACTIVE".equals(lockedGoal.getStatus())
                        ||PlanValidationPolicy.goalContextStale(PlanValidationPolicy.goalFingerprint(goal),lockedGoal,
                        goal.getVersion(),lockedGoal.getVersion()))
                    throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"目标在计划生成期间已发生变化");
                ProjectPlanningContext lockedProject=lockProjectForProposal(projectId,userId);
                if(!Objects.equals(project==null?null:project.fingerprint(),lockedProject==null?null:lockedProject.fingerprint()))
                    throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"项目在计划生成期间已发生变化");
                lockScopedTasks(goal.getId(),projectId);
                LearningPlanEntity plan=findOrCreatePlan(lockedGoal,projectId);
                Integer currentNo=jdbc.query("SELECT pv.version_no FROM plan_publication pp JOIN plan_version pv ON pv.id=pp.plan_version_id WHERE pp.plan_id=?",
                        rs->rs.next()?rs.getInt(1):null,plan.getId());
                Integer maxNo=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0) FROM plan_version WHERE plan_id=?",Integer.class,plan.getId());
                PlanVersionEntity version=new PlanVersionEntity();version.setPublicId(UUID.randomUUID().toString());version.setPlanId(plan.getId());
                if(!Objects.equals(generationBaseVersion,currentNo)
                        ||!Objects.equals(generationTaskFingerprint,scopedTaskFingerprint(goal.getId(),projectId)))
                    throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"正式计划在生成期间已发生变化");
                version.setVersionNo((maxNo==null?0:maxNo)+1);version.setBaseVersionNo(currentNo);version.setStatus("VALIDATING");
                version.setTriggerType(job.getJobType());version.setTriggerEventId(job.getPublicId());version.setProposalHash(proposalHash);
                version.setRiskLevel("MEDIUM");
                Map<String,Object> contextSnapshot=new LinkedHashMap<>();
                contextSnapshot.put("userId",String.valueOf(userId));contextSnapshot.put("goalId",String.valueOf(goal.getId()));
                contextSnapshot.put("projectId",projectId==null?null:String.valueOf(projectId));
                contextSnapshot.put("goalVersion",goal.getVersion());
                contextSnapshot.put("goalFingerprint",PlanValidationPolicy.goalFingerprint(goal));
                contextSnapshot.put("basePlanVersion",currentNo);
                contextSnapshot.put("baseTaskFingerprint",generationTaskFingerprint);
                contextSnapshot.put("goalProfileVersionId",semanticProfile.versionId()==null?null:String.valueOf(semanticProfile.versionId()));
                contextSnapshot.put("goalProfileVersionNo",semanticProfile.versionNo());
                contextSnapshot.put("semanticProfileSource",semanticProfile.source());
                contextSnapshot.put("goalProfileSnapshotHash",semanticProfile.snapshotHash());
                contextSnapshot.put("schedulingProfileVersionId",String.valueOf(profile.schedulingVersionId()));
                contextSnapshot.put("schedulingProfileVersionNo",profile.schedulingVersionNo());
                contextSnapshot.put("schedulingProfileSnapshotHash",profile.snapshotHash());
                contextSnapshot.put("planningFingerprint",profile.planningFingerprint());contextSnapshot.put("timezone",zone.getId());
                contextSnapshot.put("weekStart",profile.weekStart());
                contextSnapshot.put("planStartDate",profile.planStartDate());contextSnapshot.put("planEndDate",profile.planEndDate());
                contextSnapshot.put("dailyRecommendedTasks",profile.dailyRecommendedTasks());contextSnapshot.put("focusMinutes",profile.focusMinutes());
                contextSnapshot.put("projectVersion",project==null?null:project.version());
                contextSnapshot.put("projectFingerprint",project==null?null:project.fingerprint());
                contextSnapshot.put("catalogFingerprint",catalogFingerprint);
                contextSnapshot.put("generatedAt",Instant.now());contextSnapshot.put("userRequirement",request.userRequirement()==null?"":request.userRequirement());
                contextSnapshot.put("knowledgeSpaceIds",knowledgeContext.publicSpaceIds());
                contextSnapshot.put("knowledgeFingerprint",knowledgeContext.fingerprint());
                contextSnapshot.put("knowledgeDocuments",knowledgeContext.documents().stream().map(this::browserIdMap).toList());
                contextSnapshot.put("explorationMode",goal.getDirectionId()==null);
                contextSnapshot.put("planningContextFingerprint",PlanningContextPolicy.fingerprint(objectMapper,contextSnapshot));
                version.setContextSnapshotJson(json(contextSnapshot));
                version.setSummaryJson(json(summary(afterList,actions)));versionMapper.insert(version);
                boolean explorationMode=goal.getDirectionId()==null;
                PlanStageEntity stage=new PlanStageEntity();stage.setPlanVersionId(version.getId());stage.setClientRef("stage-1");
                stage.setName(explorationMode?"探索、资料核验与分块学习阶段":"知识块学习与测试阶段");
                stage.setSequenceNo(1);stage.setStartDate(drafts.get(0).start().toLocalDate());stage.setEndDate(drafts.get(drafts.size()-1).due().toLocalDate());
                stage.setOutcome(explorationMode
                        ?"先界定「"+goal.getCustomDirection()+"」的学习边界并补充可信资料，再逐块完成资料、练习和测试"
                        :"围绕「"+goal.getName()+"」逐块完成资料学习、练习与测试");stageMapper.insert(stage);
                for(int i=0;i<afterList.size();i++){
                    PlanChangeItemEntity item=new PlanChangeItemEntity();item.setPublicId(UUID.randomUUID().toString());item.setPlanVersionId(version.getId());
                    Long targetId=targets.get(i);item.setAction(actions.get(i));item.setTargetTaskId(targetId);
                    item.setClientRef(String.valueOf(afterList.get(i).getOrDefault("clientRef","task-"+UUID.randomUUID())));
                    if(targetId!=null)item.setBeforeJson(json(taskSnapshot(currentTasks.stream().filter(t->Objects.equals(t.getId(),targetId)).findFirst().orElseThrow(),zone)));
                    item.setAfterJson(json(afterList.get(i)));
                    item.setReason(reasons.get(i));item.setRiskLevel("LOW");item.setConfirmRequired(true);item.setItemStatus("PROPOSED");changeMapper.insert(item);
                }
                validateInternal(version,goal,profile);
                job.setPlanVersionId(version.getId());job.setStatus("SUCCEEDED");job.setFinishedAt(Instant.now());jobMapper.updateById(job);
                // 后台线程没有请求上下文，审计字段在提交线程已捕获，用 recordAs 显式传入
                audit.recordAs(userId,auditRequestId,auditIp,"PLAN_PROPOSAL_CREATE","PLAN_VERSION",
                        version.getPublicId(),null,"changes="+afterList.size(),"SUCCESS");
            });
        } catch (RuntimeException failure) {
            log.error("规划作业 {} 生成失败: {}", job.getPublicId(), failure.getMessage(), failure);
            markJobFailed(job, failure);
            throw failure;
        }
    }

    private void markJobFailed(PlanningJobEntity job, Throwable failure) {
        PlanningJobEntity failed = new PlanningJobEntity();
        failed.setId(job.getId());
        failed.setStatus("FAILED");
        failed.setErrorCode(errorCodeOf(failure));
        failed.setErrorMessage(errorMessageOf(failure));
        failed.setFinishedAt(Instant.now());
        jobMapper.updateById(failed);
    }

    private String errorCodeOf(Throwable failure) {
        if (failure instanceof AiModelException ai) return ai.getCode().name();
        if (failure instanceof BusinessException business) return business.getCode().name();
        return ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE.name();
    }

    private String errorMessageOf(Throwable failure) {
        if (failure instanceof AiModelException ai && ai.getUserMessage() != null && !ai.getUserMessage().isBlank())
            return ai.getUserMessage();
        if (failure instanceof BusinessException business) return business.getMessage();
        // 未知异常不向用户透传原始堆栈信息，细节进后端日志
        return "计划生成失败，请稍后重试";
    }

    /** 服务重启后会遗留 QUEUED/RUNNING 作业，超过 30 分钟视为中断：标记 FAILED，避免永久占用并发名额。 */
    private void expireStaleRunningJobs(Long goalId, long userId) {
        List<PlanningJobEntity> stale = jobMapper.selectList(new LambdaQueryWrapper<PlanningJobEntity>()
                .eq(PlanningJobEntity::getGoalId, goalId)
                .eq(PlanningJobEntity::getUserId, userId)
                .in(PlanningJobEntity::getStatus, "QUEUED", "RUNNING")
                .lt(PlanningJobEntity::getStartedAt, Instant.now().minus(Duration.ofMinutes(30))));
        for (PlanningJobEntity job : stale) {
            PlanningJobEntity failed = new PlanningJobEntity();
            failed.setId(job.getId());
            failed.setStatus("FAILED");
            failed.setErrorCode(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE.name());
            failed.setErrorMessage("服务中断导致任务未完成，请重新提交");
            failed.setFinishedAt(Instant.now());
            jobMapper.updateById(failed);
        }
    }

    public PlanningJobEntity getJob(String publicId){
        PlanningJobEntity job=jobMapper.selectOne(new LambdaQueryWrapper<PlanningJobEntity>().eq(PlanningJobEntity::getPublicId,publicId)
                .eq(PlanningJobEntity::getUserId,SecurityUtils.currentUserId()));if(job==null)notFound();return job;
    }

    public JobView jobView(PlanningJobEntity job){
        PlanVersionEntity version=job.getPlanVersionId()==null?null:versionMapper.selectById(job.getPlanVersionId());
        LearningPlanEntity plan=version==null?null:planMapper.selectById(version.getPlanId());
        return new JobView(job.getPublicId(),job.getJobType(),job.getStatus(),plan==null?null:plan.getPublicId(),
                version==null?null:version.getPublicId(),job.getErrorCode(),job.getErrorMessage(),job.getStartedAt(),job.getFinishedAt());
    }

    /** 目标最近一次规划作业（含已完成）：页面刷新后据此恢复轮询；遗留的 RUNNING 作业在此先按过期标记 FAILED。 */
    public JobView latestJobForGoal(String goalPublicId) {
        LearningGoalEntity goal = ownedGoal(goalPublicId);
        long userId = SecurityUtils.currentUserId();
        expireStaleRunningJobs(goal.getId(), userId);
        PlanningJobEntity job = jobMapper.selectOne(new LambdaQueryWrapper<PlanningJobEntity>()
                .eq(PlanningJobEntity::getGoalId, goal.getId())
                .eq(PlanningJobEntity::getUserId, userId)
                .orderByDesc(PlanningJobEntity::getStartedAt)
                .last("LIMIT 1"));
        return job == null ? null : jobView(job);
    }

    public PlanDetail getPlan(String publicId){
        LearningPlanEntity plan=ownedPlan(publicId);Long current=jdbc.query("SELECT plan_version_id FROM plan_publication WHERE plan_id=?",rs->rs.next()?rs.getLong(1):null,plan.getId());
        return new PlanDetail(plan,current==null?null:versionMapper.selectById(current));
    }

    public VersionDetail currentPlanForGoal(String goalPublicId) {
        return currentPlanForGoal(goalPublicId, null);
    }

    public VersionDetail currentPlanForGoal(String goalPublicId, String projectPublicId) {
        LearningGoalEntity goal = ownedGoal(goalPublicId);
        Long projectId = resolveProject(projectPublicId, goal.getId(), false);
        Long versionId = jdbc.query("""
                SELECT pv.id
                FROM learning_plan p
                JOIN plan_version pv ON pv.plan_id=p.id
                WHERE p.goal_id=? AND p.user_id=? AND p.deleted_at IS NULL
                  AND ((? IS NULL AND p.project_id IS NULL) OR p.project_id=?)
                  AND pv.status IN ('DRAFT','VALIDATING','PENDING_CONFIRMATION')
                ORDER BY pv.version_no DESC
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, goal.getId(), SecurityUtils.currentUserId(), projectId, projectId);
        if (versionId == null) versionId = jdbc.query("""
                SELECT pp.plan_version_id
                FROM learning_plan p
                JOIN plan_publication pp ON pp.plan_id=p.id
                WHERE p.goal_id=? AND p.user_id=? AND p.deleted_at IS NULL
                  AND ((? IS NULL AND p.project_id IS NULL) OR p.project_id=?)
                ORDER BY pp.published_at DESC
                LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, goal.getId(), SecurityUtils.currentUserId(), projectId, projectId);
        if (versionId == null) return null;
        PlanVersionEntity current = versionMapper.selectById(versionId);
        return current == null ? null : version(current.getPublicId());
    }

    /**
     * 目标当前「正式生效」的计划：返回最新已发布版本（plan_publication 指向，pv.status=PUBLISHED）
     * 及其阶段与生效时间，未发布过返回 null。与 currentPlanForGoal 不同，这里只看已发布版本，
     * 不返回待确认/校验中的提案；不传 projectId 时返回该目标所有计划中最新的已发布版本。
     */
    public EffectivePlanView effectivePlan(String goalPublicId) {
        return effectivePlan(goalPublicId, null);
    }

    public EffectivePlanView effectivePlan(String goalPublicId, String projectPublicId) {
        LearningGoalEntity goal = ownedGoal(goalPublicId);
        Long projectId = resolveProject(projectPublicId, goal.getId(), false);
        Map<String,Object> pub = jdbc.query("""
                SELECT pv.id AS version_id, pp.published_at AS published_at
                FROM learning_plan p
                JOIN plan_publication pp ON pp.plan_id = p.id
                JOIN plan_version pv ON pv.id = pp.plan_version_id
                WHERE p.goal_id=? AND p.user_id=? AND p.deleted_at IS NULL
                  AND pv.status='PUBLISHED'
                  AND ((? IS NULL AND p.project_id IS NULL) OR p.project_id=?)
                ORDER BY pp.published_at DESC
                LIMIT 1
                """, rs -> {
            if (!rs.next()) return null;
            Map<String,Object> value = new HashMap<>();
            value.put("versionId", rs.getLong(1));
            value.put("publishedAt", rs.getTimestamp(2).toInstant());
            return value;
        }, goal.getId(), SecurityUtils.currentUserId(), projectId, projectId);
        if (pub == null) return null;
        PlanVersionEntity version = versionMapper.selectById((Long) pub.get("versionId"));
        if (version == null) return null;
        return new EffectivePlanView(version,
                stageMapper.selectList(new LambdaQueryWrapper<PlanStageEntity>()
                        .eq(PlanStageEntity::getPlanVersionId, version.getId())
                        .orderByAsc(PlanStageEntity::getSequenceNo)),
                (Instant) pub.get("publishedAt"));
    }

    public List<PlanVersionEntity> versions(String planPublicId){LearningPlanEntity p=ownedPlan(planPublicId);return versionMapper.selectList(new LambdaQueryWrapper<PlanVersionEntity>()
            .eq(PlanVersionEntity::getPlanId,p.getId()).orderByDesc(PlanVersionEntity::getVersionNo));}

    public VersionDetail version(String publicId){PlanVersionEntity v=ownedVersion(publicId);return new VersionDetail(v,
            stageMapper.selectList(new LambdaQueryWrapper<PlanStageEntity>().eq(PlanStageEntity::getPlanVersionId,v.getId()).orderByAsc(PlanStageEntity::getSequenceNo)),
            changeMapper.selectList(new LambdaQueryWrapper<PlanChangeItemEntity>().eq(PlanChangeItemEntity::getPlanVersionId,v.getId())),
            validationMapper.selectList(new LambdaQueryWrapper<PlanValidationResultEntity>().eq(PlanValidationResultEntity::getPlanVersionId,v.getId())));}

    @Transactional
    public VersionDetail validate(String publicId){PlanVersionEntity preview=ownedVersion(publicId);LearningPlanEntity p=planMapper.lockById(preview.getPlanId());PlanVersionEntity v=versionMapper.lockById(preview.getId());
        PlanningContextPolicy.requireState(v.getStatus(),"重新校验","DRAFT","VALIDATION_FAILED");LearningGoalEntity goal=goalMapper.selectById(p.getGoalId());requireActiveScope(goal,p);requireContextFresh(v,p);validateInternal(v,goal,profileContext(SecurityUtils.currentUserId()));return version(publicId);}

    @Transactional
    public ConfirmationToken requestConfirmation(String publicId){
        PlanVersionEntity preview=ownedVersion(publicId);LearningPlanEntity p=planMapper.lockById(preview.getPlanId());PlanVersionEntity v=versionMapper.lockById(preview.getId());PlanningContextPolicy.requireState(v.getStatus(),"申请确认","PENDING_CONFIRMATION");requireActiveScope(goalMapper.selectById(p.getGoalId()),p);requirePublishable(v);requireContextFresh(v,p);
        confirmationMapper.update(null,new LambdaUpdateWrapper<PlanConfirmationEntity>().eq(PlanConfirmationEntity::getPlanVersionId,v.getId())
                .eq(PlanConfirmationEntity::getStatus,"PENDING").set(PlanConfirmationEntity::getStatus,"EXPIRED"));
        byte[] bytes=new byte[48];random.nextBytes(bytes);String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        PlanConfirmationEntity c=new PlanConfirmationEntity();c.setPlanVersionId(v.getId());c.setUserId(SecurityUtils.currentUserId());
        c.setProposalHash(v.getProposalHash());c.setTokenHash(hashing.sha256(raw));c.setStatus("PENDING");c.setCreatedAt(Instant.now());
        c.setExpiresAt(Instant.now().plus(Duration.ofMinutes(confirmationMinutes)));confirmationMapper.insert(c);
        return new ConfirmationToken(raw,c.getExpiresAt(),v.getProposalHash());
    }

    @Transactional
    public PublicationResult publish(String publicId,String confirmationToken,String idempotencyKey){
        if(idempotencyKey==null||idempotencyKey.isBlank())throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,"缺少 Idempotency-Key");
        long userId=SecurityUtils.currentUserId();String requestBody=publicId+"|"+hashing.sha256(confirmationToken==null?"":confirmationToken);
        if(userMapper.lockById(userId)==null)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"用户不存在");
        IdempotencyRecordEntity record=idempotency.find(userId,idempotencyKey,requestBody);
        if(record!=null){PlanVersionEntity done=ownedVersion(record.getResponseRef());return publicationResult(done);}
        PlanVersionEntity v=ownedVersion(publicId);PlanningContextPolicy.requireState(v.getStatus(),"发布","PENDING_CONFIRMATION");requirePublishable(v);
        PlanConfirmationEntity c=confirmationMapper.selectOne(new LambdaQueryWrapper<PlanConfirmationEntity>()
                .eq(PlanConfirmationEntity::getPlanVersionId,v.getId()).eq(PlanConfirmationEntity::getUserId,userId)
                .eq(PlanConfirmationEntity::getTokenHash,hashing.sha256(confirmationToken==null?"":confirmationToken))
                .eq(PlanConfirmationEntity::getStatus,"PENDING"));
        if(c==null||c.getExpiresAt().isBefore(Instant.now())||!c.getProposalHash().equals(v.getProposalHash()))
            throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED,"确认令牌无效、已过期或提案已变化");
        LearningPlanEntity preview=planMapper.selectById(v.getPlanId());if(preview==null)notFound();
        lockProfileForPublication(userId);
        LearningGoalEntity goal=goalMapper.lockById(preview.getGoalId());
        lockProjectForPublication(preview);
        LearningPlanEntity plan=planMapper.lockById(v.getPlanId());if(plan==null)notFound();
        PlanVersionEntity lockedVersion=versionMapper.lockById(v.getId());if(lockedVersion==null)notFound();v=lockedVersion;
        PlanningContextPolicy.requireState(v.getStatus(),"发布","PENDING_CONFIRMATION");
        requireActiveScope(goal,plan);requirePublishable(v);
        Long oldVersionId=publicationMapper.lockCurrent(plan.getId());
        Integer oldVersion=oldVersionId==null?null:Optional.ofNullable(versionMapper.selectById(oldVersionId)).map(PlanVersionEntity::getVersionNo).orElse(null);
        lockScopedTasks(plan.getGoalId(),plan.getProjectId());
        requireContextFresh(v,plan);
        Map<String,Object> context=map(v.getContextSnapshotJson());
        Integer expectedBase=nullableInt(context.get("basePlanVersion"));
        String expectedTasks=String.valueOf(context.getOrDefault("baseTaskFingerprint",""));
        PlanningContextPolicy.requirePublicationCas(expectedBase,expectedTasks,oldVersion,
                scopedTaskFingerprint(plan.getGoalId(),plan.getProjectId()));
        List<PlanChangeItemEntity> changes=changeMapper.selectList(new LambdaQueryWrapper<PlanChangeItemEntity>().eq(PlanChangeItemEntity::getPlanVersionId,v.getId())
                .eq(PlanChangeItemEntity::getItemStatus,"PROPOSED"));List<String> changed=new ArrayList<>();
        validateAuthoritativePlan(v,plan,goal,profileContext(userId));
        for(PlanChangeItemEntity item:changes){applyChange(item,plan,v,changed);item.setItemStatus("APPLIED");changeMapper.updateById(item);}
        rebuildTaskDependencies(plan.getGoalId(),plan.getProjectId(),changes);
        if(oldVersionId!=null&&!oldVersionId.equals(v.getId()))versionMapper.update(null,new LambdaUpdateWrapper<PlanVersionEntity>()
                .eq(PlanVersionEntity::getId,oldVersionId).eq(PlanVersionEntity::getStatus,"PUBLISHED").set(PlanVersionEntity::getStatus,"SUPERSEDED"));
        v.setStatus("PUBLISHED");versionMapper.updateById(v);publicationMapper.upsert(plan.getId(),v.getId(),Instant.now());
        c.setStatus("CONFIRMED");c.setConfirmedAt(Instant.now());confirmationMapper.updateById(c);
        OutboxEventEntity event=new OutboxEventEntity();event.setAggregateType("LEARNING_PLAN");event.setAggregateId(plan.getPublicId());event.setEventType("PlanPublished");
        event.setPayloadJson(json(Map.of("planId",plan.getPublicId(),"versionId",v.getPublicId(),"changedTaskIds",changed)));
        event.setCorrelationId(idempotencyKey);event.setStatus("PENDING");event.setAttempts(0);event.setNextRetryAt(Instant.now());event.setCreatedAt(Instant.now());outboxMapper.insert(event);
        audit.record("PLAN_PUBLISH","PLAN_VERSION",v.getPublicId(),oldVersionId==null?null:oldVersionId.toString(),"tasks="+changed.size(),"SUCCESS");
        idempotency.save(userId,idempotencyKey,requestBody,v.getPublicId());return new PublicationResult(plan.getPublicId(),v.getPublicId(),v.getVersionNo(),changed,"PUBLISHED");
    }

    @Transactional
    public VersionDetail partialSelection(String publicId,List<String> selectedIds){
        PlanVersionEntity preview=ownedVersion(publicId);LearningPlanEntity p=planMapper.lockById(preview.getPlanId());PlanVersionEntity source=versionMapper.lockById(preview.getId());PlanningContextPolicy.requireState(source.getStatus(),"部分采纳","VALIDATION_FAILED","PENDING_CONFIRMATION");requireActiveScope(goalMapper.selectById(p.getGoalId()),p);requireContextFresh(source,p);if(selectedIds==null||selectedIds.isEmpty())throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,"至少选择一个变更项");
        List<PlanChangeItemEntity> all=changeMapper.selectList(new LambdaQueryWrapper<PlanChangeItemEntity>().eq(PlanChangeItemEntity::getPlanVersionId,source.getId()));
        List<PlanChangeItemEntity> selected=all.stream().filter(x->selectedIds.contains(x.getPublicId())).toList();if(selected.size()!=selectedIds.size())notFound();
        Integer max=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0) FROM plan_version WHERE plan_id=?",Integer.class,source.getPlanId());
        PlanVersionEntity copy=new PlanVersionEntity();copy.setPublicId(UUID.randomUUID().toString());copy.setPlanId(source.getPlanId());copy.setVersionNo(max+1);
        copy.setBaseVersionNo(source.getBaseVersionNo());copy.setStatus("VALIDATING");copy.setTriggerType("PARTIAL_SELECTION");copy.setTriggerEventId(UUID.randomUUID().toString());
        copy.setContextSnapshotJson(source.getContextSnapshotJson());copy.setRiskLevel(source.getRiskLevel());copy.setSummaryJson(json(summary(selected.stream().map(x->map(x.getAfterJson())).toList())));
        copy.setProposalHash(hashing.sha256(json(selected.stream().map(PlanChangeItemEntity::getAfterJson).toList())));versionMapper.insert(copy);
        Set<String>selectedRefs=selected.stream().map(PlanChangeItemEntity::getClientRef).collect(java.util.stream.Collectors.toUnmodifiableSet());
        for(PlanChangeItemEntity old:selected){PlanChangeItemEntity n=new PlanChangeItemEntity();n.setPublicId(UUID.randomUUID().toString());n.setPlanVersionId(copy.getId());n.setAction(old.getAction());n.setTargetTaskId(old.getTargetTaskId());
            Map<String,Object>after=map(old.getAfterJson());after.put("dependencyTaskIds",stringList(after.get("dependencyTaskIds")).stream().filter(selectedRefs::contains).toList());
            n.setClientRef(old.getClientRef());n.setBeforeJson(old.getBeforeJson());n.setAfterJson(json(after));n.setReason(old.getReason());n.setRiskLevel(old.getRiskLevel());n.setConfirmRequired(true);n.setItemStatus("PROPOSED");changeMapper.insert(n);}
        writeSingleStage(copy,selected);source.setStatus("REJECTED");versionMapper.updateById(source);LearningGoalEntity goal=goalMapper.selectById(p.getGoalId());validateInternal(copy,goal,profileContext(SecurityUtils.currentUserId()));return version(copy.getPublicId());
    }

    @Transactional
    public void reject(String publicId,String reason){PlanVersionEntity preview=ownedVersion(publicId);planMapper.lockById(preview.getPlanId());PlanVersionEntity v=versionMapper.lockById(preview.getId());if(!Set.of("DRAFT","VALIDATION_FAILED","PENDING_CONFIRMATION").contains(v.getStatus()))
        throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"当前提案不能拒绝");v.setStatus("REJECTED");versionMapper.updateById(v);audit.record("PLAN_REJECT","PLAN_VERSION",publicId,null,reason,"SUCCESS");}

    @Transactional
    public VersionDetail rescheduleProposal(String taskPublicId,ZonedDateTime newStart,ZonedDateTime newDue,String reason){
        LearningTaskEntity task=ownedTask(taskPublicId);if(Set.of("COMPLETED","CANCELED").contains(task.getLifecycleStatus()))throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"已完成或取消任务不能重新排期");
        LearningGoalEntity goal=goalMapper.selectById(task.getGoalId());LearningPlanEntity plan=findOrCreatePlan(goal,task.getProjectId());
        requireActiveScope(goal,plan);ProfilePlanningContext profile=profileContext(task.getUserId());SemanticProfileContext semantic=semanticProfileContext(goal,profile,task.getUserId());ProjectPlanningContext project=projectContextById(task.getProjectId(),task.getUserId(),true);
        Integer max=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0) FROM plan_version WHERE plan_id=?",Integer.class,plan.getId());
        ZoneId zone=ZoneId.of(String.valueOf(profile.snapshot().get("timezone")));
        Map<String,Object> after=new LinkedHashMap<>(taskSnapshot(task,zone));after.put("clientRef","task-"+task.getPublicId());
        after.put("scheduledStart",newStart.toString());after.put("dueAt",newDue.toString());
        PlanVersionEntity v=new PlanVersionEntity();v.setPublicId(UUID.randomUUID().toString());v.setPlanId(plan.getId());v.setVersionNo(max+1);v.setStatus("VALIDATING");v.setTriggerType("RESCHEDULE");v.setTriggerEventId(UUID.randomUUID().toString());
        Map<String,Object>context=new LinkedHashMap<>();context.put("userId",String.valueOf(task.getUserId()));context.put("goalId",String.valueOf(goal.getId()));context.put("projectId",task.getProjectId()==null?null:String.valueOf(task.getProjectId()));context.put("goalVersion",goal.getVersion());context.put("goalFingerprint",PlanValidationPolicy.goalFingerprint(goal));context.put("basePlanVersion",currentPublishedVersionNo(goal.getId(),task.getProjectId()));context.put("baseTaskFingerprint",scopedTaskFingerprint(goal.getId(),task.getProjectId()));context.put("goalProfileVersionId",String.valueOf(semantic.versionId()));context.put("goalProfileVersionNo",semantic.versionNo());context.put("semanticProfileSource",semantic.source());context.put("goalProfileSnapshotHash",semantic.snapshotHash());context.put("schedulingProfileVersionId",String.valueOf(profile.schedulingVersionId()));context.put("schedulingProfileVersionNo",profile.schedulingVersionNo());context.put("schedulingProfileSnapshotHash",profile.snapshotHash());context.put("planningFingerprint",profile.planningFingerprint());context.put("timezone",profile.snapshot().get("timezone"));context.put("projectVersion",project==null?null:project.version());context.put("projectFingerprint",project==null?null:project.fingerprint());context.put("planningContextFingerprint",PlanningContextPolicy.fingerprint(objectMapper,context));v.setContextSnapshotJson(json(context));
        v.setProposalHash(hashing.sha256(json(after)));v.setRiskLevel(newDue.toLocalDate().isAfter(goal.getDueDate())?"HIGH":"LOW");v.setSummaryJson(json(Map.of("rescheduled",1,"affectedDateFrom",newStart.toLocalDate(),"affectedDateTo",newDue.toLocalDate())));versionMapper.insert(v);
        PlanChangeItemEntity item=new PlanChangeItemEntity();item.setPublicId(UUID.randomUUID().toString());item.setPlanVersionId(v.getId());item.setAction("RESCHEDULE_TASK");item.setTargetTaskId(task.getId());item.setClientRef("reschedule-1");
        item.setBeforeJson(json(taskSnapshot(task,zone)));item.setAfterJson(json(after));item.setReason(reason);item.setRiskLevel(v.getRiskLevel());item.setConfirmRequired(true);item.setItemStatus("PROPOSED");changeMapper.insert(item);
        validateInternal(v,goal,profile);return version(v.getPublicId());
    }

    private void validateInternal(PlanVersionEntity v,LearningGoalEntity goal,ProfilePlanningContext profile){
        validationMapper.delete(new LambdaQueryWrapper<PlanValidationResultEntity>().eq(PlanValidationResultEntity::getPlanVersionId,v.getId()));
        LearningPlanEntity plan=planMapper.selectById(v.getPlanId());
        if(plan==null)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"计划不存在");
        validateAuthoritativePlan(v,plan,goal,profile);
        List<PlanChangeItemEntity> items=changeMapper.selectList(new LambdaQueryWrapper<PlanChangeItemEntity>().eq(PlanChangeItemEntity::getPlanVersionId,v.getId()));
        List<PlanValidationPolicy.Change> changes=new ArrayList<>();
        for(PlanChangeItemEntity item:items){Map<String,Object> a=map(item.getAfterJson());changes.add(new PlanValidationPolicy.Change(item.getAction(),String.valueOf(a.get("title")),
                ZonedDateTime.parse(String.valueOf(a.get("scheduledStart"))),ZonedDateTime.parse(String.valueOf(a.get("dueAt"))),((Number)a.get("estimatedMinutes")).intValue(),
                Boolean.TRUE.equals(a.get("lockedSchedule")),Boolean.TRUE.equals(item.getConfirmRequired())));}
        List<PlanValidationPolicy.Issue> issues=PlanValidationPolicy.validate(changes,goal.getStartDate(),goal.getDueDate());
        for(var issue:issues){PlanValidationResultEntity e=new PlanValidationResultEntity();e.setPlanVersionId(v.getId());e.setValidatorCode(issue.code());e.setSeverity(issue.severity());e.setFieldPath(issue.fieldPath());e.setMessage(issue.message());e.setDetailsJson(json(issue.details()));e.setCreatedAt(Instant.now());validationMapper.insert(e);}
        v.setStatus(issues.stream().anyMatch(i->"ERROR".equals(i.severity()))?"VALIDATION_FAILED":"PENDING_CONFIRMATION");versionMapper.updateById(v);
    }

    private ProfilePlanningContext profileContext(long userId){
        Map<String,Object> row=jdbc.query("""
                SELECT p.id,p.profile_status,p.current_version_no,p.week_start,v.id profile_version_id,v.snapshot_json
                FROM user_profile p
                JOIN profile_version v ON v.profile_id=p.id AND v.version_no=p.current_version_no
                WHERE p.user_id=? AND p.deleted_at IS NULL
                """,rs->{if(!rs.next())return null;Map<String,Object>m=new LinkedHashMap<>();
                    m.put("profileId",rs.getLong("id"));m.put("status",rs.getString("profile_status"));
                    m.put("weekStart",rs.getInt("week_start"));
                    m.put("versionNo",rs.getInt("current_version_no"));m.put("versionId",rs.getLong("profile_version_id"));
                    m.put("snapshotJson",rs.getString("snapshot_json"));return m;},userId);
        if(row==null)throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,"尚未生成正式画像版本");
        if(!"GENERATED".equals(row.get("status")))throw new BusinessException(ErrorCode.PROFILE_CONTEXT_STALE,"画像已变化，请先重新生成正式画像");
        String snapshotJson=String.valueOf(row.get("snapshotJson"));Map<String,Object>snapshot=map(snapshotJson);
        List<RuleBasedPlanner.Slot> slots=new ArrayList<>();
        for(Map<String,Object>slot:mapList(snapshot.get("availabilityRules"))){
            int weekday=number(slot.get("weekday")).intValue();LocalTime start=LocalTime.parse(String.valueOf(slot.get("start")));
            slots.add(new RuleBasedPlanner.Slot(weekday,start,number(slot.get("availableMinutes")).intValue()));
        }
        List<RuleBasedPlanner.DayException> exceptions=new ArrayList<>();
        for(Map<String,Object>exception:mapList(snapshot.get("availabilityExceptions")))
            exceptions.add(new RuleBasedPlanner.DayException(LocalDate.parse(String.valueOf(exception.get("date"))),
                    number(exception.get("availableMinutes")).intValue()));
        Map<String,Object>preference=snapshot.get("preference") instanceof Map<?,?> value?stringMap(value):Map.of();
        BigDecimal capacityRatio=decimal(preference.get("capacityRatio"),new BigDecimal("0.85"));
        int weekStart=row.get("weekStart")==null?1:number(row.get("weekStart")).intValue();
        LocalDate planStart=Objects.requireNonNullElse(date(snapshot.get("planStartDate")),LocalDate.now());
        LocalDate planEnd=Objects.requireNonNullElse(date(snapshot.get("planEndDate")),planStart.plusDays(365));
        int dailyTasks=snapshot.get("dailyRecommendedTasks")==null?2:number(snapshot.get("dailyRecommendedTasks")).intValue();
        int focusMinutes=preference.get("focusMinutes")==null?45:number(preference.get("focusMinutes")).intValue();
        Map<String,Object> signals=new LinkedHashMap<>();
        signals.put("masteryAverage",Objects.requireNonNullElse(jdbc.queryForObject("SELECT COALESCE(AVG(score),0) FROM knowledge_mastery WHERE user_id=?",BigDecimal.class,userId),BigDecimal.ZERO));
        signals.put("lowMasteryCount",Objects.requireNonNullElse(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_mastery WHERE user_id=? AND score<60",Long.class,userId),0L));
        signals.put("recentCompleted",Objects.requireNonNullElse(jdbc.queryForObject("SELECT COUNT(*) FROM learning_task WHERE user_id=? AND lifecycle_status='COMPLETED' AND completed_at>=? AND deleted_at IS NULL",Long.class,userId,Instant.now().minus(Duration.ofDays(14))),0L));
        signals.put("recentOverdue",Objects.requireNonNullElse(jdbc.queryForObject("SELECT COUNT(*) FROM learning_task WHERE user_id=? AND lifecycle_status NOT IN ('COMPLETED','CANCELED') AND due_at<? AND due_at>=? AND deleted_at IS NULL",Long.class,userId,Instant.now(),Instant.now().minus(Duration.ofDays(14))),0L));
        String planningFingerprint=hashing.sha256(json(Map.of("schedulingProfileVersionId",row.get("versionId"),
                "snapshotHash",PlanningContextPolicy.fingerprint(snapshotJson),"weekStart",weekStart,
                "planStartDate",planStart,"planEndDate",planEnd,"dailyRecommendedTasks",dailyTasks,
                "focusMinutes",focusMinutes,"learningSignals",signals)));
        return new ProfilePlanningContext(number(row.get("profileId")).longValue(),String.valueOf(row.get("status")),
                number(row.get("versionId")).longValue(),number(row.get("versionNo")).intValue(),snapshotJson,
                PlanningContextPolicy.fingerprint(snapshotJson),snapshot,List.copyOf(slots),List.copyOf(exceptions),
                capacityRatio,weekStart,planStart,planEnd,dailyTasks,focusMinutes,signals,planningFingerprint);
    }

    private String planningBackground(ProfilePlanningContext profile){
        String background=String.valueOf(profile.snapshot().getOrDefault("backgroundText",""));
        return background+"\n近期学习反馈："+json(profile.learningSignals());
    }

    private SemanticProfileContext semanticProfileContext(LearningGoalEntity goal,ProfilePlanningContext scheduling,long userId){
        if(goal.getProfileVersionId()==null)return new SemanticProfileContext(scheduling.schedulingVersionId(),
                scheduling.schedulingVersionNo(),"CURRENT_GENERATED_PROFILE",scheduling.snapshotHash(),scheduling.snapshot());
        Map<String,Object>row=jdbc.query("""
                SELECT v.id,v.version_no,v.snapshot_json
                FROM profile_version v JOIN user_profile p ON p.id=v.profile_id
                WHERE v.id=? AND p.user_id=? AND p.deleted_at IS NULL
                """,rs->{if(!rs.next())return null;return Map.<String,Object>of("id",rs.getLong(1),"versionNo",rs.getInt(2),"snapshotJson",rs.getString(3));},goal.getProfileVersionId(),userId);
        if(row==null)throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"目标绑定的画像版本不存在");
        String snapshotJson=String.valueOf(row.get("snapshotJson"));return new SemanticProfileContext(number(row.get("id")).longValue(),
                number(row.get("versionNo")).intValue(),"GOAL_PROFILE_VERSION",PlanningContextPolicy.fingerprint(snapshotJson),map(snapshotJson));
    }

    private String profileDirectionStage(Map<String,Object>snapshot,LearningGoalEntity goal){
        for(Map<String,Object>direction:mapList(snapshot.get("directions"))){
            boolean samePublicDirection=goal.getDirectionId()!=null
                    &&Objects.equals(String.valueOf(goal.getDirectionId()),String.valueOf(direction.get("directionId")));
            boolean sameCustomDirection=goal.getDirectionId()==null
                    &&Objects.equals(goal.getCustomDirection(),direction.get("name"));
            if(samePublicDirection||sameCustomDirection){
                String stage=String.valueOf(direction.getOrDefault("currentStage","BEGINNER"));
                return Set.of("BEGINNER","INTERMEDIATE","ADVANCED").contains(stage)?stage:"BEGINNER";
            }
        }
        return "BEGINNER";
    }

    private ProjectPlanningContext projectContext(String publicId,long goalId,boolean requireActive){
        if(publicId==null||publicId.isBlank())return null;
        List<Map<String,Object>>rows=jdbc.query("""
                SELECT project.id,project.public_id,project.status,project.version,project.name,
                       project.description,project.start_date,project.due_date,project.priority,
                       project.deliverable_json,project.repository_url,link.contribution_weight
                FROM learning_project project JOIN goal_project link ON link.project_id=project.id
                WHERE project.public_id=? AND project.user_id=? AND link.goal_id=? AND project.deleted_at IS NULL
                """,(rs,row)->{Map<String,Object>value=new LinkedHashMap<>();value.put("id",rs.getLong("id"));value.put("publicId",rs.getString("public_id"));value.put("status",rs.getString("status"));value.put("version",rs.getInt("version"));value.put("name",rs.getString("name"));value.put("description",rs.getString("description"));value.put("startDate",rs.getObject("start_date"));value.put("dueDate",rs.getObject("due_date"));value.put("priority",rs.getString("priority"));value.put("deliverable",rs.getString("deliverable_json"));value.put("repository",rs.getString("repository_url"));value.put("contributionWeight",rs.getBigDecimal("contribution_weight"));return value;},publicId,SecurityUtils.currentUserId(),goalId);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"项目不存在或未关联当前目标");
        Map<String,Object>row=rows.get(0);String status=String.valueOf(row.get("status"));if(requireActive&&!"ACTIVE".equals(status))throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"只有活动项目可以规划");
        return projectPlanningContext(row);
    }

    private ProjectPlanningContext projectContextById(Long projectId,long userId,boolean requireActive){
        if(projectId==null)return null;List<Map<String,Object>>rows=jdbc.query("""
                SELECT project.id,project.public_id,project.status,project.version,project.name,project.description,
                       project.start_date,project.due_date,project.priority,project.deliverable_json,
                       project.repository_url,link.contribution_weight
                FROM learning_project project LEFT JOIN goal_project link ON link.project_id=project.id
                WHERE project.id=? AND project.user_id=? AND project.deleted_at IS NULL
                """,(rs,row)->{Map<String,Object>value=new LinkedHashMap<>();value.put("id",rs.getLong("id"));value.put("publicId",rs.getString("public_id"));value.put("status",rs.getString("status"));value.put("version",rs.getInt("version"));value.put("name",rs.getString("name"));value.put("description",rs.getString("description"));value.put("startDate",rs.getObject("start_date"));value.put("dueDate",rs.getObject("due_date"));value.put("priority",rs.getString("priority"));value.put("deliverable",rs.getString("deliverable_json"));value.put("repository",rs.getString("repository_url"));value.put("contributionWeight",rs.getBigDecimal("contribution_weight"));return value;},projectId,userId);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"项目不存在");Map<String,Object>row=rows.get(0);String status=String.valueOf(row.get("status"));if(requireActive&&!"ACTIVE".equals(status))throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"只有活动项目可以发布计划");return projectPlanningContext(row);
    }

    private ProjectPlanningContext projectPlanningContext(Map<String,Object> row){
        long projectId=number(row.get("id")).longValue();
        List<MilestonePlanningContext> milestones=jdbc.query("""
                SELECT id,public_id,sequence_no,name,due_date,weight,acceptance_json
                FROM milestone WHERE project_id=? AND status NOT IN ('COMPLETED','CANCELED')
                  AND deleted_at IS NULL ORDER BY sequence_no,id
                """,(rs,index)->new MilestonePlanningContext(rs.getLong("id"),rs.getString("public_id"),
                rs.getInt("sequence_no"),rs.getString("name"),rs.getObject("due_date",LocalDate.class),
                rs.getBigDecimal("weight").toPlainString(),criteria(rs.getString("acceptance_json"),
                "M:"+rs.getString("public_id")+":C")),projectId);
        Map<String,Object>fingerprint=new LinkedHashMap<>(row);fingerprint.remove("contributionWeight");
        fingerprint.put("milestones",milestones);
        return new ProjectPlanningContext(projectId,String.valueOf(row.get("publicId")),String.valueOf(row.get("status")),
                number(row.get("version")).intValue(),hashing.sha256(json(fingerprint)),String.valueOf(row.get("name")),
                Objects.toString(row.get("description"),""),String.valueOf(row.get("priority")),date(row.get("startDate")),
                date(row.get("dueDate")),parseJsonValue(row.get("deliverable")),Objects.toString(row.get("repository"),null),
                Objects.toString(row.get("contributionWeight"),null),List.copyOf(milestones));
    }

    private ProjectPlanningContext lockProjectForProposal(Long projectId,long userId){
        if(projectId==null)return null;
        Map<String,Object>locked=jdbc.query("SELECT id,status FROM learning_project WHERE id=? AND user_id=? AND deleted_at IS NULL FOR UPDATE",
                rs->{if(!rs.next())return null;return Map.<String,Object>of("id",rs.getLong(1),"status",rs.getString(2));},projectId,userId);
        if(locked==null)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"项目不存在");
        if(!"ACTIVE".equals(locked.get("status")))throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"只有活动项目可以规划");
        return projectContextById(projectId,userId,true);
    }

    private void applyChange(PlanChangeItemEntity item,LearningPlanEntity plan,PlanVersionEntity version,List<String> changed){
        Map<String,Object>a=map(item.getAfterJson());
        if("ADD_TASK".equals(item.getAction())){
            LearningTaskEntity t=new LearningTaskEntity();t.setPublicId(UUID.randomUUID().toString());t.setUserId(plan.getUserId());t.setGoalId(plan.getGoalId());t.setProjectId(plan.getProjectId());t.setOriginPlanVersionId(version.getId());
            t.setMilestoneId(nullableLong(a.get("milestoneId")));
            t.setTitle(String.valueOf(a.get("title")));t.setDescription(String.valueOf(a.getOrDefault("description","")));t.setTaskType(String.valueOf(a.get("taskType")));t.setPriority(String.valueOf(a.get("priority")));t.setEstimatedMinutes(((Number)a.get("estimatedMinutes")).intValue());
            t.setScheduledStart(ZonedDateTime.parse(String.valueOf(a.get("scheduledStart"))).toInstant());t.setDueAt(ZonedDateTime.parse(String.valueOf(a.get("dueAt"))).toInstant());t.setLockedSchedule(Boolean.TRUE.equals(a.get("lockedSchedule")));t.setLifecycleStatus("NOT_STARTED");t.setProgressPercent(BigDecimal.ZERO);t.setRescheduleCount(0);t.setAcceptanceJson(json(a.getOrDefault("acceptanceCriteria",List.of())));taskMapper.insert(t);changed.add(t.getPublicId());
            for(Long id:parseKnowledgePointIds(a.get("knowledgePointIds")))jdbc.update("INSERT INTO task_knowledge_point(task_id,knowledge_point_id,weight) VALUES(?,?,?)",t.getId(),id,BigDecimal.ONE);
            writeTaskKnowledgeSources(t.getId(),a);
            createLearningBlock(a,t,plan,version);
            item.setTargetTaskId(t.getId());
        }else{
            LearningTaskEntity t=taskMapper.selectById(item.getTargetTaskId());if(t==null)notFound();
            PlanningContextPolicy.requireTaskScope(plan.getUserId(),plan.getGoalId(),plan.getProjectId(),
                    t.getUserId(),t.getGoalId(),t.getProjectId());
            if("COMPLETED".equals(t.getLifecycleStatus()))throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"已完成任务不能由新计划覆盖");
            if("RESCHEDULE_TASK".equals(item.getAction())){
                Instant oldStart=t.getScheduledStart(),oldDue=t.getDueAt();t.setTitle(String.valueOf(a.getOrDefault("title",t.getTitle())));t.setDescription(String.valueOf(a.getOrDefault("description",t.getDescription()==null?"":t.getDescription())));t.setTaskType(String.valueOf(a.getOrDefault("taskType",t.getTaskType())));t.setPriority(String.valueOf(a.getOrDefault("priority",t.getPriority())));t.setEstimatedMinutes(((Number)a.getOrDefault("estimatedMinutes",t.getEstimatedMinutes())).intValue());t.setAcceptanceJson(json(a.getOrDefault("acceptanceCriteria",readList(t.getAcceptanceJson()))));t.setMilestoneId(a.containsKey("milestoneId")?nullableLong(a.get("milestoneId")):t.getMilestoneId());t.setScheduledStart(ZonedDateTime.parse(String.valueOf(a.get("scheduledStart"))).toInstant());t.setDueAt(ZonedDateTime.parse(String.valueOf(a.get("dueAt"))).toInstant());t.setRescheduleCount(t.getRescheduleCount()+1);taskMapper.updateById(t);jdbc.update("DELETE FROM task_knowledge_point WHERE task_id=?",t.getId());for(Long id:parseKnowledgePointIds(a.get("knowledgePointIds")))jdbc.update("INSERT INTO task_knowledge_point(task_id,knowledge_point_id,weight) VALUES(?,?,?)",t.getId(),id,BigDecimal.ONE);writeTaskKnowledgeSources(t.getId(),a);jdbc.update("INSERT INTO task_schedule_history(id,task_id,old_start,old_due,new_start,new_due,reason,source_plan_version_id,created_at) VALUES(?,?,?,?,?,?,?,?,?)",IdWorker.getId(),t.getId(),oldStart,oldDue,t.getScheduledStart(),t.getDueAt(),item.getReason(),version.getId(),Instant.now());
            }else if("CANCEL_TASK".equals(item.getAction())){
                taskCancellation.cancelForPlanPublication(t.getId(), plan.getUserId(), item.getReason());
            }else{t.setTitle(String.valueOf(a.getOrDefault("title",t.getTitle())));taskMapper.updateById(t);}
            changed.add(t.getPublicId());
        }
    }

    private void writeTaskKnowledgeSources(long taskId,Map<String,Object> after){
        jdbc.update("DELETE FROM task_knowledge_source WHERE task_id=?",taskId);
        Object raw=after.get("knowledgeSources");
        if(!(raw instanceof List<?> sources))return;
        for(Object source:sources){
            if(!(source instanceof Map<?,?> value)||value.get("chunkId")==null)continue;
            long chunkId=number(value.get("chunkId")).longValue();
            jdbc.update("INSERT INTO task_knowledge_source(task_id,chunk_id,created_at) VALUES(?,?,?)",
                    taskId,chunkId,Instant.now());
        }
    }

    private List<Map<String,Object>> catalogReferences(List<Long> knowledgePointIds){
        if(knowledgePointIds==null||knowledgePointIds.isEmpty())return List.of();
        String placeholders=String.join(",",Collections.nCopies(knowledgePointIds.size(),"?"));
        return jdbc.query("""
                SELECT DISTINCT reference.source_type,reference.title,reference.url,reference.summary
                FROM knowledge_point selected_point
                JOIN knowledge_point reference_point ON reference_point.direction_id=selected_point.direction_id
                JOIN knowledge_point_reference reference ON reference.knowledge_point_id=reference_point.id
                WHERE selected_point.id IN (%s) AND reference.status='ACTIVE'
                ORDER BY reference.id
                LIMIT 6
                """.formatted(placeholders),(rs,row)->{
            Map<String,Object>item=new LinkedHashMap<>();
            item.put("sourceType",rs.getString("source_type"));item.put("title",rs.getString("title"));
            item.put("url",rs.getString("url"));item.put("quotePreview",rs.getString("summary"));
            return item;
        },knowledgePointIds.toArray());
    }

    @SuppressWarnings("unchecked")
    private void createLearningBlock(Map<String,Object> after,LearningTaskEntity task,
                                     LearningPlanEntity plan,PlanVersionEntity version){
        Object raw=after.get("learningBlock");
        if(!(raw instanceof Map<?,?> untyped))return;
        Map<String,Object>block=(Map<String,Object>)untyped;
        long blockId=IdWorker.getId();Instant now=Instant.now();
        jdbc.update("""
                INSERT INTO learning_block(
                  id,public_id,user_id,goal_id,task_id,origin_plan_version_id,sequence_no,
                  title,objective,direction_name,exploration_required,source_status,
                  source_manifest_json,source_queries_json,generation_status,pass_score,
                  attempt_count,status,created_at,created_by,updated_at,updated_by,version
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,70,0,'READY',?,?,?,?,0)
                """,blockId,UUID.randomUUID().toString(),plan.getUserId(),plan.getGoalId(),task.getId(),version.getId(),
                ((Number)block.get("sequenceNo")).intValue(),String.valueOf(block.get("title")),
                String.valueOf(block.get("objective")),String.valueOf(block.get("directionName")),
                Boolean.TRUE.equals(block.get("explorationRequired")),String.valueOf(block.get("sourceStatus")),
                json(block.getOrDefault("sourceManifest",List.of())),
                json(block.getOrDefault("sourceQueries",List.of())),"OUTLINE",
                now,plan.getUserId(),now,plan.getUserId());
        jdbc.update("UPDATE learning_task SET learning_block_id=? WHERE id=?",blockId,task.getId());
        task.setLearningBlockId(blockId);
    }

    private KnowledgePrerequisiteContext knowledgePrerequisiteContext(
            Long directionId, long userId, Set<Long> candidateKnowledgePointIds) {
        if (directionId == null || candidateKnowledgePointIds.isEmpty()) {
            return new KnowledgePrerequisiteContext(List.of(), Set.of());
        }
        List<KnowledgePrerequisitePolicy.Dependency> dependencies = jdbc.query("""
                SELECT dependency.predecessor_id,dependency.successor_id
                FROM knowledge_dependency dependency
                JOIN knowledge_point predecessor ON predecessor.id=dependency.predecessor_id
                JOIN knowledge_point successor ON successor.id=dependency.successor_id
                WHERE predecessor.direction_id=? AND successor.direction_id=?
                  AND dependency.type='PREREQUISITE'
                  AND predecessor.status='ACTIVE' AND successor.status='ACTIVE'
                  AND predecessor.deleted_at IS NULL AND successor.deleted_at IS NULL
                ORDER BY dependency.predecessor_id,dependency.successor_id
                """, (rs, row) -> new KnowledgePrerequisitePolicy.Dependency(
                rs.getLong(1), rs.getLong(2)), directionId, directionId).stream()
                .filter(edge -> candidateKnowledgePointIds.contains(edge.predecessorId())
                        && candidateKnowledgePointIds.contains(edge.successorId()))
                .toList();
        Set<Long> satisfied = jdbc.query("""
                SELECT knowledge_point_id
                FROM knowledge_mastery
                WHERE user_id=? AND level='PROFICIENT'
                """, (rs, row) -> rs.getLong(1), userId).stream()
                .filter(candidateKnowledgePointIds::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new KnowledgePrerequisiteContext(dependencies, satisfied);
    }

    private void rebuildTaskDependencies(long goalId,Long projectId,List<PlanChangeItemEntity> changes) {
        Map<String,Long> taskIdByRef=new HashMap<>();
        for(PlanChangeItemEntity item:changes)if(item.getTargetTaskId()!=null)taskIdByRef.put(item.getClientRef(),item.getTargetTaskId());
        List<Long>changedIds=taskIdByRef.values().stream().distinct().toList();
        if(!changedIds.isEmpty()){
            String placeholders=String.join(",",Collections.nCopies(changedIds.size(),"?"));
            List<Object>args=new ArrayList<>(changedIds);args.addAll(changedIds);
            jdbc.update("DELETE FROM task_dependency WHERE predecessor_task_id IN ("+placeholders+")"
                    +" OR successor_task_id IN ("+placeholders+")",args.toArray());
        }
        for(PlanChangeItemEntity item:changes){
            if(item.getTargetTaskId()==null||"CANCEL_TASK".equals(item.getAction()))continue;
            for(String predecessorRef:stringList(map(item.getAfterJson()).get("dependencyTaskIds"))){
                Long predecessor=taskIdByRef.get(predecessorRef);
                if(predecessor!=null&&!predecessor.equals(item.getTargetTaskId()))jdbc.update(
                        "INSERT INTO task_dependency(predecessor_task_id,successor_task_id) VALUES(?,?)",
                        predecessor,item.getTargetTaskId());
            }
        }
        List<DependencyGraphPolicy.Edge>finalEdges=jdbc.query("""
                SELECT dependency.predecessor_task_id,dependency.successor_task_id
                FROM task_dependency dependency
                JOIN learning_task predecessor ON predecessor.id=dependency.predecessor_task_id
                JOIN learning_task successor ON successor.id=dependency.successor_task_id
                WHERE predecessor.goal_id=? AND successor.goal_id=?
                  AND ((? IS NULL AND predecessor.project_id IS NULL AND successor.project_id IS NULL)
                       OR (predecessor.project_id=? AND successor.project_id=?))
                """,(rs,row)->new DependencyGraphPolicy.Edge(rs.getLong(1),rs.getLong(2)),
                goalId,goalId,projectId,projectId,projectId);
        DependencyGraphPolicy.requireAcyclic(finalEdges);
    }

    private List<LearningTaskEntity> scopedTasks(long goalId,Long projectId,boolean includeTerminal){
        LambdaQueryWrapper<LearningTaskEntity>query=new LambdaQueryWrapper<LearningTaskEntity>()
                .eq(LearningTaskEntity::getGoalId,goalId)
                .eq(projectId!=null,LearningTaskEntity::getProjectId,projectId)
                .isNull(projectId==null,LearningTaskEntity::getProjectId)
                .orderByAsc(LearningTaskEntity::getScheduledStart,LearningTaskEntity::getDueAt,LearningTaskEntity::getId);
        if(!includeTerminal)query.notIn(LearningTaskEntity::getLifecycleStatus,"COMPLETED","CANCELED");
        return taskMapper.selectList(query);
    }

    private String scopedTaskFingerprint(long goalId,Long projectId){
        List<Map<String,Object>>tasks=new ArrayList<>();
        for(LearningTaskEntity task:scopedTasks(goalId,projectId,true)){
            Map<String,Object>value=new LinkedHashMap<>();value.put("id",String.valueOf(task.getId()));value.put("publicId",task.getPublicId());
            value.put("version",task.getVersion());value.put("status",task.getLifecycleStatus());value.put("projectId",task.getProjectId()==null?null:String.valueOf(task.getProjectId()));
            value.put("milestoneId",task.getMilestoneId()==null?null:String.valueOf(task.getMilestoneId()));value.put("start",task.getScheduledStart());value.put("due",task.getDueAt());
            value.put("minutes",task.getEstimatedMinutes());value.put("title",task.getTitle());value.put("acceptance",task.getAcceptanceJson());
            value.put("knowledgePointIds",jdbc.query("SELECT knowledge_point_id FROM task_knowledge_point WHERE task_id=? ORDER BY knowledge_point_id",(rs,row)->String.valueOf(rs.getLong(1)),task.getId()));tasks.add(value);
        }
        List<Map<String,Object>>dependencies=jdbc.query("""
                SELECT d.predecessor_task_id,d.successor_task_id
                FROM task_dependency d
                JOIN learning_task successor ON successor.id=d.successor_task_id
                JOIN learning_task predecessor ON predecessor.id=d.predecessor_task_id
                WHERE successor.goal_id=? AND predecessor.goal_id=?
                  AND ((? IS NULL AND successor.project_id IS NULL AND predecessor.project_id IS NULL)
                       OR (successor.project_id=? AND predecessor.project_id=?))
                ORDER BY d.predecessor_task_id,d.successor_task_id
                """,(rs,row)->Map.of("predecessor",String.valueOf(rs.getLong(1)),"successor",String.valueOf(rs.getLong(2))),
                goalId,goalId,projectId,projectId,projectId);
        return hashing.sha256(json(Map.of("tasks",tasks,"dependencies",dependencies)));
    }

    private void lockScopedTasks(long goalId,Long projectId){
        jdbc.query("""
                SELECT id FROM learning_task
                WHERE goal_id=? AND deleted_at IS NULL
                  AND ((? IS NULL AND project_id IS NULL) OR project_id=?)
                ORDER BY id FOR UPDATE
                """,(rs,row)->rs.getLong(1),goalId,projectId,projectId);
    }

    private Integer currentPublishedVersionNo(long goalId,Long projectId){
        return jdbc.query("""
                SELECT pv.version_no FROM learning_plan p
                JOIN plan_publication pp ON pp.plan_id=p.id JOIN plan_version pv ON pv.id=pp.plan_version_id
                WHERE p.goal_id=? AND ((? IS NULL AND p.project_id IS NULL) OR p.project_id=?) AND p.deleted_at IS NULL
                """,rs->rs.next()?rs.getInt(1):null,goalId,projectId,projectId);
    }

    private String catalogFingerprint(List<Map<String,Object>>knowledge,KnowledgePrerequisiteContext prerequisites){
        return hashing.sha256(json(Map.of("knowledge",knowledge,"dependencies",prerequisites.dependencies(),
                "satisfied",prerequisites.satisfiedPrerequisiteIds().stream().sorted().toList())));
    }

    private void requireActiveScope(LearningGoalEntity goal,LearningPlanEntity plan){
        if(goal==null||!"ACTIVE".equals(goal.getStatus()))throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"只有活动目标可以执行计划操作");
        if(plan.getProjectId()!=null)projectContextById(plan.getProjectId(),plan.getUserId(),true);
    }

    private void lockProfileForPublication(long userId){
        Map<String,Object>profile=jdbc.query("SELECT id,profile_status FROM user_profile WHERE user_id=? AND deleted_at IS NULL FOR UPDATE",rs->{if(!rs.next())return null;return Map.of("id",rs.getLong(1),"status",rs.getString(2));},userId);
        if(profile==null||!"GENERATED".equals(profile.get("status")))throw new BusinessException(ErrorCode.PROFILE_CONTEXT_STALE,"画像已变化，请先重新生成正式画像");
    }

    private void lockProjectForPublication(LearningPlanEntity plan){
        if(plan.getProjectId()==null)return;Map<String,Object>project=jdbc.query("SELECT id,status FROM learning_project WHERE id=? AND user_id=? AND deleted_at IS NULL FOR UPDATE",rs->{if(!rs.next())return null;return Map.of("id",rs.getLong(1),"status",rs.getString(2));},plan.getProjectId(),plan.getUserId());if(project==null||!"ACTIVE".equals(project.get("status")))throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"只有活动项目可以发布计划");
    }

    private List<RuleBasedPlanner.OccupiedTask> schedulingOccupiedTasks(long userId,long goalId,Long projectId,
                                                                        boolean replaceScope,ZoneId zone){
        String scope=replaceScope?" AND NOT (goal_id=? AND ((? IS NULL AND project_id IS NULL) OR project_id=?))":"";
        List<Object>args=new ArrayList<>();args.add(userId);if(replaceScope){args.add(goalId);args.add(projectId);args.add(projectId);}
        return jdbc.query(("""
                SELECT public_id,scheduled_start,estimated_minutes FROM learning_task
                WHERE user_id=? AND lifecycle_status IN ('NOT_STARTED','IN_PROGRESS','PAUSED','BLOCKED')
                  AND deleted_at IS NULL AND scheduled_start IS NOT NULL
                """+scope+" ORDER BY scheduled_start,id"),(rs,row)->new RuleBasedPlanner.OccupiedTask(
                rs.getString("public_id"),rs.getTimestamp("scheduled_start").toInstant().atZone(zone),
                rs.getInt("estimated_minutes")),args.toArray()).stream()
                .filter(task->task.end().toInstant().isAfter(Instant.now())).toList();
    }

    /** publication 锁内再次调用，确保以最新正式任务形成 simulated final user task set。 */
    private void validateAuthoritativePlan(PlanVersionEntity version,LearningPlanEntity plan,
                                           LearningGoalEntity goal,ProfilePlanningContext profile){
        ZoneId zone=ZoneId.of(String.valueOf(profile.snapshot().get("timezone")));
        List<PlanChangeItemEntity> changes=changeMapper.selectList(new LambdaQueryWrapper<PlanChangeItemEntity>()
                .eq(PlanChangeItemEntity::getPlanVersionId,version.getId()).eq(PlanChangeItemEntity::getItemStatus,"PROPOSED"));
        Map<String,PlanningCapacityPolicy.Task> simulated=new LinkedHashMap<>();
        jdbc.query("""
                SELECT task.id,task.public_id,task.goal_id,task.project_id,task.milestone_id,
                       task.lifecycle_status,task.scheduled_start,task.due_at,task.estimated_minutes,
                       goal.start_date goal_start,goal.due_date goal_due,
                       project.start_date project_start,project.due_date project_due,milestone.due_date milestone_due
                FROM learning_task task JOIN learning_goal goal ON goal.id=task.goal_id
                LEFT JOIN learning_project project ON project.id=task.project_id
                LEFT JOIN milestone milestone ON milestone.id=task.milestone_id
                WHERE task.user_id=? AND task.deleted_at IS NULL AND task.scheduled_start IS NOT NULL
                """,rs->{while(rs.next()){
                    LocalDate earliest=maxDate(profile.planStartDate(),date(rs.getObject("goal_start")),date(rs.getObject("project_start")));
                    LocalDate latest=minDate(profile.planEndDate(),date(rs.getObject("goal_due")),date(rs.getObject("project_due")),date(rs.getObject("milestone_due")));
                    String key=String.valueOf(rs.getLong("id"));simulated.put(key,new PlanningCapacityPolicy.Task(key,
                            rs.getLong("goal_id"),(Long)rs.getObject("project_id"),(Long)rs.getObject("milestone_id"),
                            rs.getString("lifecycle_status"),rs.getTimestamp("scheduled_start").toInstant().atZone(zone),
                            rs.getTimestamp("due_at").toInstant().atZone(zone),rs.getInt("estimated_minutes"),earliest,latest));
                }return null;},plan.getUserId());
        for(PlanChangeItemEntity item:changes){
            if(item.getTargetTaskId()!=null)simulated.remove(String.valueOf(item.getTargetTaskId()));
            if("CANCEL_TASK".equals(item.getAction()))continue;
            Map<String,Object>after=map(item.getAfterJson());Long milestoneId=nullableLong(after.get("milestoneId"));
            LocalDate milestoneDue=milestoneId==null?null:jdbc.query("SELECT due_date FROM milestone WHERE id=? AND deleted_at IS NULL",
                    rs->rs.next()?date(rs.getObject(1)):null,milestoneId);
            LocalDate earliest=maxDate(profile.planStartDate(),goal.getStartDate(),plan.getProjectId()==null?null:
                    projectContextById(plan.getProjectId(),plan.getUserId(),true).startDate());
            LocalDate latest=minDate(profile.planEndDate(),goal.getDueDate(),plan.getProjectId()==null?null:
                    projectContextById(plan.getProjectId(),plan.getUserId(),true).dueDate(),milestoneDue);
            simulated.put(item.getClientRef(),new PlanningCapacityPolicy.Task(item.getClientRef(),goal.getId(),
                    plan.getProjectId(),milestoneId,null,ZonedDateTime.parse(String.valueOf(after.get("scheduledStart"))),
                    ZonedDateTime.parse(String.valueOf(after.get("dueAt"))),number(after.get("estimatedMinutes")).intValue(),
                    earliest,latest));
        }
        Map<Long,Integer>budgets=jdbc.query("SELECT id,weekly_budget_minutes FROM learning_goal WHERE user_id=? AND deleted_at IS NULL",
                rs->{Map<Long,Integer>values=new HashMap<>();while(rs.next())values.put(rs.getLong(1),rs.getInt(2));return values;},plan.getUserId());
        List<PlanningCapacityPolicy.Issue> capacityIssues=PlanningCapacityPolicy.validate(new ArrayList<>(simulated.values()),
                new PlanningCapacityPolicy.Context(zone,profile.weekStart(),profile.capacityRatio(),profile.slots(),
                        profile.exceptions(),budgets==null?Map.of():budgets),Instant.now());
        if(!capacityIssues.isEmpty())throw new BusinessException(ErrorCode.PLAN_CAPACITY_EXCEEDED,
                capacityIssues.get(0).message()+"（"+capacityIssues.get(0).code()+"）");
        requireCoverage(changes,plan,goal);
    }

    private void requireCoverage(List<PlanChangeItemEntity>changes,LearningPlanEntity plan,LearningGoalEntity goal){
        Map<String,PlanCandidatePolicy.FinalTask>finalTasks=new LinkedHashMap<>();
        for(LearningTaskEntity task:scopedTasks(goal.getId(),plan.getProjectId(),true)){
            Map<String,Object>metadata=taskCoverageMetadata(task.getId());
            finalTasks.put(String.valueOf(task.getId()),new PlanCandidatePolicy.FinalTask(String.valueOf(task.getId()),
                    task.getLifecycleStatus(),task.getMilestoneId(),stringList(readList(task.getAcceptanceJson())),
                    Set.copyOf(stringList(metadata.get("coveredGoalCriterionIds"))),
                    Set.copyOf(stringList(metadata.get("coveredMilestoneCriterionIds")))));
        }
        for(PlanChangeItemEntity item:changes){
            if(item.getTargetTaskId()!=null)finalTasks.remove(String.valueOf(item.getTargetTaskId()));
            if("CANCEL_TASK".equals(item.getAction()))continue;Map<String,Object>after=map(item.getAfterJson());
            finalTasks.put(item.getClientRef(),new PlanCandidatePolicy.FinalTask(item.getClientRef(),"NOT_STARTED",
                    nullableLong(after.get("milestoneId")),stringList(after.get("acceptanceCriteria")),
                    Set.copyOf(stringList(after.get("coveredGoalCriterionIds"))),
                    Set.copyOf(stringList(after.get("coveredMilestoneCriterionIds")))));
        }
        Map<Long,PlanCandidatePolicy.Milestone>milestones=Map.of();
        if(plan.getProjectId()!=null){ProjectPlanningContext project=projectContextById(plan.getProjectId(),plan.getUserId(),true);
            milestones=project.milestones().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    MilestonePlanningContext::id,milestone->new PlanCandidatePolicy.Milestone(milestone.id(),
                            milestone.publicId(),milestone.dueDate(),milestone.criteria().stream()
                            .map(PythonAiServiceClient.PlanCriterion::criterionId).collect(java.util.stream.Collectors.toUnmodifiableSet()))));}
        Set<String>goalCriteria=criteria(goal.getSuccessCriteriaJson(),"GC").stream()
                .map(PythonAiServiceClient.PlanCriterion::criterionId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        candidatePolicy.requireFinalCoverage(new ArrayList<>(finalTasks.values()),goalCriteria,milestones);
    }

    private Map<String,Object>taskCoverageMetadata(long taskId){
        String after=jdbc.query("SELECT after_json FROM plan_change_item WHERE target_task_id=? AND item_status='APPLIED' ORDER BY id DESC LIMIT 1",
                rs->rs.next()?rs.getString(1):null,taskId);return after==null?Map.of():map(after);
    }

    private void requirePublishable(PlanVersionEntity v){if(!"PENDING_CONFIRMATION".equals(v.getStatus()))throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED,"提案尚未通过校验或已不在待确认状态");long errors=validationMapper.selectCount(new LambdaQueryWrapper<PlanValidationResultEntity>().eq(PlanValidationResultEntity::getPlanVersionId,v.getId()).eq(PlanValidationResultEntity::getSeverity,"ERROR"));if(errors>0)throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED,"提案仍有校验错误");}
    private void requireContextFresh(PlanVersionEntity v,LearningPlanEntity plan){
        Map<String,Object>c=map(v.getContextSnapshotJson());Object storedFingerprint=c.remove("planningContextFingerprint");
        if(storedFingerprint==null||!Objects.equals(String.valueOf(storedFingerprint),PlanningContextPolicy.fingerprint(objectMapper,c)))
            throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"规划上下文已损坏或不完整");
        long userId=SecurityUtils.currentUserId();long goalId=number(c.get("goalId")).longValue();int expected=number(c.get("goalVersion")).intValue();LearningGoalEntity g=goalMapper.selectById(goalId);if(g==null||PlanValidationPolicy.goalContextStale((String)c.get("goalFingerprint"),g,expected,g.getVersion()))throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"目标在预览后已发生变化");requireActiveScope(g,plan);ProfilePlanningContext current=profileContext(userId);if(!Objects.equals(String.valueOf(c.get("schedulingProfileVersionId")),String.valueOf(current.schedulingVersionId()))||!Objects.equals(c.get("schedulingProfileSnapshotHash"),current.snapshotHash())||!Objects.equals(c.get("planningFingerprint"),current.planningFingerprint()))throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"画像在预览后已发生变化");
        if(c.get("projectFingerprint")!=null){ProjectPlanningContext project=projectContextById(plan.getProjectId(),userId,true);if(!Objects.equals(c.get("projectFingerprint"),project.fingerprint()))throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"项目在预览后已发生变化");}
        List<Map<String,Object>>knowledge=g.getDirectionId()==null?List.of():jdbc.query("SELECT id,name FROM knowledge_point WHERE direction_id=? AND status='ACTIVE' AND deleted_at IS NULL ORDER BY level,id",(rs,row)->Map.of("id",rs.getLong(1),"name",rs.getString(2)),g.getDirectionId());Set<Long>ids=knowledge.stream().map(item->number(item.get("id")).longValue()).collect(java.util.stream.Collectors.toUnmodifiableSet());KnowledgePrerequisiteContext prerequisites=knowledgePrerequisiteContext(g.getDirectionId(),userId,ids);if(c.get("catalogFingerprint")!=null&&!Objects.equals(c.get("catalogFingerprint"),catalogFingerprint(knowledge,prerequisites)))throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"学习目录或掌握度在预览后已发生变化");
        List<String>spaceIds=stringList(c.get("knowledgeSpaceIds"));
        if(!spaceIds.isEmpty()){
            KnowledgePlanContext sourceContext=knowledgePlanContext(spaceIds,userId);
            if(!Objects.equals(c.get("knowledgeFingerprint"),sourceContext.fingerprint()))
                throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"计划引用的知识库资料在预览后已发生变化，请重新生成");
        }
    }
    private PublicationResult publicationResult(PlanVersionEntity v){LearningPlanEntity p=planMapper.selectById(v.getPlanId());List<String>tasks=taskMapper.selectList(new LambdaQueryWrapper<LearningTaskEntity>().eq(LearningTaskEntity::getOriginPlanVersionId,v.getId())).stream().map(LearningTaskEntity::getPublicId).toList();return new PublicationResult(p.getPublicId(),v.getPublicId(),v.getVersionNo(),tasks,v.getStatus());}
    private LearningPlanEntity findOrCreatePlan(LearningGoalEntity goal,Long projectId){LearningPlanEntity p=planMapper.selectOne(new LambdaQueryWrapper<LearningPlanEntity>().eq(LearningPlanEntity::getGoalId,goal.getId()).eq(projectId!=null,LearningPlanEntity::getProjectId,projectId).isNull(projectId==null,LearningPlanEntity::getProjectId));if(p==null){p=new LearningPlanEntity();p.setPublicId(UUID.randomUUID().toString());p.setUserId(goal.getUserId());p.setGoalId(goal.getId());p.setProjectId(projectId);p.setName(goal.getName()+"学习计划");p.setStatus("ACTIVE");planMapper.insert(p);}return p;}
    private Long resolveProject(String publicId,long goalId,boolean requireActive){ProjectPlanningContext p=projectContext(publicId,goalId,requireActive);return p==null?null:p.id();}
    private LearningGoalEntity ownedGoal(String publicId){LearningGoalEntity g=goalMapper.selectOne(new LambdaQueryWrapper<LearningGoalEntity>().eq(LearningGoalEntity::getPublicId,publicId).eq(LearningGoalEntity::getUserId,SecurityUtils.currentUserId()));if(g==null)notFound();return g;}
    private LearningPlanEntity ownedPlan(String publicId){LearningPlanEntity p=planMapper.selectOne(new LambdaQueryWrapper<LearningPlanEntity>().eq(LearningPlanEntity::getPublicId,publicId).eq(LearningPlanEntity::getUserId,SecurityUtils.currentUserId()));if(p==null)notFound();return p;}
    private PlanVersionEntity ownedVersion(String publicId){PlanVersionEntity v=versionMapper.selectOne(new LambdaQueryWrapper<PlanVersionEntity>().eq(PlanVersionEntity::getPublicId,publicId));if(v==null)notFound();LearningPlanEntity p=planMapper.selectById(v.getPlanId());if(p==null||!p.getUserId().equals(SecurityUtils.currentUserId()))notFound();return v;}
    private LearningTaskEntity ownedTask(String publicId){LearningTaskEntity t=taskMapper.selectOne(new LambdaQueryWrapper<LearningTaskEntity>().eq(LearningTaskEntity::getPublicId,publicId).eq(LearningTaskEntity::getUserId,SecurityUtils.currentUserId()));if(t==null)notFound();return t;}
    private Map<String,Object> taskAfter(RuleBasedPlanner.TaskDraft d,LearningGoalEntity goal,Long projectId,int sequenceNo){
        Map<String,Object>a=new LinkedHashMap<>();
        a.put("clientRef",d.clientRef()==null?"task-"+UUID.randomUUID():d.clientRef());
        a.put("title",d.title());a.put("description",d.learningObjective());a.put("taskType",d.taskType());
        a.put("priority",d.priority());a.put("scheduledStart",d.start().toString());a.put("dueAt",d.due().toString());
        a.put("estimatedMinutes",d.estimatedMinutes());a.put("lockedSchedule",false);a.put("goalId",goal.getPublicId());
        a.put("projectId",projectId==null?null:String.valueOf(projectId));a.put("knowledgePointIds",stringIds(d.knowledgePointIds()));
        a.put("knowledgeSources",d.knowledgeSources().stream().map(this::taskSourceSnapshot).toList());a.put("dependencyTaskIds",List.of());
        a.put("acceptanceCriteria",d.acceptance());a.put("milestoneId",d.milestoneId()==null?null:String.valueOf(d.milestoneId()));
        a.put("coveredGoalCriterionIds",d.coveredGoalCriterionIds());
        a.put("coveredMilestoneCriterionIds",d.coveredMilestoneCriterionIds());

        List<Map<String,Object>>manifest=new ArrayList<>();
        for(RuleBasedPlanner.TaskSource source:d.knowledgeSources()){
            Map<String,Object>item=new LinkedHashMap<>();
            item.put("sourceType","KNOWLEDGE_CHUNK");item.put("title",source.documentName());
            item.put("documentId",source.documentId());item.put("chunkId",String.valueOf(source.chunkId()));
            item.put("quotePreview",source.quotePreview());item.put("pageFrom",source.pageFrom());
            item.put("pageTo",source.pageTo());manifest.add(item);
        }
        manifest.addAll(catalogReferences(d.knowledgePointIds()));
        if(manifest.isEmpty()&&d.explorationRequired()){
            Map<String,Object>guide=new LinkedHashMap<>();
            guide.put("sourceType","UPLOAD_GUIDE");guide.put("title","该自定义方向尚无已核验资料");
            guide.put("quotePreview","请按建议检索词优先下载官方文档、开放教材或课程讲义，并上传到个人知识库；上传前的内容会明确标注为 AI 生成待核验。");
            manifest.add(guide);
        }
        String directionName=goal.getDirectionId()==null?goal.getCustomDirection():jdbc.query(
                "SELECT name FROM learning_direction WHERE id=?",
                rs->rs.next()?rs.getString(1):"当前学习方向",goal.getDirectionId());
        Map<String,Object>block=new LinkedHashMap<>();
        block.put("sequenceNo",sequenceNo);block.put("title",d.title());block.put("objective",d.learningObjective());
        block.put("directionName",directionName);block.put("explorationRequired",d.explorationRequired());
        block.put("sourceStatus",manifest.stream().anyMatch(item->"KNOWLEDGE_CHUNK".equals(item.get("sourceType"))
                ||"OFFICIAL_WEB".equals(item.get("sourceType"))||"OFFICIAL_DATA".equals(item.get("sourceType"))
                ||"CURATED_WEB".equals(item.get("sourceType")))?"READY":"NEEDS_UPLOAD");
        block.put("sourceManifest",manifest);block.put("sourceQueries",d.sourceQueries());
        a.put("learningBlock",block);
        return a;
    }

    private void writeSingleStage(PlanVersionEntity version,List<PlanChangeItemEntity>items){
        List<Map<String,Object>>scheduled=items.stream().filter(item->!"CANCEL_TASK".equals(item.getAction()))
                .map(item->map(item.getAfterJson())).filter(item->item.get("scheduledStart")!=null&&item.get("dueAt")!=null).toList();
        List<PlanStageEntity>sourceStages=stageMapper.selectList(new LambdaQueryWrapper<PlanStageEntity>()
                .eq(PlanStageEntity::getPlanVersionId,items.isEmpty()?version.getId():items.get(0).getPlanVersionId())
                .orderByAsc(PlanStageEntity::getSequenceNo));
        PlanStageEntity stage=new PlanStageEntity();stage.setPlanVersionId(version.getId());stage.setClientRef("stage-1");stage.setSequenceNo(1);
        if(!scheduled.isEmpty()){
            List<LocalDate>starts=scheduled.stream().map(item->ZonedDateTime.parse(String.valueOf(item.get("scheduledStart"))).toLocalDate()).sorted().toList();
            List<LocalDate>ends=scheduled.stream().map(item->ZonedDateTime.parse(String.valueOf(item.get("dueAt"))).toLocalDate()).sorted().toList();
            stage.setStartDate(starts.get(0));stage.setEndDate(ends.get(ends.size()-1));stage.setName("部分采纳后的学习阶段");stage.setOutcome("完成已选择的 "+items.size()+" 项计划变更");
        }else if(!sourceStages.isEmpty()){
            PlanStageEntity source=sourceStages.get(0);stage.setStartDate(source.getStartDate());stage.setEndDate(source.getEndDate());stage.setName(source.getName());stage.setOutcome(source.getOutcome());
        }else throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,"部分采纳结果缺少可生成阶段的日期");
        stageMapper.insert(stage);
    }
    private Map<String,Object> taskSnapshot(LearningTaskEntity task,ZoneId zone){Map<String,Object>a=new LinkedHashMap<>();a.put("title",task.getTitle());a.put("description",task.getDescription()==null?"":task.getDescription());a.put("taskType",task.getTaskType());a.put("priority",task.getPriority());a.put("scheduledStart",task.getScheduledStart().atZone(zone).toString());a.put("dueAt",task.getDueAt().atZone(zone).toString());a.put("estimatedMinutes",task.getEstimatedMinutes());a.put("lockedSchedule",task.getLockedSchedule());a.put("milestoneId",task.getMilestoneId()==null?null:String.valueOf(task.getMilestoneId()));a.put("knowledgePointIds",jdbc.query("SELECT knowledge_point_id FROM task_knowledge_point WHERE task_id=?",(rs,row)->String.valueOf(rs.getLong(1)),task.getId()));a.put("knowledgeSources",taskKnowledgeSources(task.getId()));a.put("acceptanceCriteria",readList(task.getAcceptanceJson()));Map<String,Object>coverage=taskCoverageMetadata(task.getId());a.put("coveredGoalCriterionIds",stringList(coverage.get("coveredGoalCriterionIds")));a.put("coveredMilestoneCriterionIds",stringList(coverage.get("coveredMilestoneCriterionIds")));a.put("dependencyTaskIds",List.of());return a;}

    static List<Long> parseKnowledgePointIds(Object raw) {
        if (!(raw instanceof List<?> values)) return List.of();
        List<Long> ids = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Number number) ids.add(number.longValue());
            else if (value instanceof String text && !text.isBlank()) ids.add(Long.parseLong(text));
            else throw new IllegalArgumentException("knowledgePointIds contains an invalid value");
        }
        return List.copyOf(ids);
    }

    private static List<String> stringIds(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> mapList(Object value){
        if(!(value instanceof List<?>items))return List.of();List<Map<String,Object>>result=new ArrayList<>();for(Object item:items)if(item instanceof Map<?,?>map)result.add(stringMap(map));return result;
    }
    private Map<String,Object> stringMap(Map<?,?>value){Map<String,Object>result=new LinkedHashMap<>();value.forEach((key,item)->result.put(String.valueOf(key),item));return result;}
    private Number number(Object value){if(value instanceof Number number)return number;if(value instanceof String text&&!text.isBlank())return new BigDecimal(text);throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"规划上下文缺少必要数值");}
    private BigDecimal decimal(Object value,BigDecimal fallback){if(value==null)return fallback;if(value instanceof BigDecimal decimal)return decimal;if(value instanceof Number number)return BigDecimal.valueOf(number.doubleValue());return new BigDecimal(String.valueOf(value));}
    private Integer nullableInt(Object value){return value==null?null:number(value).intValue();}
    private Long nullableLong(Object value){return value==null||String.valueOf(value).isBlank()?null:number(value).longValue();}
    private LocalDate date(Object value){
        if(value==null)return null;if(value instanceof LocalDate date)return date;
        if(value instanceof java.sql.Date date)return date.toLocalDate();
        String text=String.valueOf(value);return text.isBlank()||"null".equals(text)?null:LocalDate.parse(text);
    }
    private LocalDate maxDate(LocalDate...values){return Arrays.stream(values).filter(Objects::nonNull).max(LocalDate::compareTo).orElseThrow();}
    private LocalDate minDate(LocalDate...values){return Arrays.stream(values).filter(Objects::nonNull).min(LocalDate::compareTo).orElseThrow();}
    private Object parseJsonValue(Object value){
        if(value==null)return List.of();if(!(value instanceof String text))return value;
        try{return objectMapper.readValue(text,Object.class);}catch(Exception ignored){return text;}
    }
    private List<PythonAiServiceClient.PlanCriterion>criteria(String raw,String prefix){
        try{
            Object parsed=objectMapper.readValue(raw==null?"[]":raw,Object.class);
            if(!(parsed instanceof List<?>items))return List.of();List<PythonAiServiceClient.PlanCriterion>result=new ArrayList<>();
            for(int index=0;index<items.size();index++){
                Object item=items.get(index);String text;
                if(item instanceof Map<?,?>mapValue){Object rawText=mapValue.containsKey("description")
                        ?mapValue.get("description"):mapValue.get("text");text=Objects.toString(rawText,"");}
                else text=Objects.toString(item,"");
                if(!text.isBlank())result.add(new PythonAiServiceClient.PlanCriterion(prefix+(index+1),text));
            }
            return List.copyOf(result);
        }catch(Exception error){throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,"验收条件 JSON 格式无效");}
    }

    private KnowledgePlanContext knowledgePlanContext(List<String> requestedSpaceIds,long userId){
        List<String>publicIds=requestedSpaceIds==null?List.of():requestedSpaceIds.stream()
                .filter(Objects::nonNull).map(String::trim).filter(value->!value.isBlank()).distinct().limit(20).toList();
        if(publicIds.isEmpty())return new KnowledgePlanContext(List.of(),List.of(),List.of(),"",List.of());
        String placeholders=String.join(",",Collections.nCopies(publicIds.size(),"?"));
        List<Object>spaceArgs=new ArrayList<>(publicIds);spaceArgs.add(userId);
        List<Map<String,Object>>spaces=jdbc.query("""
                SELECT id,public_id,name FROM knowledge_space
                WHERE public_id IN (%s) AND status='ACTIVE' AND deleted_at IS NULL
                  AND (user_id=? OR visibility='PUBLIC')
                ORDER BY public_id
                """.formatted(placeholders),(rs,row)->Map.of(
                        "id",rs.getLong("id"),"publicId",rs.getString("public_id"),"name",rs.getString("name")),
                spaceArgs.toArray());
        if(spaces.size()!=publicIds.size())throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"部分知识空间不存在或无权访问");
        List<Long>spaceIds=spaces.stream().map(item->((Number)item.get("id")).longValue()).toList();
        String spacePlaceholders=String.join(",",Collections.nCopies(spaceIds.size(),"?"));
        List<Object>documentArgs=new ArrayList<>(spaceIds);documentArgs.add(userId);
        List<Map<String,Object>>documents=jdbc.query("""
                SELECT d.public_id,d.display_name,dv.id document_version_id,dv.text_hash,d.space_id
                FROM knowledge_document d
                JOIN document_version dv ON dv.document_id=d.id AND dv.version_no=d.active_version_no
                WHERE d.space_id IN (%s) AND d.status='INDEXED' AND dv.status='INDEXED'
                  AND d.deleted_at IS NULL AND (d.owner_user_id=? OR d.visibility='PUBLIC')
                ORDER BY d.public_id,dv.id
                """.formatted(spacePlaceholders),(rs,row)->{
            Map<String,Object>value=new LinkedHashMap<>();
            value.put("documentId",rs.getString("public_id"));value.put("documentName",rs.getString("display_name"));
            value.put("documentVersionId",rs.getLong("document_version_id"));value.put("textHash",rs.getString("text_hash"));
            value.put("spaceId",rs.getLong("space_id"));return value;
        },documentArgs.toArray());
        if(documents.isEmpty())throw new BusinessException(ErrorCode.RAG_EVIDENCE_INSUFFICIENT,"所选知识空间中没有已完成索引的文档");
        List<Long>versionIds=documents.stream().map(item->((Number)item.get("documentVersionId")).longValue()).toList();
        String fingerprint=hashing.sha256(json(documents));
        return new KnowledgePlanContext(spaceIds,versionIds,publicIds,fingerprint,documents);
    }

    private Map<Long,RuleBasedPlanner.TaskSource> resolvePlanSources(
            PythonAiServiceClient.PlanRecommendationResult result,KnowledgePlanContext context,long userId){
        List<Long>chunkIds=result.tasks().stream().flatMap(task->task.sourceChunkIds().stream()).distinct().toList();
        if(chunkIds.isEmpty())return Map.of();
        if(context.documentVersionIds().isEmpty())
            throw new BusinessException(ErrorCode.RAG_EVIDENCE_INSUFFICIENT,"AI 计划引用了未授权的知识资料");
        String chunkPlaceholders=String.join(",",Collections.nCopies(chunkIds.size(),"?"));
        String versionPlaceholders=String.join(",",Collections.nCopies(context.documentVersionIds().size(),"?"));
        List<Object>args=new ArrayList<>(chunkIds);args.addAll(context.documentVersionIds());args.add(userId);
        List<RuleBasedPlanner.TaskSource>sources=jdbc.query("""
                SELECT c.id,d.public_id,d.display_name,c.chunk_no,c.text,c.page_from,c.page_to
                FROM knowledge_chunk c
                JOIN document_version dv ON dv.id=c.document_version_id
                JOIN knowledge_document d ON d.id=dv.document_id AND d.active_version_no=dv.version_no
                WHERE c.id IN (%s) AND dv.id IN (%s)
                  AND d.status='INDEXED' AND dv.status='INDEXED' AND d.deleted_at IS NULL
                  AND (d.owner_user_id=? OR d.visibility='PUBLIC')
                ORDER BY c.id
                """.formatted(chunkPlaceholders,versionPlaceholders),(rs,row)->{
            String text=Optional.ofNullable(rs.getString("text")).orElse("").replaceAll("\\s+"," ").trim();
            if(text.length()>300)text=text.substring(0,300);
            return new RuleBasedPlanner.TaskSource(rs.getLong("id"),rs.getString("public_id"),
                    rs.getString("display_name"),rs.getInt("chunk_no"),text,
                    (Integer)rs.getObject("page_from"),(Integer)rs.getObject("page_to"));
        },args.toArray());
        if(sources.size()!=chunkIds.size())
            throw new BusinessException(ErrorCode.RAG_EVIDENCE_INSUFFICIENT,"AI 计划引用了无权限、已删除或过期的资料片段");
        return sources.stream().collect(java.util.stream.Collectors.toMap(
                RuleBasedPlanner.TaskSource::chunkId,source->source));
    }

    private List<Map<String,Object>> taskKnowledgeSources(long taskId){
        return jdbc.query("""
                SELECT c.id,d.public_id,d.display_name,c.chunk_no,c.text,c.page_from,c.page_to
                FROM task_knowledge_source source
                JOIN knowledge_chunk c ON c.id=source.chunk_id
                JOIN document_version dv ON dv.id=c.document_version_id
                JOIN knowledge_document d ON d.id=dv.document_id
                WHERE source.task_id=?
                ORDER BY d.display_name,c.chunk_no
                """,(rs,row)->{
            String text=Optional.ofNullable(rs.getString("text")).orElse("").replaceAll("\\s+"," ").trim();
            if(text.length()>300)text=text.substring(0,300);
            Map<String,Object>value=new LinkedHashMap<>();value.put("chunkId",String.valueOf(rs.getLong("id")));
            value.put("documentId",rs.getString("public_id"));value.put("documentName",rs.getString("display_name"));
            value.put("chunkNo",rs.getInt("chunk_no"));value.put("quotePreview",text);
            value.put("pageFrom",rs.getObject("page_from"));value.put("pageTo",rs.getObject("page_to"));return value;
        },taskId);
    }

    private List<String> stringList(Object value){
        if(!(value instanceof List<?>items))return List.of();
        return items.stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }

    private Map<String,Object>taskSourceSnapshot(RuleBasedPlanner.TaskSource source){
        Map<String,Object>value=new LinkedHashMap<>();value.put("chunkId",String.valueOf(source.chunkId()));
        value.put("documentId",source.documentId());value.put("documentName",source.documentName());
        value.put("chunkNo",source.chunkNo());value.put("quotePreview",source.quotePreview());
        value.put("pageFrom",source.pageFrom());value.put("pageTo",source.pageTo());return value;
    }

    private Map<String,Object>browserIdMap(Map<String,Object>source){
        Map<String,Object>value=new LinkedHashMap<>(source);
        for(String key:List.of("id","spaceId","documentVersionId","chunkId"))
            if(value.get(key) instanceof Number id)value.put(key,String.valueOf(id.longValue()));
        return value;
    }

    private Map<String,Object> summary(List<Map<String,Object>> after){int total=after.stream().mapToInt(a->((Number)a.get("estimatedMinutes")).intValue()).sum();List<LocalDate>dates=after.stream().map(a->ZonedDateTime.parse(String.valueOf(a.get("scheduledStart"))).toLocalDate()).sorted().toList();return Map.of("added",after.size(),"updated",0,"rescheduled",0,"canceled",0,"totalEstimatedMinutes",total,"affectedDateFrom",dates.get(0),"affectedDateTo",dates.get(dates.size()-1));}
    private Map<String,Object> summary(List<Map<String,Object>> after,List<String> actions){Map<String,Object>result=new LinkedHashMap<>(summary(after));result.put("added",actions.stream().filter("ADD_TASK"::equals).count());result.put("updated",actions.stream().filter("UPDATE_TASK"::equals).count());result.put("rescheduled",actions.stream().filter("RESCHEDULE_TASK"::equals).count());result.put("canceled",actions.stream().filter("CANCEL_TASK"::equals).count());return result;}
    private Map<String,Object> map(String value){try{return objectMapper.readValue(value,new TypeReference<>(){});}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private List<Object> readList(String value){try{return objectMapper.readValue(value,new TypeReference<>(){});}catch(Exception e){return List.of();}}
    private String json(Object value){try{return objectMapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private void notFound(){throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"资源不存在");}
}
