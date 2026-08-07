package com.adaptivelearning.planning.application;

import com.adaptivelearning.execution.domain.LearningTaskEntity;
import com.adaptivelearning.execution.infrastructure.LearningTaskMapper;
import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
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
            Map<String,Object> profile=profileContext(userId);
            @SuppressWarnings("unchecked") List<RuleBasedPlanner.Slot> slots=(List<RuleBasedPlanner.Slot>)profile.get("slots");
            @SuppressWarnings("unchecked") List<RuleBasedPlanner.DayException> exceptions=
                    (List<RuleBasedPlanner.DayException>)profile.get("exceptions");
            BigDecimal capacityRatio=(BigDecimal)profile.get("capacityRatio");
            LearningGoalEntity goal=goalMapper.selectById(job.getGoalId());
            if(goal==null)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"目标不存在或已删除");
            Long projectId=resolveProject(request.projectId(), goal.getId());
            List<Map<String,Object>> knowledge=goal.getDirectionId()==null?List.of():jdbc.query(
                    "SELECT id,name FROM knowledge_point WHERE direction_id=? AND status='ACTIVE' AND deleted_at IS NULL ORDER BY level,id",
                    (rs,row)->Map.of("id",rs.getLong(1),"name",rs.getString(2)),goal.getDirectionId());
            ZoneId zone=ZoneId.of((String)profile.get("timezone"));
            if(!pythonAi.isConfigured())throw new AiModelException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE);
            String directionName=goal.getDirectionId()==null?goal.getCustomDirection():jdbc.query(
                    "SELECT name FROM learning_direction WHERE id=? AND status='ACTIVE'",
                    rs->rs.next()?rs.getString(1):"当前学习方向",goal.getDirectionId());
            String currentStage=goal.getDirectionId()==null?jdbc.query("""
                            SELECT d.current_stage FROM user_profile_direction d
                            JOIN user_profile p ON p.id=d.profile_id
                            WHERE d.direction_id IS NULL AND d.custom_direction=? AND p.user_id=?
                              AND d.status='ACTIVE' AND d.deleted_at IS NULL
                            ORDER BY d.id DESC LIMIT 1
                            """,rs->rs.next()?rs.getString(1):"BEGINNER",goal.getCustomDirection(),userId)
                    :jdbc.query("SELECT d.current_stage FROM user_profile_direction d JOIN user_profile p ON p.id=d.profile_id WHERE d.direction_id=? AND p.user_id=? AND d.status='ACTIVE' AND d.deleted_at IS NULL ORDER BY d.id DESC LIMIT 1",
                    rs->rs.next()?rs.getString(1):"INTERMEDIATE",goal.getDirectionId(),userId);
            int weeklyMinutes=BigDecimal.valueOf(slots.stream().mapToInt(RuleBasedPlanner.Slot::minutes).sum())
                    .multiply(capacityRatio).intValue();
            int taskCount=Math.max(2,Math.min(10,knowledge.isEmpty()?6:Math.min(knowledge.size()*2,10)));
            KnowledgePlanContext knowledgeContext=knowledgePlanContext(request.knowledgeSpaceIds(),userId);
            PythonAiServiceClient.PlanRecommendationResult planResult=pythonAi.planRecommendations(
                    new PythonAiServiceClient.PlanRecommendationRequest(userId,goal.getName(),directionName,currentStage,
                            goal.getStartDate(),goal.getDueDate(),planningBackground(profile),
                            knowledge.stream().map(k->new PythonAiServiceClient.PlanKnowledgePoint(
                                    ((Number)k.get("id")).longValue(),String.valueOf(k.get("name")))).toList(),
                            knowledgeContext.spaceIds(),knowledgeContext.documentVersionIds(),12,
                            request.userRequirement(),weeklyMinutes,goal.getDirectionId()==null,taskCount));
            Map<Long,RuleBasedPlanner.TaskSource> planSources=resolvePlanSources(
                    planResult,knowledgeContext,userId);
            List<RuleBasedPlanner.TaskContent> contents=planResult.tasks().stream().map(t->new RuleBasedPlanner.TaskContent(
                    t.title(),t.taskType(),t.priority(),t.estimatedMinutes(),t.knowledgePointIds(),
                    t.sourceChunkIds().stream().map(planSources::get).filter(Objects::nonNull).toList(),
                    t.learningObjective(),t.sourceQueries(),goal.getDirectionId()==null,
                    t.acceptanceCriteria(),t.reason())).toList();
            List<RuleBasedPlanner.TaskDraft> drafts=ruleBasedPlanner.schedule(contents,goal.getStartDate(),
                    goal.getDueDate(),zone,slots,exceptions,capacityRatio);
            if(drafts.isEmpty())throw new BusinessException(ErrorCode.PLAN_CAPACITY_EXCEEDED,"目标周期太短，无法容纳任何候选任务");

            boolean optimization="OPTIMIZATION".equals(job.getJobType());
            List<LearningTaskEntity> currentTasks=optimization?taskMapper.selectList(new LambdaQueryWrapper<LearningTaskEntity>()
                    .eq(LearningTaskEntity::getGoalId,goal.getId())
                    .notIn(LearningTaskEntity::getLifecycleStatus,"COMPLETED","CANCELED")
                    .orderByAsc(LearningTaskEntity::getScheduledStart,LearningTaskEntity::getDueAt)):List.of();
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
            for(int i=drafts.size();i<currentTasks.size();i++){
                LearningTaskEntity task=currentTasks.get(i);afterList.add(taskSnapshot(task,zone));
                actions.add("CANCEL_TASK");targets.add(task.getId());
                reasons.add("优化后的学习路径不再需要该任务");
            }
            String proposalHash=hashing.sha256(json(afterList));
            // 写事务：计划行、版本、阶段、变更、校验、作业成功一次性提交，任一步失败整体回滚
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                LearningPlanEntity plan=findOrCreatePlan(goal,projectId);
                Integer currentNo=jdbc.query("SELECT pv.version_no FROM plan_publication pp JOIN plan_version pv ON pv.id=pp.plan_version_id WHERE pp.plan_id=?",
                        rs->rs.next()?rs.getInt(1):null,plan.getId());
                Integer maxNo=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0) FROM plan_version WHERE plan_id=?",Integer.class,plan.getId());
                PlanVersionEntity version=new PlanVersionEntity();version.setPublicId(UUID.randomUUID().toString());version.setPlanId(plan.getId());
                version.setVersionNo((maxNo==null?0:maxNo)+1);version.setBaseVersionNo(currentNo);version.setStatus("VALIDATING");
                version.setTriggerType(job.getJobType());version.setTriggerEventId(job.getPublicId());version.setProposalHash(proposalHash);
                version.setRiskLevel("MEDIUM");
                Map<String,Object> contextSnapshot=new LinkedHashMap<>();
                contextSnapshot.put("userId",userId);contextSnapshot.put("goalId",goal.getId());
                contextSnapshot.put("projectId",projectId);
                contextSnapshot.put("profileVersion",profile.get("profileVersion"));contextSnapshot.put("goalVersion",goal.getVersion());
                contextSnapshot.put("goalFingerprint",PlanValidationPolicy.goalFingerprint(goal));
                contextSnapshot.put("basePlanVersion",currentNo==null?0:currentNo);
                contextSnapshot.put("planningFingerprint",profile.get("planningFingerprint"));contextSnapshot.put("timezone",zone.getId());
                contextSnapshot.put("generatedAt",Instant.now());contextSnapshot.put("userRequirement",request.userRequirement()==null?"":request.userRequirement());
                contextSnapshot.put("knowledgeSpaceIds",knowledgeContext.publicSpaceIds());
                contextSnapshot.put("knowledgeFingerprint",knowledgeContext.fingerprint());
                contextSnapshot.put("knowledgeDocuments",knowledgeContext.documents());
                contextSnapshot.put("explorationMode",goal.getDirectionId()==null);
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
                    item.setAction(actions.get(i));item.setTargetTaskId(targets.get(i));item.setClientRef("change-"+(i+1));item.setAfterJson(json(afterList.get(i)));
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
        Long projectId = resolveProject(projectPublicId, goal.getId());
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
        Long projectId = resolveProject(projectPublicId, goal.getId());
        Map<String,Object> pub = jdbc.query("""
                SELECT pv.id AS version_id, pp.published_at AS published_at
                FROM learning_plan p
                JOIN plan_publication pp ON pp.plan_id = p.id
                JOIN plan_version pv ON pv.id = pp.plan_version_id
                WHERE p.goal_id=? AND p.user_id=? AND p.deleted_at IS NULL
                  AND pv.status='PUBLISHED'
                  AND (? IS NULL OR p.project_id=?)
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
    public VersionDetail validate(String publicId){PlanVersionEntity v=ownedVersion(publicId);LearningPlanEntity p=planMapper.selectById(v.getPlanId());
        LearningGoalEntity goal=goalMapper.selectById(p.getGoalId());validateInternal(v,goal,profileContext(SecurityUtils.currentUserId()));return version(publicId);}

    @Transactional
    public ConfirmationToken requestConfirmation(String publicId){
        PlanVersionEntity v=ownedVersion(publicId);requirePublishable(v);requireContextFresh(v);
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
        IdempotencyRecordEntity record=idempotency.find(userId,idempotencyKey,requestBody);
        if(record!=null){PlanVersionEntity done=ownedVersion(record.getResponseRef());return publicationResult(done);}
        PlanVersionEntity v=ownedVersion(publicId);requirePublishable(v);requireContextFresh(v);
        PlanConfirmationEntity c=confirmationMapper.selectOne(new LambdaQueryWrapper<PlanConfirmationEntity>()
                .eq(PlanConfirmationEntity::getPlanVersionId,v.getId()).eq(PlanConfirmationEntity::getUserId,userId)
                .eq(PlanConfirmationEntity::getTokenHash,hashing.sha256(confirmationToken==null?"":confirmationToken))
                .eq(PlanConfirmationEntity::getStatus,"PENDING"));
        if(c==null||c.getExpiresAt().isBefore(Instant.now())||!c.getProposalHash().equals(v.getProposalHash()))
            throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED,"确认令牌无效、已过期或提案已变化");
        LearningPlanEntity plan=planMapper.selectById(v.getPlanId());publicationMapper.lockCurrent(plan.getId());requireContextFresh(v);
        Long oldVersion=jdbc.query("SELECT plan_version_id FROM plan_publication WHERE plan_id=?",rs->rs.next()?rs.getLong(1):null,plan.getId());
        List<PlanChangeItemEntity> changes=changeMapper.selectList(new LambdaQueryWrapper<PlanChangeItemEntity>().eq(PlanChangeItemEntity::getPlanVersionId,v.getId())
                .eq(PlanChangeItemEntity::getItemStatus,"PROPOSED"));List<String> changed=new ArrayList<>();
        for(PlanChangeItemEntity item:changes){applyChange(item,plan,v,changed);item.setItemStatus("APPLIED");changeMapper.updateById(item);}
        rebuildTaskDependencies(plan.getGoalId());
        if(oldVersion!=null&&!oldVersion.equals(v.getId()))versionMapper.update(null,new LambdaUpdateWrapper<PlanVersionEntity>()
                .eq(PlanVersionEntity::getId,oldVersion).eq(PlanVersionEntity::getStatus,"PUBLISHED").set(PlanVersionEntity::getStatus,"SUPERSEDED"));
        v.setStatus("PUBLISHED");versionMapper.updateById(v);publicationMapper.upsert(plan.getId(),v.getId(),Instant.now());
        c.setStatus("CONFIRMED");c.setConfirmedAt(Instant.now());confirmationMapper.updateById(c);
        OutboxEventEntity event=new OutboxEventEntity();event.setAggregateType("LEARNING_PLAN");event.setAggregateId(plan.getPublicId());event.setEventType("PlanPublished");
        event.setPayloadJson(json(Map.of("planId",plan.getPublicId(),"versionId",v.getPublicId(),"changedTaskIds",changed)));
        event.setCorrelationId(idempotencyKey);event.setStatus("PENDING");event.setAttempts(0);event.setNextRetryAt(Instant.now());event.setCreatedAt(Instant.now());outboxMapper.insert(event);
        audit.record("PLAN_PUBLISH","PLAN_VERSION",v.getPublicId(),oldVersion==null?null:oldVersion.toString(),"tasks="+changed.size(),"SUCCESS");
        idempotency.save(userId,idempotencyKey,requestBody,v.getPublicId());return new PublicationResult(plan.getPublicId(),v.getPublicId(),v.getVersionNo(),changed,"PUBLISHED");
    }

    @Transactional
    public VersionDetail partialSelection(String publicId,List<String> selectedIds){
        PlanVersionEntity source=ownedVersion(publicId);if(selectedIds==null||selectedIds.isEmpty())throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,"至少选择一个变更项");
        List<PlanChangeItemEntity> all=changeMapper.selectList(new LambdaQueryWrapper<PlanChangeItemEntity>().eq(PlanChangeItemEntity::getPlanVersionId,source.getId()));
        List<PlanChangeItemEntity> selected=all.stream().filter(x->selectedIds.contains(x.getPublicId())).toList();if(selected.size()!=selectedIds.size())notFound();
        Integer max=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0) FROM plan_version WHERE plan_id=?",Integer.class,source.getPlanId());
        PlanVersionEntity copy=new PlanVersionEntity();copy.setPublicId(UUID.randomUUID().toString());copy.setPlanId(source.getPlanId());copy.setVersionNo(max+1);
        copy.setBaseVersionNo(source.getBaseVersionNo());copy.setStatus("VALIDATING");copy.setTriggerType("PARTIAL_SELECTION");copy.setTriggerEventId(UUID.randomUUID().toString());
        copy.setContextSnapshotJson(source.getContextSnapshotJson());copy.setRiskLevel(source.getRiskLevel());copy.setSummaryJson(json(summary(selected.stream().map(x->map(x.getAfterJson())).toList())));
        copy.setProposalHash(hashing.sha256(json(selected.stream().map(PlanChangeItemEntity::getAfterJson).toList())));versionMapper.insert(copy);
        for(PlanChangeItemEntity old:selected){PlanChangeItemEntity n=new PlanChangeItemEntity();n.setPublicId(UUID.randomUUID().toString());n.setPlanVersionId(copy.getId());n.setAction(old.getAction());n.setTargetTaskId(old.getTargetTaskId());
            n.setClientRef(old.getClientRef());n.setBeforeJson(old.getBeforeJson());n.setAfterJson(old.getAfterJson());n.setReason(old.getReason());n.setRiskLevel(old.getRiskLevel());n.setConfirmRequired(true);n.setItemStatus("PROPOSED");changeMapper.insert(n);}
        source.setStatus("REJECTED");versionMapper.updateById(source);LearningPlanEntity p=planMapper.selectById(copy.getPlanId());validateInternal(copy,goalMapper.selectById(p.getGoalId()),profileContext(SecurityUtils.currentUserId()));return version(copy.getPublicId());
    }

    public void reject(String publicId,String reason){PlanVersionEntity v=ownedVersion(publicId);if(!Set.of("DRAFT","VALIDATION_FAILED","PENDING_CONFIRMATION").contains(v.getStatus()))
        throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"当前提案不能拒绝");v.setStatus("REJECTED");versionMapper.updateById(v);audit.record("PLAN_REJECT","PLAN_VERSION",publicId,null,reason,"SUCCESS");}

    @Transactional
    public VersionDetail rescheduleProposal(String taskPublicId,ZonedDateTime newStart,ZonedDateTime newDue,String reason){
        LearningTaskEntity task=ownedTask(taskPublicId);if(Set.of("COMPLETED","CANCELED").contains(task.getLifecycleStatus()))throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"已完成或取消任务不能重新排期");
        LearningGoalEntity goal=goalMapper.selectById(task.getGoalId());LearningPlanEntity plan=findOrCreatePlan(goal,task.getProjectId());
        Integer max=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0) FROM plan_version WHERE plan_id=?",Integer.class,plan.getId());
        Map<String,Object> after=new LinkedHashMap<>();after.put("title",task.getTitle());after.put("scheduledStart",newStart.toString());after.put("dueAt",newDue.toString());
        after.put("estimatedMinutes",task.getEstimatedMinutes());after.put("taskType",task.getTaskType());after.put("priority",task.getPriority());after.put("acceptanceCriteria",readList(task.getAcceptanceJson()));
        PlanVersionEntity v=new PlanVersionEntity();v.setPublicId(UUID.randomUUID().toString());v.setPlanId(plan.getId());v.setVersionNo(max+1);v.setStatus("VALIDATING");v.setTriggerType("RESCHEDULE");v.setTriggerEventId(UUID.randomUUID().toString());
        Map<String,Object> profile=profileContext(task.getUserId());v.setContextSnapshotJson(json(Map.of("userId",task.getUserId(),"goalId",goal.getId(),"profileVersion",profile.get("profileVersion"),"goalVersion",goal.getVersion(),"planningFingerprint",profile.get("planningFingerprint"),"timezone",profile.get("timezone"))));
        v.setProposalHash(hashing.sha256(json(after)));v.setRiskLevel(newDue.toLocalDate().isAfter(goal.getDueDate())?"HIGH":"LOW");v.setSummaryJson(json(Map.of("rescheduled",1,"affectedDateFrom",newStart.toLocalDate(),"affectedDateTo",newDue.toLocalDate())));versionMapper.insert(v);
        PlanChangeItemEntity item=new PlanChangeItemEntity();item.setPublicId(UUID.randomUUID().toString());item.setPlanVersionId(v.getId());item.setAction("RESCHEDULE_TASK");item.setTargetTaskId(task.getId());item.setClientRef("reschedule-1");
        item.setBeforeJson(json(Map.of("scheduledStart",task.getScheduledStart(),"dueAt",task.getDueAt())));item.setAfterJson(json(after));item.setReason(reason);item.setRiskLevel(v.getRiskLevel());item.setConfirmRequired(true);item.setItemStatus("PROPOSED");changeMapper.insert(item);
        validateInternal(v,goal,profile);return version(v.getPublicId());
    }

    private void validateInternal(PlanVersionEntity v,LearningGoalEntity goal,Map<String,Object> profile){
        validationMapper.delete(new LambdaQueryWrapper<PlanValidationResultEntity>().eq(PlanValidationResultEntity::getPlanVersionId,v.getId()));
        List<PlanChangeItemEntity> items=changeMapper.selectList(new LambdaQueryWrapper<PlanChangeItemEntity>().eq(PlanChangeItemEntity::getPlanVersionId,v.getId()));
        List<PlanValidationPolicy.Change> changes=new ArrayList<>();
        for(PlanChangeItemEntity item:items){Map<String,Object> a=map(item.getAfterJson());changes.add(new PlanValidationPolicy.Change(item.getAction(),String.valueOf(a.get("title")),
                ZonedDateTime.parse(String.valueOf(a.get("scheduledStart"))),ZonedDateTime.parse(String.valueOf(a.get("dueAt"))),((Number)a.get("estimatedMinutes")).intValue(),
                Boolean.TRUE.equals(a.get("lockedSchedule")),Boolean.TRUE.equals(item.getConfirmRequired())));}
        List<PlanValidationPolicy.Issue> issues=PlanValidationPolicy.validate(changes,goal.getStartDate(),goal.getDueDate());
        for(var issue:issues){PlanValidationResultEntity e=new PlanValidationResultEntity();e.setPlanVersionId(v.getId());e.setValidatorCode(issue.code());e.setSeverity(issue.severity());e.setFieldPath(issue.fieldPath());e.setMessage(issue.message());e.setDetailsJson(json(issue.details()));e.setCreatedAt(Instant.now());validationMapper.insert(e);}
        v.setStatus(issues.stream().anyMatch(i->"ERROR".equals(i.severity()))?"VALIDATION_FAILED":"PENDING_CONFIRMATION");versionMapper.updateById(v);
    }

    private Map<String,Object> profileContext(long userId){
        Map<String,Object> p=jdbc.query("SELECT p.id,p.timezone,p.version,p.current_version_no,p.background_text FROM user_profile p WHERE p.user_id=? AND p.deleted_at IS NULL",
                rs->{if(!rs.next())return null;Map<String,Object>m=new HashMap<>();m.put("profileVersion",rs.getInt("version"));m.put("profileSnapshotVersion",rs.getInt("current_version_no"));m.put("timezone",rs.getString("timezone"));m.put("backgroundText",rs.getString("background_text"));return m;},userId);
        if(p==null)throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,"画像最小字段尚未完成");
        List<RuleBasedPlanner.Slot> slots=jdbc.query("SELECT weekday,start_time,available_minutes FROM availability_rule WHERE user_id=? AND deleted_at IS NULL ORDER BY weekday,start_time",
                (rs,row)->new RuleBasedPlanner.Slot(rs.getInt(1),rs.getTime(2).toLocalTime(),rs.getInt(3)),userId);p.put("slots",slots);
        List<RuleBasedPlanner.DayException> exceptions=jdbc.query("SELECT local_date,available_minutes FROM availability_exception WHERE user_id=? AND deleted_at IS NULL ORDER BY local_date",
                (rs,row)->new RuleBasedPlanner.DayException(rs.getDate(1).toLocalDate(),rs.getInt(2)),userId);
        p.put("exceptions",exceptions);
        BigDecimal capacityRatio=jdbc.query("SELECT capacity_ratio FROM learning_preference WHERE user_id=? AND deleted_at IS NULL",
                rs->rs.next()?rs.getBigDecimal(1):new BigDecimal("0.85"),userId);
        if(capacityRatio==null)capacityRatio=new BigDecimal("0.85");
        p.put("capacityRatio",capacityRatio);
        Map<String,Object> signals=new LinkedHashMap<>();
        signals.put("masteryAverage",Objects.requireNonNullElse(jdbc.queryForObject("SELECT COALESCE(AVG(score),0) FROM knowledge_mastery WHERE user_id=?",BigDecimal.class,userId),BigDecimal.ZERO));
        signals.put("lowMasteryCount",Objects.requireNonNullElse(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_mastery WHERE user_id=? AND score<60",Long.class,userId),0L));
        signals.put("recentCompleted",Objects.requireNonNullElse(jdbc.queryForObject("SELECT COUNT(*) FROM learning_task WHERE user_id=? AND lifecycle_status='COMPLETED' AND completed_at>=? AND deleted_at IS NULL",Long.class,userId,Instant.now().minus(Duration.ofDays(14))),0L));
        signals.put("recentOverdue",Objects.requireNonNullElse(jdbc.queryForObject("SELECT COUNT(*) FROM learning_task WHERE user_id=? AND lifecycle_status NOT IN ('COMPLETED','CANCELED') AND due_at<? AND due_at>=? AND deleted_at IS NULL",Long.class,userId,Instant.now(),Instant.now().minus(Duration.ofDays(14))),0L));
        p.put("learningSignals",signals);
        p.put("planningFingerprint",hashing.sha256(json(Map.of("timezone",p.get("timezone"),"slots",slots,
                "exceptions",exceptions,"capacityRatio",capacityRatio,"learningSignals",signals))));return p;
    }

    private String planningBackground(Map<String,Object> profile){
        String background=String.valueOf(profile.getOrDefault("backgroundText",""));
        return background+"\n近期学习反馈："+json(profile.getOrDefault("learningSignals",Map.of()));
    }

    private void applyChange(PlanChangeItemEntity item,LearningPlanEntity plan,PlanVersionEntity version,List<String> changed){
        Map<String,Object>a=map(item.getAfterJson());
        if("ADD_TASK".equals(item.getAction())){
            LearningTaskEntity t=new LearningTaskEntity();t.setPublicId(UUID.randomUUID().toString());t.setUserId(plan.getUserId());t.setGoalId(plan.getGoalId());t.setProjectId(plan.getProjectId());t.setOriginPlanVersionId(version.getId());
            t.setTitle(String.valueOf(a.get("title")));t.setDescription(String.valueOf(a.getOrDefault("description","")));t.setTaskType(String.valueOf(a.get("taskType")));t.setPriority(String.valueOf(a.get("priority")));t.setEstimatedMinutes(((Number)a.get("estimatedMinutes")).intValue());
            t.setScheduledStart(ZonedDateTime.parse(String.valueOf(a.get("scheduledStart"))).toInstant());t.setDueAt(ZonedDateTime.parse(String.valueOf(a.get("dueAt"))).toInstant());t.setLockedSchedule(Boolean.TRUE.equals(a.get("lockedSchedule")));t.setLifecycleStatus("NOT_STARTED");t.setProgressPercent(BigDecimal.ZERO);t.setRescheduleCount(0);t.setAcceptanceJson(json(a.getOrDefault("acceptanceCriteria",List.of())));taskMapper.insert(t);changed.add(t.getPublicId());
            @SuppressWarnings("unchecked")List<Number>kp=(List<Number>)a.getOrDefault("knowledgePointIds",List.of());for(Number id:kp)jdbc.update("INSERT INTO task_knowledge_point(task_id,knowledge_point_id,weight) VALUES(?,?,?)",t.getId(),id.longValue(),BigDecimal.ONE);
            writeTaskKnowledgeSources(t.getId(),a);
            createLearningBlock(a,t,plan,version);
        }else{
            LearningTaskEntity t=taskMapper.selectById(item.getTargetTaskId());if(t==null||!t.getUserId().equals(plan.getUserId()))notFound();if("COMPLETED".equals(t.getLifecycleStatus()))throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"已完成任务不能由新计划覆盖");
            if("RESCHEDULE_TASK".equals(item.getAction())){
                Instant oldStart=t.getScheduledStart(),oldDue=t.getDueAt();t.setTitle(String.valueOf(a.getOrDefault("title",t.getTitle())));t.setDescription(String.valueOf(a.getOrDefault("description",t.getDescription()==null?"":t.getDescription())));t.setTaskType(String.valueOf(a.getOrDefault("taskType",t.getTaskType())));t.setPriority(String.valueOf(a.getOrDefault("priority",t.getPriority())));t.setEstimatedMinutes(((Number)a.getOrDefault("estimatedMinutes",t.getEstimatedMinutes())).intValue());t.setAcceptanceJson(json(a.getOrDefault("acceptanceCriteria",readList(t.getAcceptanceJson()))));t.setScheduledStart(ZonedDateTime.parse(String.valueOf(a.get("scheduledStart"))).toInstant());t.setDueAt(ZonedDateTime.parse(String.valueOf(a.get("dueAt"))).toInstant());t.setRescheduleCount(t.getRescheduleCount()+1);taskMapper.updateById(t);jdbc.update("DELETE FROM task_knowledge_point WHERE task_id=?",t.getId());@SuppressWarnings("unchecked")List<Number>kp=(List<Number>)a.getOrDefault("knowledgePointIds",List.of());for(Number id:kp)jdbc.update("INSERT INTO task_knowledge_point(task_id,knowledge_point_id,weight) VALUES(?,?,?)",t.getId(),id.longValue(),BigDecimal.ONE);writeTaskKnowledgeSources(t.getId(),a);jdbc.update("INSERT INTO task_schedule_history(id,task_id,old_start,old_due,new_start,new_due,reason,source_plan_version_id,created_at) VALUES(?,?,?,?,?,?,?,?,?)",IdWorker.getId(),t.getId(),oldStart,oldDue,t.getScheduledStart(),t.getDueAt(),item.getReason(),version.getId(),Instant.now());
            }else if("CANCEL_TASK".equals(item.getAction())){t.setLifecycleStatus("CANCELED");taskMapper.updateById(t);}else{t.setTitle(String.valueOf(a.getOrDefault("title",t.getTitle())));taskMapper.updateById(t);}
            changed.add(t.getPublicId());
        }
    }

    private void writeTaskKnowledgeSources(long taskId,Map<String,Object> after){
        jdbc.update("DELETE FROM task_knowledge_source WHERE task_id=?",taskId);
        Object raw=after.get("knowledgeSources");
        if(!(raw instanceof List<?> sources))return;
        for(Object source:sources){
            if(!(source instanceof Map<?,?> value)||!(value.get("chunkId") instanceof Number chunkId))continue;
            jdbc.update("INSERT INTO task_knowledge_source(task_id,chunk_id,created_at) VALUES(?,?,?)",
                    taskId,chunkId.longValue(),Instant.now());
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

    private void rebuildTaskDependencies(long goalId) {
        jdbc.update("""
                DELETE d FROM task_dependency d
                JOIN learning_task successor ON successor.id=d.successor_task_id
                WHERE successor.goal_id=?
                """, goalId);
        List<Long> taskIds = jdbc.query("""
                SELECT id FROM learning_task
                WHERE goal_id=? AND lifecycle_status<>'CANCELED' AND deleted_at IS NULL
                ORDER BY scheduled_start,due_at,id
                """, (rs, row) -> rs.getLong(1), goalId);
        for (int i = 1; i < taskIds.size(); i++) {
            jdbc.update("INSERT INTO task_dependency(predecessor_task_id,successor_task_id) VALUES(?,?)",
                    taskIds.get(i - 1), taskIds.get(i));
        }
    }

    private void requirePublishable(PlanVersionEntity v){if(!"PENDING_CONFIRMATION".equals(v.getStatus()))throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED,"提案尚未通过校验或已不在待确认状态");long errors=validationMapper.selectCount(new LambdaQueryWrapper<PlanValidationResultEntity>().eq(PlanValidationResultEntity::getPlanVersionId,v.getId()).eq(PlanValidationResultEntity::getSeverity,"ERROR"));if(errors>0)throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED,"提案仍有校验错误");}
    private void requireContextFresh(PlanVersionEntity v){
        Map<String,Object>c=map(v.getContextSnapshotJson());long userId=SecurityUtils.currentUserId();long goalId=((Number)c.get("goalId")).longValue();int expected=((Number)c.get("goalVersion")).intValue();LearningGoalEntity g=goalMapper.selectById(goalId);if(g==null||PlanValidationPolicy.goalContextStale((String)c.get("goalFingerprint"),g,expected,g.getVersion()))throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"目标在预览后已发生变化");Map<String,Object>current=profileContext(userId);if(((Number)c.get("profileVersion")).intValue()!=((Number)current.get("profileVersion")).intValue()||!Objects.equals(c.get("planningFingerprint"),current.get("planningFingerprint")))throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"画像在预览后已发生变化");
        List<String>spaceIds=stringList(c.get("knowledgeSpaceIds"));
        if(!spaceIds.isEmpty()){
            KnowledgePlanContext knowledge=knowledgePlanContext(spaceIds,userId);
            if(!Objects.equals(c.get("knowledgeFingerprint"),knowledge.fingerprint()))
                throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"计划引用的知识库资料在预览后已发生变化，请重新生成");
        }
    }
    private PublicationResult publicationResult(PlanVersionEntity v){LearningPlanEntity p=planMapper.selectById(v.getPlanId());List<String>tasks=taskMapper.selectList(new LambdaQueryWrapper<LearningTaskEntity>().eq(LearningTaskEntity::getOriginPlanVersionId,v.getId())).stream().map(LearningTaskEntity::getPublicId).toList();return new PublicationResult(p.getPublicId(),v.getPublicId(),v.getVersionNo(),tasks,v.getStatus());}
    private LearningPlanEntity findOrCreatePlan(LearningGoalEntity goal,Long projectId){LearningPlanEntity p=planMapper.selectOne(new LambdaQueryWrapper<LearningPlanEntity>().eq(LearningPlanEntity::getGoalId,goal.getId()).eq(projectId!=null,LearningPlanEntity::getProjectId,projectId).isNull(projectId==null,LearningPlanEntity::getProjectId));if(p==null){p=new LearningPlanEntity();p.setPublicId(UUID.randomUUID().toString());p.setUserId(goal.getUserId());p.setGoalId(goal.getId());p.setProjectId(projectId);p.setName(goal.getName()+"学习计划");p.setStatus("ACTIVE");planMapper.insert(p);}return p;}
    private Long resolveProject(String publicId,long goalId){if(publicId==null||publicId.isBlank())return null;List<Long>ids=jdbc.query("SELECT project.id FROM learning_project project JOIN goal_project link ON link.project_id=project.id WHERE project.public_id=? AND project.user_id=? AND link.goal_id=? AND project.deleted_at IS NULL AND project.status NOT IN ('COMPLETED','CANCELED','ARCHIVED')",(rs,row)->rs.getLong(1),publicId,SecurityUtils.currentUserId(),goalId);if(ids.isEmpty())throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,"项目不存在、已结束或未关联当前目标");return ids.get(0);}
    private LearningGoalEntity ownedGoal(String publicId){LearningGoalEntity g=goalMapper.selectOne(new LambdaQueryWrapper<LearningGoalEntity>().eq(LearningGoalEntity::getPublicId,publicId).eq(LearningGoalEntity::getUserId,SecurityUtils.currentUserId()));if(g==null)notFound();return g;}
    private LearningPlanEntity ownedPlan(String publicId){LearningPlanEntity p=planMapper.selectOne(new LambdaQueryWrapper<LearningPlanEntity>().eq(LearningPlanEntity::getPublicId,publicId).eq(LearningPlanEntity::getUserId,SecurityUtils.currentUserId()));if(p==null)notFound();return p;}
    private PlanVersionEntity ownedVersion(String publicId){PlanVersionEntity v=versionMapper.selectOne(new LambdaQueryWrapper<PlanVersionEntity>().eq(PlanVersionEntity::getPublicId,publicId));if(v==null)notFound();LearningPlanEntity p=planMapper.selectById(v.getPlanId());if(p==null||!p.getUserId().equals(SecurityUtils.currentUserId()))notFound();return v;}
    private LearningTaskEntity ownedTask(String publicId){LearningTaskEntity t=taskMapper.selectOne(new LambdaQueryWrapper<LearningTaskEntity>().eq(LearningTaskEntity::getPublicId,publicId).eq(LearningTaskEntity::getUserId,SecurityUtils.currentUserId()));if(t==null)notFound();return t;}
    private Map<String,Object> taskAfter(RuleBasedPlanner.TaskDraft d,LearningGoalEntity goal,Long projectId,int sequenceNo){
        Map<String,Object>a=new LinkedHashMap<>();
        a.put("title",d.title());a.put("description",d.learningObjective());a.put("taskType",d.taskType());
        a.put("priority",d.priority());a.put("scheduledStart",d.start().toString());a.put("dueAt",d.due().toString());
        a.put("estimatedMinutes",d.estimatedMinutes());a.put("lockedSchedule",false);a.put("goalId",goal.getPublicId());
        a.put("projectId",projectId);a.put("knowledgePointIds",d.knowledgePointIds());
        a.put("knowledgeSources",d.knowledgeSources());a.put("dependencyTaskIds",List.of());
        a.put("acceptanceCriteria",d.acceptance());

        List<Map<String,Object>>manifest=new ArrayList<>();
        for(RuleBasedPlanner.TaskSource source:d.knowledgeSources()){
            Map<String,Object>item=new LinkedHashMap<>();
            item.put("sourceType","KNOWLEDGE_CHUNK");item.put("title",source.documentName());
            item.put("documentId",source.documentId());item.put("chunkId",source.chunkId());
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
    private Map<String,Object> taskSnapshot(LearningTaskEntity task,ZoneId zone){Map<String,Object>a=new LinkedHashMap<>();a.put("title",task.getTitle());a.put("description",task.getDescription()==null?"":task.getDescription());a.put("taskType",task.getTaskType());a.put("priority",task.getPriority());a.put("scheduledStart",task.getScheduledStart().atZone(zone).toString());a.put("dueAt",task.getDueAt().atZone(zone).toString());a.put("estimatedMinutes",task.getEstimatedMinutes());a.put("lockedSchedule",task.getLockedSchedule());a.put("knowledgePointIds",jdbc.query("SELECT knowledge_point_id FROM task_knowledge_point WHERE task_id=?",(rs,row)->rs.getLong(1),task.getId()));a.put("knowledgeSources",taskKnowledgeSources(task.getId()));a.put("acceptanceCriteria",readList(task.getAcceptanceJson()));return a;}

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
            Map<String,Object>value=new LinkedHashMap<>();value.put("chunkId",rs.getLong("id"));
            value.put("documentId",rs.getString("public_id"));value.put("documentName",rs.getString("display_name"));
            value.put("chunkNo",rs.getInt("chunk_no"));value.put("quotePreview",text);
            value.put("pageFrom",rs.getObject("page_from"));value.put("pageTo",rs.getObject("page_to"));return value;
        },taskId);
    }

    private List<String> stringList(Object value){
        if(!(value instanceof List<?>items))return List.of();
        return items.stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }

    private Map<String,Object> summary(List<Map<String,Object>> after){int total=after.stream().mapToInt(a->((Number)a.get("estimatedMinutes")).intValue()).sum();List<LocalDate>dates=after.stream().map(a->ZonedDateTime.parse(String.valueOf(a.get("scheduledStart"))).toLocalDate()).sorted().toList();return Map.of("added",after.size(),"updated",0,"rescheduled",0,"canceled",0,"totalEstimatedMinutes",total,"affectedDateFrom",dates.get(0),"affectedDateTo",dates.get(dates.size()-1));}
    private Map<String,Object> summary(List<Map<String,Object>> after,List<String> actions){Map<String,Object>result=new LinkedHashMap<>(summary(after));result.put("added",actions.stream().filter("ADD_TASK"::equals).count());result.put("updated",actions.stream().filter("UPDATE_TASK"::equals).count());result.put("rescheduled",actions.stream().filter("RESCHEDULE_TASK"::equals).count());result.put("canceled",actions.stream().filter("CANCEL_TASK"::equals).count());return result;}
    private Map<String,Object> map(String value){try{return objectMapper.readValue(value,new TypeReference<>(){});}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private List<Object> readList(String value){try{return objectMapper.readValue(value,new TypeReference<>(){});}catch(Exception e){return List.of();}}
    private String json(Object value){try{return objectMapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private void notFound(){throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"资源不存在");}
}
