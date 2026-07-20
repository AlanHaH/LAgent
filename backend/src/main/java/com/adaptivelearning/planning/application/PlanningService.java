package com.adaptivelearning.planning.application;

import com.adaptivelearning.execution.domain.LearningTaskEntity;
import com.adaptivelearning.execution.infrastructure.LearningTaskMapper;
import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.GoalMapper;
import com.adaptivelearning.planning.domain.*;
import com.adaptivelearning.planning.infrastructure.PlanningMappers.*;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.support.application.AuditService;
import com.adaptivelearning.support.application.HashingService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

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
    private final HashingService hashing;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.planning.capacity-ratio:0.85}") private double defaultCapacityRatio;
    @Value("${app.planning.confirmation-minutes:30}") private long confirmationMinutes;

    public record JobRequest(String type,String projectId,String userRequirement){}
    public record JobView(String publicId,String jobType,String status,String planId,String planVersionId,
                          String errorCode,String errorMessage,Instant startedAt,Instant finishedAt){}
    public record ConfirmationToken(String token,Instant expiresAt,String proposalHash){}
    public record PublicationResult(String planId,String versionId,int versionNo,List<String> changedTaskIds,String status){}
    public record VersionDetail(PlanVersionEntity version,List<PlanStageEntity> stages,
                                List<PlanChangeItemEntity> changes,List<PlanValidationResultEntity> validation){}
    public record PlanDetail(LearningPlanEntity plan,PlanVersionEntity currentVersion){}

    @Transactional
    public PlanningJobEntity createJob(String goalPublicId,JobRequest request,String idempotencyKey){
        if(idempotencyKey==null||idempotencyKey.isBlank())throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,"缺少 Idempotency-Key");
        long userId=SecurityUtils.currentUserId();String requestJson=json(request);
        PlanningJobEntity existing=jobMapper.selectOne(new LambdaQueryWrapper<PlanningJobEntity>()
                .eq(PlanningJobEntity::getUserId,userId).eq(PlanningJobEntity::getIdempotencyKey,idempotencyKey));
        if(existing!=null){if(!existing.getRequestHash().equals(hashing.sha256(requestJson)))throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,"同一幂等键不能用于不同规划请求");return existing;}
        LearningGoalEntity goal=ownedGoal(goalPublicId);
        if(!"ACTIVE".equals(goal.getStatus()))throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"只有活动目标可以发起规划");
        long running=jobMapper.selectCount(new LambdaQueryWrapper<PlanningJobEntity>().eq(PlanningJobEntity::getGoalId,goal.getId())
                .in(PlanningJobEntity::getStatus,"QUEUED","RUNNING"));
        if(running>0)throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT,"该目标已有运行中的规划作业");
        PlanningJobEntity job=new PlanningJobEntity();job.setPublicId(UUID.randomUUID().toString());job.setUserId(userId);job.setGoalId(goal.getId());
        job.setJobType(request.type()==null?"INITIAL":request.type());job.setStatus("RUNNING");job.setIdempotencyKey(idempotencyKey);
        job.setRequestHash(hashing.sha256(requestJson));job.setStartedAt(Instant.now());jobMapper.insert(job);

        Map<String,Object> profile=profileContext(userId);
        @SuppressWarnings("unchecked") List<RuleBasedPlanner.Slot> slots=(List<RuleBasedPlanner.Slot>)profile.get("slots");
        if(slots.isEmpty())throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,"未来没有可用学习时段，不能启动规划");
        Long projectId=resolveProject(request.projectId());
        LearningPlanEntity plan=findOrCreatePlan(goal,projectId);
        Integer currentNo=jdbc.query("SELECT pv.version_no FROM plan_publication pp JOIN plan_version pv ON pv.id=pp.plan_version_id WHERE pp.plan_id=?",
                rs->rs.next()?rs.getInt(1):null,plan.getId());
        Integer maxNo=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0) FROM plan_version WHERE plan_id=?",Integer.class,plan.getId());
        List<Map<String,Object>> knowledge=jdbc.query("SELECT id,name FROM knowledge_point WHERE direction_id=? AND status='ACTIVE' ORDER BY level,id",
                (rs,row)->Map.of("id",rs.getLong(1),"name",rs.getString(2)),goal.getDirectionId());
        @SuppressWarnings("unchecked") Map<LocalDate,Integer> exceptions=(Map<LocalDate,Integer>)profile.get("exceptions");
        ZoneId zone=ZoneId.of((String)profile.get("timezone"));double capacity=((BigDecimal)profile.get("capacityRatio")).doubleValue();
        List<RuleBasedPlanner.TaskDraft> drafts=ruleBasedPlanner.create(goal.getStartDate(),goal.getDueDate(),zone,slots,exceptions,capacity,knowledge,goal.getName());
        if(drafts.isEmpty())throw new BusinessException(ErrorCode.PLAN_CAPACITY_EXCEEDED,"目标周期内没有可容纳最小任务的时段");

        List<Map<String,Object>> afterList=new ArrayList<>();
        for(RuleBasedPlanner.TaskDraft d:drafts)afterList.add(taskAfter(d,goal,projectId));
        String proposalHash=hashing.sha256(json(afterList));
        PlanVersionEntity version=new PlanVersionEntity();version.setPublicId(UUID.randomUUID().toString());version.setPlanId(plan.getId());
        version.setVersionNo((maxNo==null?0:maxNo)+1);version.setBaseVersionNo(currentNo);version.setStatus("VALIDATING");
        version.setTriggerType(job.getJobType());version.setTriggerEventId(job.getPublicId());version.setProposalHash(proposalHash);
        version.setRiskLevel("MEDIUM");version.setContextSnapshotJson(json(Map.of("userId",userId,"goalId",goal.getId(),
                "profileVersion",profile.get("profileVersion"),"goalVersion",goal.getVersion(),"basePlanVersion",currentNo==null?0:currentNo,
                "planningFingerprint",profile.get("planningFingerprint"),"timezone",zone.getId(),"generatedAt",Instant.now(),"userRequirement",request.userRequirement()==null?"":request.userRequirement())));
        version.setSummaryJson(json(summary(afterList)));versionMapper.insert(version);
        PlanStageEntity stage=new PlanStageEntity();stage.setPlanVersionId(version.getId());stage.setClientRef("stage-1");stage.setName("目标主线学习阶段");
        stage.setSequenceNo(1);stage.setStartDate(drafts.get(0).start().toLocalDate());stage.setEndDate(drafts.get(drafts.size()-1).due().toLocalDate());
        stage.setOutcome("围绕「"+goal.getName()+"」完成知识学习、练习与复盘");stageMapper.insert(stage);
        for(int i=0;i<afterList.size();i++){
            PlanChangeItemEntity item=new PlanChangeItemEntity();item.setPublicId(UUID.randomUUID().toString());item.setPlanVersionId(version.getId());
            item.setAction("ADD_TASK");item.setClientRef("change-"+(i+1));item.setAfterJson(json(afterList.get(i)));
            item.setReason(drafts.get(i).reason());item.setRiskLevel("LOW");item.setConfirmRequired(true);item.setItemStatus("PROPOSED");changeMapper.insert(item);
        }
        validateInternal(version,goal,profile);
        job.setPlanVersionId(version.getId());job.setStatus("SUCCEEDED");job.setFinishedAt(Instant.now());jobMapper.updateById(job);
        audit.record("PLAN_PROPOSAL_CREATE","PLAN_VERSION",version.getPublicId(),null,"changes="+afterList.size(),"SUCCESS");
        return job;
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

    public PlanDetail getPlan(String publicId){
        LearningPlanEntity plan=ownedPlan(publicId);Long current=jdbc.query("SELECT plan_version_id FROM plan_publication WHERE plan_id=?",rs->rs.next()?rs.getLong(1):null,plan.getId());
        return new PlanDetail(plan,current==null?null:versionMapper.selectById(current));
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
        @SuppressWarnings("unchecked") Map<LocalDate,Integer> capacity=(Map<LocalDate,Integer>)profile.get("capacityByDate");
        double ratio=((BigDecimal)profile.get("capacityRatio")).doubleValue();List<PlanValidationPolicy.Issue> issues=PlanValidationPolicy.validate(changes,goal.getStartDate(),goal.getDueDate(),capacity,ratio);
        for(var issue:issues){PlanValidationResultEntity e=new PlanValidationResultEntity();e.setPlanVersionId(v.getId());e.setValidatorCode(issue.code());e.setSeverity(issue.severity());e.setFieldPath(issue.fieldPath());e.setMessage(issue.message());e.setDetailsJson(json(issue.details()));e.setCreatedAt(Instant.now());validationMapper.insert(e);}
        v.setStatus(issues.stream().anyMatch(i->"ERROR".equals(i.severity()))?"VALIDATION_FAILED":"PENDING_CONFIRMATION");versionMapper.updateById(v);
    }

    private Map<String,Object> profileContext(long userId){
        Map<String,Object> p=jdbc.query("SELECT p.id,p.timezone,p.version,p.current_version_no,COALESCE(pref.capacity_ratio,?) ratio FROM user_profile p LEFT JOIN learning_preference pref ON pref.user_id=p.user_id AND pref.deleted_at IS NULL WHERE p.user_id=? AND p.deleted_at IS NULL",
                rs->{if(!rs.next())return null;Map<String,Object>m=new HashMap<>();m.put("profileVersion",rs.getInt("version"));m.put("profileSnapshotVersion",rs.getInt("current_version_no"));m.put("timezone",rs.getString("timezone"));m.put("capacityRatio",rs.getBigDecimal("ratio"));return m;},defaultCapacityRatio,userId);
        if(p==null)throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,"画像最小字段尚未完成");
        List<RuleBasedPlanner.Slot> slots=jdbc.query("SELECT weekday,start_time,available_minutes FROM availability_rule WHERE user_id=? AND deleted_at IS NULL ORDER BY weekday,start_time",
                (rs,row)->new RuleBasedPlanner.Slot(rs.getInt(1),rs.getTime(2).toLocalTime(),rs.getInt(3)),userId);p.put("slots",slots);
        Map<LocalDate,Integer> exceptions=new HashMap<>();jdbc.query("SELECT local_date,available_minutes FROM availability_exception WHERE user_id=? AND deleted_at IS NULL",rs->{exceptions.put(rs.getDate(1).toLocalDate(),rs.getInt(2));},userId);p.put("exceptions",exceptions);
        ZoneId zone=ZoneId.of((String)p.get("timezone"));Map<LocalDate,Integer> capacity=new HashMap<>();LocalDate start=LocalDate.now(zone);
        for(int i=0;i<366;i++){LocalDate d=start.plusDays(i);int value=exceptions.getOrDefault(d,slots.stream().filter(s->s.weekday()==d.getDayOfWeek().getValue()).mapToInt(RuleBasedPlanner.Slot::minutes).sum());capacity.put(d,value);}p.put("capacityByDate",capacity);
        p.put("planningFingerprint",hashing.sha256(json(Map.of("slots",slots,"exceptions",exceptions,"capacityRatio",p.get("capacityRatio"),"timezone",p.get("timezone")))));return p;
    }

    private void applyChange(PlanChangeItemEntity item,LearningPlanEntity plan,PlanVersionEntity version,List<String> changed){
        Map<String,Object>a=map(item.getAfterJson());if("ADD_TASK".equals(item.getAction())){LearningTaskEntity t=new LearningTaskEntity();t.setPublicId(UUID.randomUUID().toString());t.setUserId(plan.getUserId());t.setGoalId(plan.getGoalId());t.setProjectId(plan.getProjectId());t.setOriginPlanVersionId(version.getId());
            t.setTitle(String.valueOf(a.get("title")));t.setDescription(String.valueOf(a.getOrDefault("description","")));t.setTaskType(String.valueOf(a.get("taskType")));t.setPriority(String.valueOf(a.get("priority")));t.setEstimatedMinutes(((Number)a.get("estimatedMinutes")).intValue());
            t.setScheduledStart(ZonedDateTime.parse(String.valueOf(a.get("scheduledStart"))).toInstant());t.setDueAt(ZonedDateTime.parse(String.valueOf(a.get("dueAt"))).toInstant());t.setLockedSchedule(Boolean.TRUE.equals(a.get("lockedSchedule")));t.setLifecycleStatus("NOT_STARTED");t.setProgressPercent(BigDecimal.ZERO);t.setRescheduleCount(0);t.setAcceptanceJson(json(a.getOrDefault("acceptanceCriteria",List.of())));taskMapper.insert(t);changed.add(t.getPublicId());
            @SuppressWarnings("unchecked")List<Number>kp=(List<Number>)a.getOrDefault("knowledgePointIds",List.of());for(Number id:kp)jdbc.update("INSERT INTO task_knowledge_point(task_id,knowledge_point_id,weight) VALUES(?,?,?)",t.getId(),id.longValue(),BigDecimal.ONE);
        }else{LearningTaskEntity t=taskMapper.selectById(item.getTargetTaskId());if(t==null||!t.getUserId().equals(plan.getUserId()))notFound();if("COMPLETED".equals(t.getLifecycleStatus()))throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"已完成任务不能由新计划覆盖");
            if("RESCHEDULE_TASK".equals(item.getAction())){Instant oldStart=t.getScheduledStart(),oldDue=t.getDueAt();t.setScheduledStart(ZonedDateTime.parse(String.valueOf(a.get("scheduledStart"))).toInstant());t.setDueAt(ZonedDateTime.parse(String.valueOf(a.get("dueAt"))).toInstant());t.setRescheduleCount(t.getRescheduleCount()+1);taskMapper.updateById(t);jdbc.update("INSERT INTO task_schedule_history(id,task_id,old_start,old_due,new_start,new_due,reason,source_plan_version_id,created_at) VALUES(?,?,?,?,?,?,?,?,?)",IdWorker.getId(),t.getId(),oldStart,oldDue,t.getScheduledStart(),t.getDueAt(),item.getReason(),version.getId(),Instant.now());}
            else if("CANCEL_TASK".equals(item.getAction())){t.setLifecycleStatus("CANCELED");taskMapper.updateById(t);}else{t.setTitle(String.valueOf(a.getOrDefault("title",t.getTitle())));taskMapper.updateById(t);}changed.add(t.getPublicId());}
    }

    private void requirePublishable(PlanVersionEntity v){if(!"PENDING_CONFIRMATION".equals(v.getStatus()))throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED,"提案尚未通过校验或已不在待确认状态");long errors=validationMapper.selectCount(new LambdaQueryWrapper<PlanValidationResultEntity>().eq(PlanValidationResultEntity::getPlanVersionId,v.getId()).eq(PlanValidationResultEntity::getSeverity,"ERROR"));if(errors>0)throw new BusinessException(ErrorCode.PLAN_CONFIRMATION_REQUIRED,"提案仍有校验错误");}
    private void requireContextFresh(PlanVersionEntity v){Map<String,Object>c=map(v.getContextSnapshotJson());long goalId=((Number)c.get("goalId")).longValue();int expected=((Number)c.get("goalVersion")).intValue();LearningGoalEntity g=goalMapper.selectById(goalId);if(g==null||g.getVersion()!=expected)throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"目标在预览后已发生变化");Map<String,Object>current=profileContext(SecurityUtils.currentUserId());if(((Number)c.get("profileVersion")).intValue()!=((Number)current.get("profileVersion")).intValue()||!Objects.equals(c.get("planningFingerprint"),current.get("planningFingerprint")))throw new BusinessException(ErrorCode.PLAN_CONTEXT_STALE,"画像、偏好或可用时间在预览后已发生变化");}
    private PublicationResult publicationResult(PlanVersionEntity v){LearningPlanEntity p=planMapper.selectById(v.getPlanId());List<String>tasks=taskMapper.selectList(new LambdaQueryWrapper<LearningTaskEntity>().eq(LearningTaskEntity::getOriginPlanVersionId,v.getId())).stream().map(LearningTaskEntity::getPublicId).toList();return new PublicationResult(p.getPublicId(),v.getPublicId(),v.getVersionNo(),tasks,v.getStatus());}
    private LearningPlanEntity findOrCreatePlan(LearningGoalEntity goal,Long projectId){LearningPlanEntity p=planMapper.selectOne(new LambdaQueryWrapper<LearningPlanEntity>().eq(LearningPlanEntity::getGoalId,goal.getId()).eq(projectId!=null,LearningPlanEntity::getProjectId,projectId).isNull(projectId==null,LearningPlanEntity::getProjectId));if(p==null){p=new LearningPlanEntity();p.setPublicId(UUID.randomUUID().toString());p.setUserId(goal.getUserId());p.setGoalId(goal.getId());p.setProjectId(projectId);p.setName(goal.getName()+"学习计划");p.setStatus("ACTIVE");planMapper.insert(p);}return p;}
    private Long resolveProject(String publicId){if(publicId==null||publicId.isBlank())return null;List<Long>ids=jdbc.query("SELECT id FROM learning_project WHERE public_id=? AND user_id=? AND deleted_at IS NULL",(rs,row)->rs.getLong(1),publicId,SecurityUtils.currentUserId());if(ids.isEmpty())notFound();return ids.get(0);}
    private LearningGoalEntity ownedGoal(String publicId){LearningGoalEntity g=goalMapper.selectOne(new LambdaQueryWrapper<LearningGoalEntity>().eq(LearningGoalEntity::getPublicId,publicId).eq(LearningGoalEntity::getUserId,SecurityUtils.currentUserId()));if(g==null)notFound();return g;}
    private LearningPlanEntity ownedPlan(String publicId){LearningPlanEntity p=planMapper.selectOne(new LambdaQueryWrapper<LearningPlanEntity>().eq(LearningPlanEntity::getPublicId,publicId).eq(LearningPlanEntity::getUserId,SecurityUtils.currentUserId()));if(p==null)notFound();return p;}
    private PlanVersionEntity ownedVersion(String publicId){PlanVersionEntity v=versionMapper.selectOne(new LambdaQueryWrapper<PlanVersionEntity>().eq(PlanVersionEntity::getPublicId,publicId));if(v==null)notFound();LearningPlanEntity p=planMapper.selectById(v.getPlanId());if(p==null||!p.getUserId().equals(SecurityUtils.currentUserId()))notFound();return v;}
    private LearningTaskEntity ownedTask(String publicId){LearningTaskEntity t=taskMapper.selectOne(new LambdaQueryWrapper<LearningTaskEntity>().eq(LearningTaskEntity::getPublicId,publicId).eq(LearningTaskEntity::getUserId,SecurityUtils.currentUserId()));if(t==null)notFound();return t;}
    private Map<String,Object> taskAfter(RuleBasedPlanner.TaskDraft d,LearningGoalEntity goal,Long projectId){Map<String,Object>a=new LinkedHashMap<>();a.put("title",d.title());a.put("description","");a.put("taskType",d.taskType());a.put("priority",d.priority());a.put("scheduledStart",d.start().toString());a.put("dueAt",d.due().toString());a.put("estimatedMinutes",d.estimatedMinutes());a.put("lockedSchedule",false);a.put("goalId",goal.getPublicId());a.put("projectId",projectId);a.put("knowledgePointIds",d.knowledgePointIds());a.put("dependencyTaskIds",List.of());a.put("acceptanceCriteria",d.acceptance());return a;}
    private Map<String,Object> summary(List<Map<String,Object>> after){int total=after.stream().mapToInt(a->((Number)a.get("estimatedMinutes")).intValue()).sum();List<LocalDate>dates=after.stream().map(a->ZonedDateTime.parse(String.valueOf(a.get("scheduledStart"))).toLocalDate()).sorted().toList();return Map.of("added",after.size(),"updated",0,"rescheduled",0,"canceled",0,"totalEstimatedMinutes",total,"affectedDateFrom",dates.get(0),"affectedDateTo",dates.get(dates.size()-1));}
    private Map<String,Object> map(String value){try{return objectMapper.readValue(value,new TypeReference<>(){});}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private List<Object> readList(String value){try{return objectMapper.readValue(value,new TypeReference<>(){});}catch(Exception e){return List.of();}}
    private String json(Object value){try{return objectMapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private void notFound(){throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"资源不存在");}
}
