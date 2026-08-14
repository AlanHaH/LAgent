package com.adaptivelearning.planning.application;

import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.CurrentUser;
import com.adaptivelearning.support.application.AuditService;
import com.adaptivelearning.support.application.HashingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PlanningPublicationConcurrencyIntegrationTest {
    @Autowired PlanningService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired HashingService hashing;
    @MockBean AuditService audit;

    private static final long USER_ID = 42L;
    private static final long GOAL_ID = 8_100L;
    private static final long PLAN_ID = 8_200L;

    @BeforeEach
    void setUp() throws Exception {
        createM05TestTables();
        clearM05Data();
        authenticate();
        Instant now = Instant.now();
        jdbc.update("""
                MERGE INTO sys_user(id,public_id,username,email,email_verified_at,password_hash,status,timezone,
                  login_failed_count,created_at,created_by,updated_at,updated_by,version)
                KEY(id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, USER_ID, "m05-user", "m05-user", "m05-user@example.com", now, "test", "ACTIVE",
                "Asia/Shanghai", 0, now, USER_ID, now, USER_ID, 0);
        String snapshot = json.writeValueAsString(Map.of(
                "timezone", "Asia/Shanghai",
                "backgroundText", "并发发布测试",
                "directions", List.of(Map.of("directionId", "10", "name", "计算机科学",
                        "currentStage", "INTERMEDIATE", "primary", true)),
                "preference", Map.of("capacityRatio", 0.85),
                "availabilityRules", List.of(
                        Map.of("weekday",1,"start","08:00","end","23:00","availableMinutes",900),
                        Map.of("weekday",2,"start","08:00","end","23:00","availableMinutes",900),
                        Map.of("weekday",3,"start","08:00","end","23:00","availableMinutes",900),
                        Map.of("weekday",4,"start","08:00","end","23:00","availableMinutes",900),
                        Map.of("weekday",5,"start","08:00","end","23:00","availableMinutes",900),
                        Map.of("weekday",6,"start","08:00","end","23:00","availableMinutes",900),
                        Map.of("weekday",7,"start","08:00","end","23:00","availableMinutes",900)),
                "availabilityExceptions", List.of()));
        jdbc.update("""
                INSERT INTO user_profile(id,user_id,timezone,week_start,plan_start_date,plan_end_date,
                  plan_period_days,background_text,profile_status,current_version_no,created_at,created_by,
                  updated_at,updated_by,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, 8_000L, USER_ID, "Asia/Shanghai", 1, LocalDate.now(), LocalDate.now().plusDays(30),
                30, "并发发布测试", "GENERATED", 1, now, USER_ID, now, USER_ID, 0);
        jdbc.update("""
                INSERT INTO profile_version(id,profile_id,version_no,snapshot_json,confidence,trigger_type,
                  trigger_event_id,created_at,created_by) VALUES(?,?,?,? FORMAT JSON,?,?,?,?,?)
                """, 8_001L, 8_000L, 1, snapshot, new BigDecimal("0.20"), "MANUAL", "test", now, USER_ID);
        jdbc.update("""
                INSERT INTO learning_goal(id,public_id,user_id,direction_id,custom_direction,source_type,
                  profile_version_id,name,type,description,priority,start_date,due_date,weekly_budget_minutes,
                  status,success_criteria_json,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, GOAL_ID, "goal-cas", USER_ID, 10L, null, "CUSTOM", 8_001L, "并发计划目标", "SKILL",
                "验证发布 CAS", "MEDIUM", LocalDate.now(), LocalDate.now().plusDays(30), 300, "ACTIVE",
                "[]", now, USER_ID, now, USER_ID, 0);
        jdbc.update("""
                INSERT INTO learning_plan(id,public_id,user_id,goal_id,project_id,name,status,created_at,
                  created_by,updated_at,updated_by,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, PLAN_ID, "plan-cas", USER_ID, GOAL_ID, null, "并发计划", "ACTIVE",
                now, USER_ID, now, USER_ID, 0);
    }

    @Test
    void twoInitialProposalsWithoutPublicationAllowOnlyOneCommit() throws Exception {
        insertProposal(8_301L, "proposal-a", 1, null, "token-a");
        insertProposal(8_302L, "proposal-b", 2, null, "token-b");

        List<Object> outcomes = publishConcurrently(
                new PublishCall("proposal-a", "token-a", "publish-a"),
                new PublishCall("proposal-b", "token-b", "publish-b"));

        assertOnePublishedAndOneStale(outcomes);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_publication WHERE plan_id=?", Integer.class, PLAN_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id='plan-cas' AND event_type='PlanPublished'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_version WHERE plan_id=? AND status='PUBLISHED'", Integer.class, PLAN_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_version WHERE plan_id=? AND status='PENDING_CONFIRMATION'", Integer.class, PLAN_ID)).isEqualTo(1);

        PlanningService.PublicationResult success = outcomes.stream()
                .filter(PlanningService.PublicationResult.class::isInstance)
                .map(PlanningService.PublicationResult.class::cast).findFirst().orElseThrow();
        String retryKey = success.versionId().equals("proposal-a") ? "publish-a" : "publish-b";
        String retryToken = success.versionId().equals("proposal-a") ? "token-a" : "token-b";
        assertThat(service.publish(success.versionId(), retryToken, retryKey).versionId()).isEqualTo(success.versionId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id='plan-cas' AND event_type='PlanPublished'", Integer.class)).isEqualTo(1);
    }

    @Test
    void twoOptimizationsWithSameBaseAllowOnlyOneCommit() throws Exception {
        insertPublishedBase(8_300L, "published-base", 1);
        insertProposal(8_301L, "optimization-a", 2, 1, "token-a");
        insertProposal(8_302L, "optimization-b", 3, 1, "token-b");

        List<Object> outcomes = publishConcurrently(
                new PublishCall("optimization-a", "token-a", "opt-a"),
                new PublishCall("optimization-b", "token-b", "opt-b"));

        assertOnePublishedAndOneStale(outcomes);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_publication WHERE plan_id=?", Integer.class, PLAN_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id='plan-cas' AND event_type='PlanPublished'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_version WHERE id=? AND status='SUPERSEDED'", Integer.class, 8_300L)).isEqualTo(1);
    }

    @Test
    void changedScopedTasksMakeOldProposalStaleWithoutSideEffects() throws Exception {
        insertProposal(8_301L, "stale-tasks", 1, null, "token-stale");
        insertTask(8_501L, "task-new", null, "生成后新增的任务");

        Object outcome;
        try {
            outcome = service.publish("stale-tasks", "token-stale", "stale-publish");
        } catch (BusinessException error) {
            outcome = error;
        }

        assertThat(outcome).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) outcome).getCode()).isEqualTo(ErrorCode.PLAN_CONTEXT_STALE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_publication", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id='plan-cas'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM plan_version WHERE id=?", String.class, 8_301L))
                .isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    void taskScopeIsExactForGoalLevelAndProjects() {
        insertTask(8_501L, "task-goal", null, "目标级任务");
        insertTask(8_502L, "task-a", 9_001L, "项目 A 任务");
        insertTask(8_503L, "task-b", 9_002L, "项目 B 任务");
        PlanningService target = AopTestUtils.getUltimateTargetObject(service);

        List<?> goalTasks = ReflectionTestUtils.invokeMethod(target, "scopedTasks", GOAL_ID, null, true);
        List<?> projectATasks = ReflectionTestUtils.invokeMethod(target, "scopedTasks", GOAL_ID, 9_001L, true);
        String aBefore = ReflectionTestUtils.invokeMethod(target, "scopedTaskFingerprint", GOAL_ID, 9_001L);
        jdbc.update("UPDATE learning_task SET title='项目 B 已修改',version=version+1 WHERE id=?", 8_503L);
        String aAfter = ReflectionTestUtils.invokeMethod(target, "scopedTaskFingerprint", GOAL_ID, 9_001L);

        assertThat(goalTasks).extracting(item -> ReflectionTestUtils.invokeMethod(item, "getPublicId"))
                .containsExactly("task-goal");
        assertThat(projectATasks).extracting(item -> ReflectionTestUtils.invokeMethod(item, "getPublicId"))
                .containsExactly("task-a");
        assertThat(aAfter).isEqualTo(aBefore);
    }

    @Test
    void inactiveGoalDraftProfileAndInactiveProjectBlockPublication() throws Exception {
        insertProposal(8_301L, "inactive-goal", 1, null, "token-goal");
        jdbc.update("UPDATE learning_goal SET status='PAUSED' WHERE id=?", GOAL_ID);
        assertBusinessCode(() -> service.publish("inactive-goal", "token-goal", "inactive-goal-key"),
                ErrorCode.STATE_TRANSITION_INVALID);
        jdbc.update("UPDATE learning_goal SET status='ACTIVE' WHERE id=?", GOAL_ID);

        jdbc.update("UPDATE user_profile SET profile_status='DRAFT' WHERE id=8000");
        assertBusinessCode(() -> service.publish("inactive-goal", "token-goal", "draft-profile-key"),
                ErrorCode.PROFILE_CONTEXT_STALE);
        jdbc.update("UPDATE user_profile SET profile_status='GENERATED' WHERE id=8000");

        Instant now=Instant.now();
        jdbc.update("""
                INSERT INTO learning_project(id,public_id,user_id,name,start_date,due_date,priority,status,
                  deliverable_json,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,9_001L,"project-paused",USER_ID,"暂停项目",LocalDate.now(),LocalDate.now().plusDays(10),
                "MEDIUM","PAUSED","[]",now,USER_ID,now,USER_ID,0);
        jdbc.update("UPDATE learning_plan SET project_id=? WHERE id=?",9_001L,PLAN_ID);
        assertBusinessCode(() -> service.publish("inactive-goal", "token-goal", "inactive-project-key"),
                ErrorCode.STATE_TRANSITION_INVALID);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_publication",Integer.class)).isZero();
    }

    @Test
    void partialSelectionCreatesStageAndCannotRunFromTerminalVersion() throws Exception {
        insertProposal(8_301L, "partial-source", 1, null, "token-partial");
        jdbc.update("UPDATE plan_version SET status='VALIDATION_FAILED' WHERE id=?",8_301L);
        Instant start=Instant.parse("2026-08-15T11:00:00Z");
        Instant due=Instant.parse("2026-08-15T12:00:00Z");
        Map<String,Object> afterValue=new LinkedHashMap<>();
        afterValue.put("title","选择任务");afterValue.put("description","部分采纳");
        afterValue.put("taskType","STUDY");afterValue.put("priority","MEDIUM");
        afterValue.put("scheduledStart",ZonedDateTime.ofInstant(start,java.time.ZoneId.of("Asia/Shanghai")).toString());
        afterValue.put("dueAt",ZonedDateTime.ofInstant(due,java.time.ZoneId.of("Asia/Shanghai")).toString());
        afterValue.put("estimatedMinutes",60);afterValue.put("lockedSchedule",false);
        afterValue.put("knowledgePointIds",List.of());afterValue.put("knowledgeSources",List.of());
        afterValue.put("acceptanceCriteria",List.of("完成"));
        String after=json.writeValueAsString(afterValue);
        jdbc.update("""
                INSERT INTO plan_change_item(id,public_id,plan_version_id,action,client_ref,after_json,reason,
                  risk_level,confirm_required,item_status) VALUES(?,?,?,?,?,?,?,?,?,?)
                """,8_401L,"change-selected",8_301L,"ADD_TASK","change-1",after,"部分采纳","LOW",true,"PROPOSED");
        jdbc.update("""
                INSERT INTO plan_stage(id,plan_version_id,client_ref,name,sequence_no,start_date,end_date,outcome)
                VALUES(?,?,?,?,?,?,?,?)
                """,8_410L,8_301L,"stage-1","原阶段",1,LocalDate.of(2026,8,15),LocalDate.of(2026,8,15),"完成原任务");

        PlanningService.VersionDetail detail=service.partialSelection("partial-source",List.of("change-selected"));

        assertThat(detail.version().getStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(detail.stages()).hasSize(1);
        assertThat(detail.stages().get(0).getStartDate()).isEqualTo(LocalDate.of(2026,8,15));
        assertThat(detail.stages().get(0).getEndDate()).isEqualTo(LocalDate.of(2026,8,15));
        assertThat(jdbc.queryForObject("SELECT status FROM plan_version WHERE id=?",String.class,8_301L)).isEqualTo("REJECTED");
        assertBusinessCode(() -> service.partialSelection("partial-source",List.of("change-selected")),
                ErrorCode.STATE_TRANSITION_INVALID);
    }

    @Test
    void crossUserVersionIsNotReadable() throws Exception {
        insertProposal(8_301L,"other-version",1,null,"token-other");
        Instant now=Instant.now();
        jdbc.update("""
                INSERT INTO planning_job(id,public_id,user_id,goal_id,job_type,status,idempotency_key,request_hash,
                  started_at,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,8_601L,"other-job",99L,GOAL_ID,"INITIAL","SUCCEEDED","other-key",
                "0123456789012345678901234567890123456789012345678901234567890123",now,now,99L,now,99L,0);
        insertTask(8_501L,"other-task",null,"其他用户任务");
        jdbc.update("UPDATE learning_task SET user_id=99 WHERE id=?",8_501L);
        assertBusinessCode(() -> service.getJob("other-job"),ErrorCode.RESOURCE_NOT_FOUND);
        jdbc.update("UPDATE learning_plan SET user_id=99 WHERE id=?",PLAN_ID);
        assertBusinessCode(() -> service.getPlan("plan-cas"),ErrorCode.RESOURCE_NOT_FOUND);
        assertBusinessCode(() -> service.version("other-version"),ErrorCode.RESOURCE_NOT_FOUND);
        assertBusinessCode(() -> service.rescheduleProposal("other-task",ZonedDateTime.now(),
                ZonedDateTime.now().plusHours(1),"越权"),ErrorCode.RESOURCE_NOT_FOUND);
        jdbc.update("UPDATE learning_goal SET user_id=99 WHERE id=?",GOAL_ID);
        assertBusinessCode(() -> service.latestJobForGoal("goal-cas"),ErrorCode.RESOURCE_NOT_FOUND);
    }

    private void insertProposal(long id, String publicId, int versionNo, Integer baseVersion, String token) throws Exception {
        Map<String,Object> context = currentContext(baseVersion);
        context.put("planningContextFingerprint", PlanningContextPolicy.fingerprint(json, context));
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO plan_version(id,public_id,plan_id,version_no,base_version_no,status,trigger_type,
                  trigger_event_id,context_snapshot_json,proposal_hash,risk_level,summary_json,created_at,
                  created_by,updated_at,updated_by,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, publicId, PLAN_ID, versionNo, baseVersion, "PENDING_CONFIRMATION", "TEST",
                publicId, json.writeValueAsString(context), "proposal-" + id, "LOW", "{}",
                now, USER_ID, now, USER_ID, 0);
        jdbc.update("""
                INSERT INTO plan_confirmation(id,plan_version_id,user_id,proposal_hash,token_hash,status,
                  expires_at,created_at) VALUES(?,?,?,?,?,?,?,?)
                """, id + 1_000, id, USER_ID, "proposal-" + id, hashing.sha256(token), "PENDING",
                now.plusSeconds(600), now);
    }

    private void insertPublishedBase(long id, String publicId, int versionNo) throws Exception {
        Map<String,Object> context = currentContext(null);
        context.put("planningContextFingerprint", PlanningContextPolicy.fingerprint(json, context));
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO plan_version(id,public_id,plan_id,version_no,status,trigger_type,trigger_event_id,
                  context_snapshot_json,proposal_hash,risk_level,summary_json,created_at,created_by,updated_at,
                  updated_by,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, publicId, PLAN_ID, versionNo, "PUBLISHED", "TEST", publicId,
                json.writeValueAsString(context), "base", "LOW", "{}", now, USER_ID, now, USER_ID, 0);
        jdbc.update("INSERT INTO plan_publication(plan_id,plan_version_id,published_at) VALUES(?,?,?)", PLAN_ID, id, now);
    }

    private void insertTask(long id,String publicId,Long projectId,String title){
        Instant now=Instant.now();
        jdbc.update("""
                INSERT INTO learning_task(id,public_id,user_id,goal_id,project_id,title,description,task_type,
                  priority,estimated_minutes,scheduled_start,due_at,locked_schedule,lifecycle_status,
                  progress_percent,reschedule_count,acceptance_json,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,publicId,USER_ID,GOAL_ID,projectId,title,"测试任务","STUDY","MEDIUM",30,
                now.plusSeconds(id%100),now.plusSeconds(id%100+1800),false,"NOT_STARTED",BigDecimal.ZERO,0,"[]",
                now,USER_ID,now,USER_ID,0);
    }

    private Map<String,Object> currentContext(Integer baseVersion) {
        PlanningService target = AopTestUtils.getUltimateTargetObject(service);
        Object profile = ReflectionTestUtils.invokeMethod(target, "profileContext", USER_ID);
        String snapshotHash = ReflectionTestUtils.invokeMethod(profile, "snapshotHash");
        String planningFingerprint = ReflectionTestUtils.invokeMethod(profile, "planningFingerprint");
        LearningGoalEntity goal = new LearningGoalEntity();
        goal.setName("并发计划目标"); goal.setDirectionId(10L); goal.setType("SKILL");
        goal.setDescription("验证发布 CAS"); goal.setPriority("MEDIUM");
        goal.setStartDate(LocalDate.now()); goal.setDueDate(LocalDate.now().plusDays(30));
        goal.setWeeklyBudgetMinutes(300); goal.setSuccessCriteriaJson("[]"); goal.setProfileVersionId(8_001L);
        String taskFingerprint = ReflectionTestUtils.invokeMethod(target, "scopedTaskFingerprint", GOAL_ID, null);
        Map<String,Object> context = new LinkedHashMap<>();
        context.put("userId", USER_ID); context.put("goalId", GOAL_ID); context.put("projectId", null);
        context.put("goalVersion", 0); context.put("goalFingerprint", PlanValidationPolicy.goalFingerprint(goal));
        context.put("basePlanVersion", baseVersion); context.put("baseTaskFingerprint", taskFingerprint);
        context.put("goalProfileVersionId", "8001"); context.put("goalProfileVersionNo", 1);
        context.put("semanticProfileSource", "GOAL_PROFILE_VERSION"); context.put("goalProfileSnapshotHash", snapshotHash);
        context.put("schedulingProfileVersionId", "8001"); context.put("schedulingProfileVersionNo", 1);
        context.put("schedulingProfileSnapshotHash", snapshotHash); context.put("planningFingerprint", planningFingerprint);
        context.put("knowledgeSpaceIds", List.of());
        return context;
    }

    private List<Object> publishConcurrently(PublishCall first, PublishCall second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> publishAfterBarrier(first, ready, start));
            var b = executor.submit(() -> publishAfterBarrier(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private Object publishAfterBarrier(PublishCall call, CountDownLatch ready, CountDownLatch start) throws Exception {
        authenticate();
        try {
            ready.countDown(); start.await(5, TimeUnit.SECONDS);
            return service.publish(call.versionId(), call.token(), call.idempotencyKey());
        } catch (BusinessException error) {
            return error;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void assertOnePublishedAndOneStale(List<Object> outcomes) {
        assertThat(outcomes.stream().filter(PlanningService.PublicationResult.class::isInstance).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast).map(BusinessException::getCode).toList())
                .containsExactly(ErrorCode.PLAN_CONTEXT_STALE);
    }

    private void assertBusinessCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,ErrorCode code){
        org.assertj.core.api.Assertions.assertThatThrownBy(action).isInstanceOf(BusinessException.class)
                .extracting(error->((BusinessException)error).getCode()).isEqualTo(code);
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(USER_ID, "u-42", "student", "", Set.of("STUDENT"), Set.of()), null, List.of()));
    }

    private void createM05TestTables() {
        for(String ddl : List.of(
                "CREATE TABLE IF NOT EXISTS learning_goal(id BIGINT PRIMARY KEY,public_id VARCHAR(64),user_id BIGINT,direction_id BIGINT,custom_direction VARCHAR(120),source_goal_id BIGINT,source_type VARCHAR(40),profile_version_id BIGINT,recommendation_snapshot_json VARCHAR(8000),name VARCHAR(100),type VARCHAR(40),description VARCHAR(2000),priority VARCHAR(20),start_date DATE,due_date DATE,weekly_budget_minutes INT,status VARCHAR(24),success_criteria_json VARCHAR(4000),acceptance_snapshot_json VARCHAR(8000),created_at TIMESTAMP,created_by BIGINT,updated_at TIMESTAMP,updated_by BIGINT,version INT,deleted_at TIMESTAMP)",
                "CREATE TABLE IF NOT EXISTS learning_plan(id BIGINT PRIMARY KEY,public_id VARCHAR(64),user_id BIGINT,goal_id BIGINT,project_id BIGINT,name VARCHAR(160),status VARCHAR(24),created_at TIMESTAMP,created_by BIGINT,updated_at TIMESTAMP,updated_by BIGINT,version INT,deleted_at TIMESTAMP)",
                "CREATE TABLE IF NOT EXISTS plan_version(id BIGINT PRIMARY KEY,public_id VARCHAR(64),plan_id BIGINT,version_no INT,base_version_no INT,status VARCHAR(32),trigger_type VARCHAR(40),trigger_event_id VARCHAR(120),context_snapshot_json VARCHAR(16000),proposal_hash VARCHAR(64),risk_level VARCHAR(20),model_run_id BIGINT,summary_json VARCHAR(8000),created_at TIMESTAMP,created_by BIGINT,updated_at TIMESTAMP,updated_by BIGINT,version INT,deleted_at TIMESTAMP,UNIQUE(plan_id,version_no))",
                "CREATE TABLE IF NOT EXISTS plan_change_item(id BIGINT PRIMARY KEY,public_id VARCHAR(64),plan_version_id BIGINT,action VARCHAR(32),target_task_id BIGINT,client_ref VARCHAR(80),before_json VARCHAR(8000),after_json VARCHAR(8000),reason VARCHAR(1000),risk_level VARCHAR(20),confirm_required BOOLEAN,item_status VARCHAR(24))",
                "CREATE TABLE IF NOT EXISTS plan_stage(id BIGINT PRIMARY KEY,plan_version_id BIGINT,client_ref VARCHAR(80),name VARCHAR(120),sequence_no INT,start_date DATE,end_date DATE,outcome VARCHAR(1000),UNIQUE(plan_version_id,sequence_no))",
                "CREATE TABLE IF NOT EXISTS plan_validation_result(id BIGINT PRIMARY KEY,plan_version_id BIGINT,validator_code VARCHAR(80),severity VARCHAR(16),field_path VARCHAR(200),message VARCHAR(1000),details_json VARCHAR(8000),created_at TIMESTAMP)",
                "CREATE TABLE IF NOT EXISTS plan_confirmation(id BIGINT PRIMARY KEY,plan_version_id BIGINT,user_id BIGINT,proposal_hash VARCHAR(64),token_hash VARCHAR(64),status VARCHAR(24),expires_at TIMESTAMP,confirmed_at TIMESTAMP,created_at TIMESTAMP)",
                "CREATE TABLE IF NOT EXISTS plan_publication(plan_id BIGINT PRIMARY KEY,plan_version_id BIGINT UNIQUE,published_at TIMESTAMP)",
                "CREATE TABLE IF NOT EXISTS idempotency_record(id BIGINT PRIMARY KEY,user_id BIGINT,key_hash VARCHAR(64),request_hash VARCHAR(64),response_ref VARCHAR(200),status VARCHAR(24),expires_at TIMESTAMP,created_at TIMESTAMP,UNIQUE(user_id,key_hash))",
                "CREATE TABLE IF NOT EXISTS planning_job(id BIGINT PRIMARY KEY,public_id VARCHAR(64),user_id BIGINT,goal_id BIGINT,job_type VARCHAR(40),status VARCHAR(24),idempotency_key VARCHAR(160),request_hash VARCHAR(64),plan_version_id BIGINT,error_code VARCHAR(80),error_message VARCHAR(500),started_at TIMESTAMP,finished_at TIMESTAMP,created_at TIMESTAMP,created_by BIGINT,updated_at TIMESTAMP,updated_by BIGINT,version INT,deleted_at TIMESTAMP)",
                "CREATE TABLE IF NOT EXISTS learning_task(id BIGINT PRIMARY KEY,public_id VARCHAR(64),user_id BIGINT,goal_id BIGINT,project_id BIGINT,milestone_id BIGINT,origin_plan_version_id BIGINT,learning_block_id BIGINT,title VARCHAR(200),description VARCHAR(2000),task_type VARCHAR(40),priority VARCHAR(20),estimated_minutes INT,scheduled_start TIMESTAMP,due_at TIMESTAMP,locked_schedule BOOLEAN,lifecycle_status VARCHAR(24),progress_percent DECIMAL(5,2),completed_at TIMESTAMP,reschedule_count INT,acceptance_json VARCHAR(4000),created_at TIMESTAMP,created_by BIGINT,updated_at TIMESTAMP,updated_by BIGINT,version INT,deleted_at TIMESTAMP)",
                "CREATE TABLE IF NOT EXISTS task_dependency(predecessor_task_id BIGINT,successor_task_id BIGINT,PRIMARY KEY(predecessor_task_id,successor_task_id))",
                "CREATE TABLE IF NOT EXISTS task_knowledge_point(task_id BIGINT,knowledge_point_id BIGINT,weight DECIMAL(6,4),PRIMARY KEY(task_id,knowledge_point_id))",
                "CREATE TABLE IF NOT EXISTS task_knowledge_source(task_id BIGINT,chunk_id BIGINT,created_at TIMESTAMP,PRIMARY KEY(task_id,chunk_id))",
                "CREATE TABLE IF NOT EXISTS knowledge_mastery(id BIGINT,user_id BIGINT,knowledge_point_id BIGINT,score DECIMAL(6,2),level VARCHAR(40))"
        )) jdbc.execute(ddl);
    }

    private void clearM05Data() {
        for(String table : List.of("idempotency_record","planning_job","plan_confirmation","plan_validation_result","plan_change_item",
                "plan_stage","plan_publication","plan_version","task_dependency","task_knowledge_point","task_knowledge_source",
                "learning_task","learning_plan","learning_goal","knowledge_mastery")) jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM learning_project WHERE id=9001");
        jdbc.update("DELETE FROM profile_version WHERE profile_id=8000");
        jdbc.update("DELETE FROM user_profile WHERE id=8000");
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id='plan-cas'");
    }

    private record PublishCall(String versionId, String token, String idempotencyKey) { }
}
