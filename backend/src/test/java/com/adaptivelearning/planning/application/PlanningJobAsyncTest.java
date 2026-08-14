package com.adaptivelearning.planning.application;

import com.adaptivelearning.execution.domain.LearningTaskEntity;
import com.adaptivelearning.execution.application.TaskCancellationService;
import com.adaptivelearning.execution.infrastructure.LearningTaskMapper;
import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.GoalMapper;
import com.adaptivelearning.planning.domain.LearningPlanEntity;
import com.adaptivelearning.planning.domain.PlanChangeItemEntity;
import com.adaptivelearning.planning.domain.PlanStageEntity;
import com.adaptivelearning.planning.domain.PlanVersionEntity;
import com.adaptivelearning.planning.domain.PlanningJobEntity;
import com.adaptivelearning.planning.infrastructure.PlanningMappers.*;
import com.adaptivelearning.shared.ai.AiModelException;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.CurrentUser;
import com.adaptivelearning.support.application.AuditService;
import com.adaptivelearning.support.application.HashingService;
import com.adaptivelearning.support.infrastructure.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanningJobAsyncTest {

    @Mock GoalMapper goalMapper;
    @Mock PlanMapper planMapper;
    @Mock PlanVersionMapper versionMapper;
    @Mock PlanStageMapper stageMapper;
    @Mock PlanChangeMapper changeMapper;
    @Mock PlanValidationMapper validationMapper;
    @Mock PlanConfirmationMapper confirmationMapper;
    @Mock PlanningJobMapper jobMapper;
    @Mock PublicationMapper publicationMapper;
    @Mock OutboxMapper outboxMapper;
    @Mock LearningTaskMapper taskMapper;
    @Mock IdempotencyService idempotency;
    @Mock RuleBasedPlanner ruleBasedPlanner;
    @Mock PythonAiServiceClient pythonAi;
    @Mock HashingService hashing;
    @Mock JdbcTemplate jdbc;
    @Mock AuditService audit;
    @Mock PlatformTransactionManager transactionManager;
    @Mock UserMapper userMapper;
    @Mock TaskCancellationService taskCancellation;

    /** 正式排期必须使用画像可用时段。 */
    private final List<RuleBasedPlanner.Slot> slots = new ArrayList<>();
    private final List<Runnable> submitted = new ArrayList<>();
    private final List<Map<String, Object>> catalogKnowledge = new ArrayList<>();
    private final List<KnowledgePrerequisitePolicy.Dependency> catalogDependencies = new ArrayList<>();
    private final List<Long> proficientKnowledgePointIds = new ArrayList<>();
    private final List<Long> publishedTaskIds = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private PlanningService service;

    @BeforeEach
    void setUp() {
        // SQL 分发桩：按语句特征返回对应的模拟数据（严格模式不会误报未用桩）
        lenient().when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("SELECT p.id,p.profile_status")) {
                Map<String,Object> snapshot = new HashMap<>();
                snapshot.put("timezone", "Asia/Shanghai");
                snapshot.put("backgroundText", "Java 基础学习者");
                snapshot.put("planStartDate", "2026-08-01");
                snapshot.put("planEndDate", "2026-08-30");
                snapshot.put("dailyRecommendedTasks", 2);
                snapshot.put("directions", List.of(Map.of(
                        "directionId", "1", "name", "Java 方向", "currentStage", "INTERMEDIATE",
                        "primary", true, "sourceType", "CATALOG", "knowledgeBaseDirection", true)));
                snapshot.put("preference", Map.of("capacityRatio", 0.85, "focusMinutes", 45));
                snapshot.put("availabilityRules", slots.stream().map(slot -> Map.of(
                        "weekday", slot.weekday(), "start", slot.start().toString(),
                        "availableMinutes", slot.minutes())).toList());
                snapshot.put("availabilityExceptions", List.of());
                Map<String, Object> profile = new HashMap<>();
                profile.put("profileId", 10L);
                profile.put("status", "GENERATED");
                profile.put("versionNo", 2);
                profile.put("versionId", 20L);
                profile.put("snapshotJson", objectMapper.writeValueAsString(snapshot));
                return profile;
            }
            if (sql.contains("FROM profile_version v JOIN user_profile p")) {
                Map<String,Object> snapshot = new HashMap<>();
                snapshot.put("timezone", "Asia/Shanghai");
                snapshot.put("directions", List.of(Map.of(
                        "directionId", "1", "name", "Java 方向", "currentStage", "ADVANCED",
                        "primary", true, "sourceType", "CATALOG", "knowledgeBaseDirection", true)));
                return Map.of("id", 15L, "versionNo", 1,
                        "snapshotJson", objectMapper.writeValueAsString(snapshot));
            }
            if (sql.contains("SELECT id,profile_status FROM user_profile"))
                return Map.of("id", 10L, "status", "GENERATED");
            if (sql.contains("learning_direction")) return "Java 方向";
            if (sql.contains("current_stage")) return "INTERMEDIATE";
            if (sql.contains("plan_publication")) return null; // 尚无已发布版本
            return null;
        });
        lenient().when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("weekday,start_time")) return slots;
            if (sql.contains("knowledge_point WHERE direction_id"))
                return catalogKnowledge;
            if (sql.contains("FROM knowledge_dependency")) return catalogDependencies;
            if (sql.contains("FROM knowledge_mastery")) return proficientKnowledgePointIds;
            if (sql.contains("SELECT id FROM learning_task")) return publishedTaskIds;
            if (sql.contains("reference_point")) return List.of();
            return List.of();
        });
        lenient().when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
        lenient().when(hashing.sha256(anyString())).thenReturn("req-hash");
        lenient().when(pythonAi.isConfigured()).thenReturn(true);
        lenient().when(goalMapper.lockById(anyLong())).thenAnswer(inv -> activeGoal());

        slots.add(new RuleBasedPlanner.Slot(1, LocalTime.of(19, 0), 60)); // 周一晚间 1 小时
        catalogKnowledge.add(Map.of("id", 1L, "name", "Java 变量"));

        doAnswer(inv -> { ((PlanningJobEntity) inv.getArgument(0)).setId(200L); return 1; }).when(jobMapper).insert(any(PlanningJobEntity.class));
        doAnswer(inv -> { ((LearningPlanEntity) inv.getArgument(0)).setId(300L); return 1; }).when(planMapper).insert(any(LearningPlanEntity.class));
        lenient().when(planMapper.selectById(300L)).thenAnswer(inv -> {
            LearningPlanEntity plan = new LearningPlanEntity(); plan.setId(300L); plan.setPublicId("plan-1");
            plan.setUserId(1L); plan.setGoalId(100L); plan.setStatus("ACTIVE"); return plan;
        });
        doAnswer(inv -> { ((PlanVersionEntity) inv.getArgument(0)).setId(400L); return 1; })
                .when(versionMapper).insert(any(PlanVersionEntity.class));

        service = new PlanningService(goalMapper, planMapper, versionMapper, stageMapper, changeMapper,
                validationMapper, confirmationMapper, jobMapper, publicationMapper, outboxMapper, taskMapper,
                idempotency, ruleBasedPlanner, pythonAi, hashing, objectMapper,
                jdbc, audit, transactionManager, userMapper, taskCancellation);
        ReflectionTestUtils.setField(service, "planningJobExecutor", (Executor) submitted::add);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(1L, "user-pid", "tester", "pw", Set.of("LEARNER"), Set.of()), null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private LearningGoalEntity activeGoal() {
        LearningGoalEntity goal = new LearningGoalEntity();
        goal.setId(100L); goal.setPublicId("goal-1"); goal.setUserId(1L);
        goal.setName("构建图书管理控制台应用"); goal.setDirectionId(1L);
        goal.setStartDate(LocalDate.parse("2026-08-01")); goal.setDueDate(LocalDate.parse("2026-08-30"));
        goal.setStatus("ACTIVE"); goal.setVersion(1);
        return goal;
    }

    private PlanningService.JobRequest initialRequest() {
        return new PlanningService.JobRequest("INITIAL", null, null, List.of());
    }

    @Test
    void submitReturnsQueuedJobImmediatelyWithoutRunningGeneration() {
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());
        when(jobMapper.selectOne(any())).thenReturn(null);
        when(jobMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.selectCount(any())).thenReturn(0L);

        PlanningJobEntity job = service.submitPlanningJob("goal-1", initialRequest(), "key-1");

        assertThat(job.getStatus()).isEqualTo("QUEUED");
        assertThat(job.getPublicId()).isNotBlank();
        assertThat(job.getStartedAt()).isNotNull();
        assertThat(submitted).hasSize(1);
        verify(jobMapper, never()).selectById(any());
        verify(pythonAi, never()).planRecommendations(any());
    }

    @Test
    void workerRestoresIdentityAndMarksJobSucceeded() throws Exception {
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());
        when(goalMapper.selectById(100L)).thenReturn(activeGoal());
        when(jobMapper.selectOne(any())).thenReturn(null);
        when(jobMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.selectCount(any())).thenReturn(0L);
        when(ruleBasedPlanner.schedule(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(

                new RuleBasedPlanner.TaskDraft("学习 Java 变量", "LEARNING", "HIGH",
                        ZonedDateTime.of(2026, 8, 3, 19, 0, 0, 0, ZoneId.of("Asia/Shanghai")),
                        ZonedDateTime.of(2026, 8, 3, 20, 0, 0, 0, ZoneId.of("Asia/Shanghai")),
                        30, List.of(1L), List.of(), "掌握变量声明", List.of(), false,
                        List.of("能写出变量声明"), "基础第一步")));
        when(pythonAi.planRecommendations(any())).thenAnswer(inv -> {
            // 验证后台线程已恢复登录身份
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            assertThat(((CurrentUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).id()).isEqualTo(1L);
            return new PythonAiServiceClient.PlanRecommendationResult(List.of(
                    new PythonAiServiceClient.PlanTaskItem("学习 Java 变量", "LEARNING", "HIGH", 30,
                            List.of(1L), List.of(), "掌握变量声明", List.of(),
                            List.of("能写出变量声明"), "基础第一步")), "test-prompt-v1");
        });

        PlanningJobEntity job = service.submitPlanningJob("goal-1", initialRequest(), "key-1");
        submitted.forEach(Runnable::run);

        assertThat(job.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(job.getPlanVersionId()).isEqualTo(400L);
        assertThat(job.getFinishedAt()).isNotNull();
        verify(jobMapper, times(2)).updateById(any(PlanningJobEntity.class)); // RUNNING + SUCCEEDED
        // 后台线程无请求上下文：审计必须用 recordAs 显式传参，不能用 record()（会访问 HttpServletRequest）
        verify(audit).currentClientIp();
        verify(audit).recordAs(eq(1L), eq("request-unavailable"), isNull(), eq("PLAN_PROPOSAL_CREATE"),
                eq("PLAN_VERSION"), anyString(), isNull(), anyString(), eq("SUCCESS"));
    }

    @Test
    void workerMarksJobFailedWithAiErrorCodeWhenModelTimesOut() {
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());
        when(goalMapper.selectById(100L)).thenReturn(activeGoal());
        when(jobMapper.selectOne(any())).thenReturn(null);
        when(jobMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.selectCount(any())).thenReturn(0L);
        when(pythonAi.planRecommendations(any()))
                .thenThrow(new AiModelException(ErrorCode.MODEL_REQUEST_TIMEOUT, "模型响应超时", Map.of(), null));

        service.submitPlanningJob("goal-1", initialRequest(), "key-1");
        submitted.forEach(Runnable::run);

        PlanningJobEntity marked = failedUpdate();
        assertThat(marked.getStatus()).isEqualTo("FAILED");
        assertThat(marked.getErrorCode()).isEqualTo("MODEL_REQUEST_TIMEOUT");
        assertThat(marked.getErrorMessage()).isEqualTo("模型响应超时");
        assertThat(marked.getFinishedAt()).isNotNull();
    }

    /** 捕获最近一次把作业标记为 FAILED 的 updateById 参数（markJobFailed 使用新实体落库） */
    private PlanningJobEntity failedUpdate() {
        ArgumentCaptor<PlanningJobEntity> updated = ArgumentCaptor.forClass(PlanningJobEntity.class);
        verify(jobMapper, org.mockito.Mockito.atLeastOnce()).updateById(updated.capture());
        return updated.getAllValues().stream()
                .filter(entity -> "FAILED".equals(entity.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未发现 FAILED 状态更新"));
    }

    @Test
    void workerFailsWithoutAvailabilityRules() throws Exception {
        slots.clear();
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());
        when(goalMapper.selectById(100L)).thenReturn(activeGoal());
        when(jobMapper.selectOne(any())).thenReturn(null);
        when(jobMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.selectCount(any())).thenReturn(0L);
        when(pythonAi.planRecommendations(any())).thenAnswer(inv -> {
            PythonAiServiceClient.PlanRecommendationRequest request = inv.getArgument(0);
            assertThat(request.weeklyAvailableMinutes()).isZero();
            return new PythonAiServiceClient.PlanRecommendationResult(List.of(
                    new PythonAiServiceClient.PlanTaskItem("学习 Java 变量", "LEARNING", "HIGH", 30,
                            List.of(1L), List.of(), "掌握变量声明", List.of(),
                            List.of("能写出变量声明"), "基础第一步")), "test-prompt-v1");
        });

        PlanningJobEntity job = service.submitPlanningJob("goal-1", initialRequest(), "key-1");
        submitted.forEach(Runnable::run);

        assertThat(failedUpdate().getStatus()).isEqualTo("FAILED");
        assertThat(job.getPlanVersionId()).isNull();
        verify(pythonAi, never()).planRecommendations(any());
    }

    @Test
    void dualProfileVersionsAndSnapshotStageEnterPlanningContext() throws Exception {
        LearningGoalEntity goal = activeGoal();
        goal.setProfileVersionId(15L);
        when(goalMapper.selectOne(any())).thenReturn(goal);
        when(goalMapper.selectById(100L)).thenReturn(goal);
        when(goalMapper.lockById(100L)).thenReturn(goal);
        when(jobMapper.selectOne(any())).thenReturn(null);
        when(jobMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.selectCount(any())).thenReturn(0L);
        when(ruleBasedPlanner.schedule(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(draft(new RuleBasedPlanner.TaskContent("学习 Java", "STUDY", "HIGH", 30,
                        List.of(1L), List.of(), "掌握 Java", List.of(), false,
                        List.of("完成练习"), "画像驱动"), 0)));
        when(pythonAi.planRecommendations(any())).thenAnswer(inv -> {
            PythonAiServiceClient.PlanRecommendationRequest request = inv.getArgument(0);
            assertThat(request.currentStage()).isEqualTo("ADVANCED");
            return new PythonAiServiceClient.PlanRecommendationResult(List.of(planTask("学习 Java", 1L)), "v1");
        });

        service.submitPlanningJob("goal-1", initialRequest(), "dual-profile");
        submitted.forEach(Runnable::run);

        ArgumentCaptor<PlanVersionEntity> captured = ArgumentCaptor.forClass(PlanVersionEntity.class);
        verify(versionMapper).insert(captured.capture());
        Map<String,Object> context = objectMapper.readValue(captured.getValue().getContextSnapshotJson(), Map.class);
        assertThat(context).containsEntry("goalProfileVersionId", "15")
                .containsEntry("goalProfileVersionNo", 1)
                .containsEntry("semanticProfileSource", "GOAL_PROFILE_VERSION")
                .containsEntry("schedulingProfileVersionId", "20")
                .containsEntry("schedulingProfileVersionNo", 2);
        assertThat(context).containsEntry("userId", "1").containsEntry("goalId", "100");
        assertThat(context.get("planningContextFingerprint")).isNotNull();
        verify(jdbc, never()).query(org.mockito.ArgumentMatchers.contains("user_profile_direction"),
                any(ResultSetExtractor.class), any(Object[].class));
    }

    @Test
    void optimizationStoresCompleteBeforeSnapshot() throws Exception {
        LearningGoalEntity goal = activeGoal();
        LearningTaskEntity current = new LearningTaskEntity();
        current.setId(501L); current.setPublicId("task-501"); current.setUserId(1L);
        current.setGoalId(100L); current.setProjectId(null); current.setTitle("旧任务");
        current.setDescription("旧描述"); current.setTaskType("STUDY"); current.setPriority("MEDIUM");
        current.setEstimatedMinutes(45); current.setScheduledStart(Instant.parse("2026-08-03T11:00:00Z"));
        current.setDueAt(Instant.parse("2026-08-03T12:00:00Z")); current.setLockedSchedule(false);
        current.setLifecycleStatus("NOT_STARTED"); current.setAcceptanceJson("[\"完成旧练习\"]"); current.setVersion(3);
        when(goalMapper.selectOne(any())).thenReturn(goal);
        when(goalMapper.selectById(100L)).thenReturn(goal);
        when(goalMapper.lockById(100L)).thenReturn(goal);
        when(jobMapper.selectOne(any())).thenReturn(null);
        when(jobMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectList(any())).thenReturn(List.of(current));
        when(pythonAi.planRecommendations(any())).thenReturn(
                new PythonAiServiceClient.PlanRecommendationResult(List.of(planTask("新任务", 1L)), "v1"));
        when(ruleBasedPlanner.schedule(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(draft(new RuleBasedPlanner.TaskContent("新任务", "STUDY", "HIGH", 30,
                        List.of(1L), List.of(), "新目标", List.of(), false,
                        List.of("完成新练习"), "优化"), 0)));

        service.submitPlanningJob("goal-1", new PlanningService.JobRequest(
                "OPTIMIZATION", null, "调整", List.of()), "optimization-before");
        submitted.forEach(Runnable::run);

        ArgumentCaptor<PlanChangeItemEntity> captured = ArgumentCaptor.forClass(PlanChangeItemEntity.class);
        verify(changeMapper).insert(captured.capture());
        assertThat(captured.getValue().getAction()).isEqualTo("RESCHEDULE_TASK");
        Map<String,Object> before = objectMapper.readValue(captured.getValue().getBeforeJson(), Map.class);
        assertThat(before).containsKeys("title", "description", "taskType", "priority", "scheduledStart",
                "dueAt", "estimatedMinutes", "lockedSchedule", "knowledgePointIds",
                "knowledgeSources", "acceptanceCriteria");
        assertThat(before).containsEntry("title", "旧任务").containsEntry("description", "旧描述");
    }

    @Test
    void readsDependenciesAndProficientMasteryIntoPythonRequest() {
        catalogKnowledge.clear();
        catalogKnowledge.add(Map.of("id", 1L, "name", "A"));
        catalogKnowledge.add(Map.of("id", 2L, "name", "B"));
        catalogDependencies.add(new KnowledgePrerequisitePolicy.Dependency(1L, 2L));
        proficientKnowledgePointIds.add(1L);
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());
        when(goalMapper.selectById(100L)).thenReturn(activeGoal());
        when(jobMapper.selectOne(any())).thenReturn(null);
        when(jobMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.selectCount(any())).thenReturn(0L);
        when(pythonAi.planRecommendations(any())).thenAnswer(inv -> {
            PythonAiServiceClient.PlanRecommendationRequest request = inv.getArgument(0);
            assertThat(request.knowledgeDependencies()).containsExactly(
                    new PythonAiServiceClient.KnowledgeDependency(1L, 2L));
            assertThat(request.satisfiedPrerequisiteIds()).containsExactly(1L);
            return new PythonAiServiceClient.PlanRecommendationResult(List.of(
                    planTask("B", 2L), planTask("A review", 1L)), "test-prerequisite-v1");
        });
        when(ruleBasedPlanner.schedule(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt())).thenAnswer(inv -> {
            List<RuleBasedPlanner.TaskContent> contents = inv.getArgument(0);
            // PROFICIENT means B does not have to wait for the optional A review.
            assertThat(contents).extracting(RuleBasedPlanner.TaskContent::title)
                    .containsExactly("B", "A review");
            return List.of(draft(contents.get(0), 0), draft(contents.get(1), 1));
        });

        service.submitPlanningJob("goal-1", initialRequest(), "dependency-key");
        submitted.forEach(Runnable::run);

        verify(jdbc).query(org.mockito.ArgumentMatchers.contains("FROM knowledge_dependency"),
                any(RowMapper.class), eq(1L), eq(1L));
        verify(jdbc).query(org.mockito.ArgumentMatchers.contains("level='PROFICIENT'"),
                any(RowMapper.class), eq(1L));
    }

    @Test
    void ordinaryReverseIsCorrectedBeforeRuleBasedScheduling() {
        catalogKnowledge.clear();
        catalogKnowledge.add(Map.of("id", 1L, "name", "A"));
        catalogKnowledge.add(Map.of("id", 2L, "name", "B"));
        catalogDependencies.add(new KnowledgePrerequisitePolicy.Dependency(1L, 2L));
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());
        when(goalMapper.selectById(100L)).thenReturn(activeGoal());
        when(jobMapper.selectOne(any())).thenReturn(null);
        when(jobMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.selectCount(any())).thenReturn(0L);
        when(pythonAi.planRecommendations(any())).thenReturn(
                new PythonAiServiceClient.PlanRecommendationResult(
                        List.of(planTask("B", 2L), planTask("A", 1L)), "legacy-python"));
        when(ruleBasedPlanner.schedule(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt())).thenAnswer(inv -> {
            List<RuleBasedPlanner.TaskContent> contents = inv.getArgument(0);
            assertThat(contents).extracting(RuleBasedPlanner.TaskContent::title)
                    .containsExactly("A", "B");
            return List.of(draft(contents.get(0), 0), draft(contents.get(1), 1));
        });

        service.submitPlanningJob("goal-1", initialRequest(), "reverse-key");
        submitted.forEach(Runnable::run);
    }

    @Test
    void customDirectionSendsEmptyPrerequisiteContext() {
        LearningGoalEntity custom = activeGoal();
        custom.setDirectionId(null);
        custom.setCustomDirection("自定义探索");
        when(goalMapper.selectOne(any())).thenReturn(custom);
        when(goalMapper.selectById(100L)).thenReturn(custom);
        when(goalMapper.lockById(100L)).thenReturn(custom);
        when(jobMapper.selectOne(any())).thenReturn(null);
        when(jobMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.selectCount(any())).thenReturn(0L);
        when(pythonAi.planRecommendations(any())).thenAnswer(inv -> {
            PythonAiServiceClient.PlanRecommendationRequest request = inv.getArgument(0);
            assertThat(request.explorationMode()).isTrue();
            assertThat(request.knowledgeDependencies()).isEmpty();
            assertThat(request.satisfiedPrerequisiteIds()).isEmpty();
            return new PythonAiServiceClient.PlanRecommendationResult(
                    List.of(planTask("探索", new Long[0])), "custom-v1");
        });
        when(ruleBasedPlanner.schedule(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt())).thenAnswer(inv -> {
            List<RuleBasedPlanner.TaskContent> contents = inv.getArgument(0);
            return List.of(draft(contents.get(0), 0));
        });

        service.submitPlanningJob("goal-1", initialRequest(), "custom-key");
        submitted.forEach(Runnable::run);

        verify(jdbc, never()).query(org.mockito.ArgumentMatchers.contains("FROM knowledge_dependency"),
                any(RowMapper.class), any(Object[].class));
    }

    @Test
    void tasksWithoutBusinessPrerequisiteAreNotForcedIntoLinearDependency() {
        publishedTaskIds.addAll(List.of(11L, 12L, 13L));

        ReflectionTestUtils.invokeMethod(service, "rebuildTaskDependencies", 100L, null, List.of());

        verify(jdbc, never()).update(eq("INSERT INTO task_dependency(predecessor_task_id,successor_task_id) VALUES(?,?)"),
                any(), any());
    }

    @Test
    void stableClientRefsPersistMilestoneAndExactPrerequisiteEdge() throws Exception {
        LearningPlanEntity plan = new LearningPlanEntity(); plan.setId(300L); plan.setUserId(1L);
        plan.setGoalId(100L); plan.setProjectId(200L);
        PlanVersionEntity version = new PlanVersionEntity(); version.setId(400L);
        PlanChangeItemEntity first = change("task-a", null, "[]");
        PlanChangeItemEntity second = change("task-b", null, "[\"task-a\"]");
        doAnswer(inv -> { LearningTaskEntity task=inv.getArgument(0); task.setId(
                "任务A".equals(task.getTitle())?501L:502L); return 1; }).when(taskMapper).insert(any(LearningTaskEntity.class));
        List<String> changed = new ArrayList<>();

        ReflectionTestUtils.invokeMethod(service,"applyChange",first,plan,version,changed);
        ReflectionTestUtils.invokeMethod(service,"applyChange",second,plan,version,changed);
        ReflectionTestUtils.invokeMethod(service,"rebuildTaskDependencies",100L,200L,List.of(first,second));

        ArgumentCaptor<LearningTaskEntity> tasks=ArgumentCaptor.forClass(LearningTaskEntity.class);
        verify(taskMapper,times(2)).insert(tasks.capture());
        assertThat(tasks.getAllValues()).extracting(LearningTaskEntity::getMilestoneId).containsOnly(900L);
        assertThat(first.getTargetTaskId()).isEqualTo(501L);
        assertThat(second.getTargetTaskId()).isEqualTo(502L);
        verify(jdbc).update("INSERT INTO task_dependency(predecessor_task_id,successor_task_id) VALUES(?,?)",501L,502L);
    }

    private PlanChangeItemEntity change(String clientRef,Long target,String dependencies) throws Exception {
        Map<String,Object>after=new LinkedHashMap<>();after.put("clientRef",clientRef);
        after.put("title","task-a".equals(clientRef)?"任务A":"任务B");after.put("description","目标");
        after.put("taskType","LEARNING");after.put("priority","HIGH");after.put("estimatedMinutes",30);
        after.put("scheduledStart","2026-08-10T08:00:00+08:00[Asia/Shanghai]");
        after.put("dueAt","2026-08-10T09:00:00+08:00[Asia/Shanghai]");after.put("lockedSchedule",false);
        after.put("milestoneId","900");after.put("knowledgePointIds",List.of());after.put("knowledgeSources",List.of());
        after.put("acceptanceCriteria",List.of("提交成果"));after.put("dependencyTaskIds",objectMapper.readValue(dependencies,List.class));
        PlanChangeItemEntity item=new PlanChangeItemEntity();item.setAction("ADD_TASK");item.setClientRef(clientRef);
        item.setTargetTaskId(target);item.setAfterJson(objectMapper.writeValueAsString(after));item.setReason("test");return item;
    }

    private PythonAiServiceClient.PlanTaskItem planTask(String title, Long... knowledgePointIds) {
        return new PythonAiServiceClient.PlanTaskItem(title, "LEARNING", "HIGH", 30,
                List.of(knowledgePointIds), List.of(), "按前置顺序学习", List.of(),
                List.of("完成可验收练习"), "保持前置顺序");
    }

    private RuleBasedPlanner.TaskDraft draft(RuleBasedPlanner.TaskContent content, int dayOffset) {
        ZonedDateTime start = ZonedDateTime.of(2026, 8, 3 + dayOffset, 19, 0, 0, 0,
                ZoneId.of("Asia/Shanghai"));
        return new RuleBasedPlanner.TaskDraft(content.title(), content.taskType(), content.priority(),
                start, start.plusMinutes(content.estimatedMinutes()), content.estimatedMinutes(),
                content.knowledgePointIds(), content.knowledgeSources(), content.learningObjective(),
                content.sourceQueries(), content.explorationRequired(), content.acceptance(),
                content.reason());
    }

    @Test
    void duplicateIdempotencyKeyReturnsExistingJobWithoutResubmit() {
        PlanningJobEntity existing = new PlanningJobEntity();
        existing.setId(200L); existing.setPublicId("job-old"); existing.setStatus("SUCCEEDED");
        existing.setRequestHash("req-hash");
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());
        when(jobMapper.selectOne(any())).thenReturn(existing);

        PlanningJobEntity job = service.submitPlanningJob("goal-1", initialRequest(), "key-1");

        assertThat(job.getPublicId()).isEqualTo("job-old");
        assertThat(submitted).isEmpty();
        verify(jobMapper, never()).insert(any(PlanningJobEntity.class));
    }

    @Test
    void sameKeyWithDifferentRequestRejected() {
        PlanningJobEntity existing = new PlanningJobEntity();
        existing.setId(200L); existing.setRequestHash("other-hash");
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());
        when(jobMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> service.submitPlanningJob("goal-1", initialRequest(), "key-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同一幂等键不能用于不同规划请求");
        assertThat(submitted).isEmpty();
    }

    @Test
    void runningJobBlocksNewSubmission() {
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());
        when(jobMapper.selectOne(any())).thenReturn(null);
        when(jobMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.submitPlanningJob("goal-1", initialRequest(), "key-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("该目标已有运行中的规划作业");
        assertThat(submitted).isEmpty();
    }

    @Test
    void staleRunningJobExpiredBeforeSubmission() {
        PlanningJobEntity stale = new PlanningJobEntity();
        stale.setId(150L); stale.setStatus("RUNNING");
        stale.setStartedAt(Instant.now().minusSeconds(3600));
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());
        when(jobMapper.selectOne(any())).thenReturn(null);
        when(jobMapper.selectList(any())).thenReturn(List.of(stale));
        when(jobMapper.selectCount(any())).thenReturn(0L);

        service.submitPlanningJob("goal-1", initialRequest(), "key-1");

        ArgumentCaptor<PlanningJobEntity> updated = ArgumentCaptor.forClass(PlanningJobEntity.class);
        verify(jobMapper).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo(150L);
        assertThat(updated.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(updated.getValue().getErrorCode()).isEqualTo("SERVICE_TEMPORARILY_UNAVAILABLE");
        assertThat(updated.getValue().getErrorMessage()).contains("服务中断");
    }

    @Test
    void latestJobForGoalReturnsMostRecentJob() {
        PlanningJobEntity running = new PlanningJobEntity();
        running.setId(200L); running.setPublicId("job-running"); running.setUserId(1L); running.setGoalId(100L);
        running.setJobType("INITIAL"); running.setStatus("RUNNING"); running.setStartedAt(Instant.now());
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());
        when(jobMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.selectOne(any())).thenReturn(running);

        PlanningService.JobView view = service.latestJobForGoal("goal-1");

        assertThat(view).isNotNull();
        assertThat(view.publicId()).isEqualTo("job-running");
        assertThat(view.status()).isEqualTo("RUNNING");
    }

    @Test
    void latestJobForGoalReturnsNullWhenNoJobEverSubmitted() {
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());
        when(jobMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.selectOne(any())).thenReturn(null);

        assertThat(service.latestJobForGoal("goal-1")).isNull();
    }

    @Test
    void effectivePlanReturnsNullWhenNothingPublished() {
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());
        // setUp 的 SQL 分发器对含 plan_publication 的查询返回 null → 从未发布
        assertThat(service.effectivePlan("goal-1")).isNull();
    }

    @Test
    void effectivePlanReturnsLatestPublishedVersionAndDate() {
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());
        // 后注册桩覆盖 setUp 分发器：plan_publication 查询返回已发布版本行
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(Map.of("versionId", 400L, "publishedAt", Instant.parse("2026-08-05T12:00:00Z")));
        PlanVersionEntity published = new PlanVersionEntity();
        published.setId(400L); published.setPublicId("v-2"); published.setPlanId(300L);
        published.setVersionNo(2); published.setStatus("PUBLISHED");
        when(versionMapper.selectById(400L)).thenReturn(published);
        when(stageMapper.selectList(any())).thenReturn(List.of(new PlanStageEntity()));

        PlanningService.EffectivePlanView view = service.effectivePlan("goal-1");

        assertThat(view).isNotNull();
        assertThat(view.version().getVersionNo()).isEqualTo(2);
        assertThat(view.version().getStatus()).isEqualTo("PUBLISHED");
        assertThat(view.publishedAt()).isEqualTo(Instant.parse("2026-08-05T12:00:00Z"));
        assertThat(view.stages()).hasSize(1);
    }
}
