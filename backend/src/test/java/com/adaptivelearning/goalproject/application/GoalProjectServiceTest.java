package com.adaptivelearning.goalproject.application;

import com.adaptivelearning.goalproject.domain.GoalProjectEntity;
import com.adaptivelearning.goalproject.domain.GoalStatus;
import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
import com.adaptivelearning.goalproject.domain.LearningProjectEntity;
import com.adaptivelearning.goalproject.domain.MilestoneEntity;
import com.adaptivelearning.evaluation.application.AssessmentService;
import com.adaptivelearning.execution.application.TaskCancellationService;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.GoalMapper;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.GoalProjectMapper;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.MilestoneMapper;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.ProjectMapper;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.security.CurrentUser;
import com.adaptivelearning.support.application.AuditService;
import com.adaptivelearning.support.domain.UserEntity;
import com.adaptivelearning.support.infrastructure.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

class GoalProjectServiceTest {
    private final GoalMapper goals = mock(GoalMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final MilestoneMapper milestones = mock(MilestoneMapper.class);
    private final GoalProjectMapper goalLinks = mock(GoalProjectMapper.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final AssessmentService assessments = mock(AssessmentService.class);
    private final GoalRecommendationBatchStore recommendationBatches = mock(GoalRecommendationBatchStore.class);
    private final UserMapper users = mock(UserMapper.class);
    private final TaskCancellationService taskCancellation = mock(TaskCancellationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final GoalProjectService service = new GoalProjectService(goals, projects, milestones,
            goalLinks, jdbc, objectMapper, audit, assessments, recommendationBatches, users, taskCancellation);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void customGoalCanUseCatalogDirectionBeforeProfileExists() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(42L, "u-42", "student", "", Set.of("STUDENT"), Set.of()), null, List.of()));
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM learning_direction"),
                eq(Integer.class), eq(200L))).thenReturn(1);

        service.createGoal(new GoalProjectService.GoalInput(200L, null, "经济学一周入门", "SKILL",
                "先建立基本框架", "MEDIUM", LocalDate.now(), LocalDate.now().plusDays(6),
                420, List.of(Map.of("type", "OUTCOME", "description", "完成笔记", "completed", false)),
                null, null, "CUSTOM", null, null, null));

        ArgumentCaptor<LearningGoalEntity> captor = ArgumentCaptor.forClass(LearningGoalEntity.class);
        verify(goals).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(42L);
        assertThat(captor.getValue().getDirectionId()).isEqualTo(200L);
        assertThat(captor.getValue().getStatus()).isEqualTo("DRAFT");
        assertThat(captor.getValue().getSourceType()).isEqualTo("CUSTOM");
        verify(jdbc, never()).queryForObject(startsWith("SELECT COUNT(*) FROM user_profile_direction"),
                eq(Integer.class), any(), any());
    }

    @Test
    void customGoalCanUseFreeTextDirectionBeforeProfileExists() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(42L, "u-42", "student", "", Set.of("STUDENT"), Set.of()), null, List.of()));

        service.createGoal(new GoalProjectService.GoalInput(null, "摄影理论", "摄影理论一周入门", "SKILL",
                "从个人资料开始学习", "MEDIUM", LocalDate.now(), LocalDate.now().plusDays(6),
                420, List.of(Map.of("type", "OUTCOME", "description", "完成作品分析", "completed", false)),
                null, null, "CUSTOM", null, null, null));

        ArgumentCaptor<LearningGoalEntity> captor = ArgumentCaptor.forClass(LearningGoalEntity.class);
        verify(goals).insert(captor.capture());
        assertThat(captor.getValue().getDirectionId()).isNull();
        assertThat(captor.getValue().getCustomDirection()).isEqualTo("摄影理论");
        assertThat(captor.getValue().getStatus()).isEqualTo("DRAFT");
        verifyNoInteractions(jdbc);
    }

    @Test
    void recommendedGoalUsesServerVerifiedBatchAndNeverFuzzyMapsCustomDirection() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(42L, "u-42", "student", "", Set.of("STUDENT"), Set.of()), null, List.of()));
        GoalRecommendationContext context = recommendationContext(
                new GoalRecommendationContext.Direction(200L, "经济学", "BEGINNER", true));
        GoalRecommendationService.Recommendation candidate = recommendation(
                "rec-1", 200L, null, "BEGINNER", "AI");
        when(recommendationBatches.verifyForAdoption(42L, "rec-1", "AI_RECOMMENDED", 700L))
                .thenReturn(new GoalRecommendationBatchStore.VerifiedRecommendation(
                        "AI_RECOMMENDED", 700L, 7, candidate, context));
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM learning_direction"),
                eq(Integer.class), eq(200L))).thenReturn(1);

        service.createGoal(new GoalProjectService.GoalInput(200L, null, "经济学一周入门", "SKILL",
                "来自画像推荐", "MEDIUM", LocalDate.now(), LocalDate.now().plusDays(6),
                420, List.of(Map.of("type", "OUTCOME", "description", "完成笔记", "completed", false)),
                null, null, "AI_RECOMMENDED", 700L, "rec-1", "匹配经济学画像"));

        ArgumentCaptor<LearningGoalEntity> captor = ArgumentCaptor.forClass(LearningGoalEntity.class);
        verify(goals).insert(captor.capture());
        assertThat(captor.getValue().getDirectionId()).isEqualTo(200L);
        assertThat(captor.getValue().getSourceType()).isEqualTo("AI_RECOMMENDED");
        assertThat(captor.getValue().getProfileVersionId()).isEqualTo(700L);
        assertThat(captor.getValue().getRecommendationSnapshotJson()).contains("rec-1", "originalCandidate");
        verify(recommendationBatches).verifyForAdoption(42L, "rec-1", "AI_RECOMMENDED", 700L);
    }

    @Test
    void recommendedGoalCanKeepCustomProfileDirection() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(42L, "u-42", "student", "", Set.of("STUDENT"), Set.of()), null, List.of()));
        GoalRecommendationContext context = recommendationContext(
                new GoalRecommendationContext.Direction(null, "心理学", "BEGINNER", true));
        GoalRecommendationService.Recommendation candidate = recommendation(
                "rec-custom", null, "心理学", "BEGINNER", "AI");
        when(recommendationBatches.verifyForAdoption(42L, "rec-custom", "AI_RECOMMENDED", 700L))
                .thenReturn(new GoalRecommendationBatchStore.VerifiedRecommendation(
                        "AI_RECOMMENDED", 700L, 7, candidate, context));

        service.createGoal(new GoalProjectService.GoalInput(null, "心理学", "心理学一周入门", "SKILL",
                "来自自定义画像的推荐", "MEDIUM", LocalDate.now(), LocalDate.now().plusDays(6),
                420, List.of(Map.of("type", "OUTCOME", "description", "完成概念图", "completed", false)),
                null, null, "AI_RECOMMENDED", 700L, "rec-custom", "匹配心理学画像"));

        ArgumentCaptor<LearningGoalEntity> captor = ArgumentCaptor.forClass(LearningGoalEntity.class);
        verify(goals).insert(captor.capture());
        assertThat(captor.getValue().getDirectionId()).isNull();
        assertThat(captor.getValue().getCustomDirection()).isEqualTo("心理学");
        assertThat(captor.getValue().getSourceType()).isEqualTo("AI_RECOMMENDED");
    }

    @Test
    void forgedRecommendationIdIsRejectedBeforeGoalInsert() {
        setStudent();
        when(recommendationBatches.verifyForAdoption(42L, "forged", "AI_RECOMMENDED", 700L))
                .thenThrow(new BusinessException(com.adaptivelearning.shared.exception.ErrorCode.RESOURCE_NOT_FOUND,
                        "推荐候选不存在"));
        GoalProjectService.GoalInput input = new GoalProjectService.GoalInput(
                null, "心理学", "编辑后的真实业务目标", "SKILL", "保留用户编辑能力", "MEDIUM",
                LocalDate.now(), LocalDate.now().plusDays(10), 300,
                List.of(Map.of("type", "OUTCOME", "description", "提交成果", "completed", false)),
                null, null, "AI_RECOMMENDED", 700L, "forged", "客户端理由");

        assertThatThrownBy(() -> service.createGoal(input)).isInstanceOf(BusinessException.class);
        verify(goals, never()).insert(any(LearningGoalEntity.class));
    }

    private GoalRecommendationContext recommendationContext(GoalRecommendationContext.Direction direction) {
        return new GoalRecommendationContext(700L, 7, LocalDate.now(), LocalDate.now().plusDays(30),
                "背景", List.of(direction), Map.of("capacityRatio", 0.5), 600,
                new BigDecimal("0.5"), 1, new BigDecimal("0.2"), 2, 2, List.of());
    }

    private GoalRecommendationService.Recommendation recommendation(String id, Long directionId,
                                                                      String customDirection,
                                                                      String stage, String source) {
        return new GoalRecommendationService.Recommendation(id, directionId, customDirection,
                directionId == null ? customDirection : "经济学", stage, "候选目标", "SKILL",
                "候选说明", "MEDIUM", LocalDate.now(), LocalDate.now().plusDays(10), 300,
                List.of(Map.of("type", "OUTCOME", "description", "提交成果", "completed", false)),
                "正式画像候选", List.of("完成资料", "完成成果"), source);
    }

    private void setStudent() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(42L, "u-42", "student", "", Set.of("STUDENT"), Set.of()), null, List.of()));
        when(users.lockById(42L)).thenReturn(new UserEntity());
    }

    private GoalProjectService.GoalInput customInput(String type, String name) {
        return new GoalProjectService.GoalInput(200L, null, name, type, "实践验证", "MEDIUM",
                LocalDate.now(), LocalDate.now().plusDays(30), 420,
                List.of(Map.of("type", "OUTCOME", "description", "完成验收", "completed", false)),
                null, null, "CUSTOM", null, null, null);
    }

    private LearningGoalEntity goalEntity(String publicId, String status, String type) {
        LearningGoalEntity e = new LearningGoalEntity();
        e.setPublicId(publicId);
        e.setUserId(42L);
        e.setStatus(status);
        e.setType(type);
        return e;
    }

    private LearningProjectEntity projectEntity(String publicId, String status) {
        LearningProjectEntity e = new LearningProjectEntity();
        e.setId(1L);
        e.setPublicId(publicId);
        e.setUserId(42L);
        e.setStatus(status);
        e.setStartDate(LocalDate.now());
        e.setDueDate(LocalDate.now().plusDays(30));
        return e;
    }

    @Test
    void projectGoalCreationKeepsSkillBehaviorWithoutProject() {
        setStudent();
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM learning_direction"),
                eq(Integer.class), eq(200L))).thenReturn(1);

        GoalProjectService.CreateGoalResult result = service.createGoalWithProject(
                customInput("SKILL", "经济学一周入门"), List.of("数据库设计"), "https://example.com/repo");

        assertThat(result.goal()).isNotNull();
        assertThat(result.project()).isNull();
        verify(goals).insert(any(LearningGoalEntity.class));
        verify(projects, never()).insert(any(LearningProjectEntity.class));
    }

    @Test
    void projectGoalWithEmptyMilestonesIsRejected() {
        setStudent();
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM learning_direction"),
                eq(Integer.class), eq(200L))).thenReturn(1);

        assertThatThrownBy(() -> service.createGoalWithProject(
                customInput("PROJECT", "图书管理系统"), List.of("  ", ""), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少填写一个里程碑");
        verify(projects, never()).insert(any(LearningProjectEntity.class));
    }

    @Test
    void projectGoalCreationCreatesLinkedProjectAndMilestones() {
        setStudent();
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM learning_direction"),
                eq(Integer.class), eq(200L))).thenReturn(1);
        when(jdbc.queryForObject(startsWith("SELECT COALESCE(SUM(contribution_weight),0) FROM goal_project"),
                eq(BigDecimal.class), any())).thenReturn(BigDecimal.ZERO);
        when(projects.selectOne(any())).thenReturn(projectEntity("proj-1", "DRAFT"));
        when(projects.lockByPublicId(anyString())).thenReturn(projectEntity("proj-1", "DRAFT"));
        when(goals.selectOne(any())).thenReturn(goalEntity("goal-1", "DRAFT", "PROJECT"));

        GoalProjectService.CreateGoalResult result = service.createGoalWithProject(
                customInput("PROJECT", "图书管理系统"), List.of("数据库设计", "后端 API"),
                "https://github.com/x/book-manager");

        assertThat(result.goal()).isNotNull();
        assertThat(result.project()).isNotNull();
        verify(goals).insert(any(LearningGoalEntity.class));
        verify(projects).insert(any(LearningProjectEntity.class));
        verify(goalLinks).insert(any(GoalProjectEntity.class));
        verify(milestones, times(2)).insert(any(MilestoneEntity.class));
    }

    @Test
    void activatingProjectGoalAlsoActivatesLinkedDraftProject() {
        setStudent();
        LearningGoalEntity goal = goalEntity("goal-1", "DRAFT", "PROJECT");
        goal.setId(1L);
        goal.setName("图书管理系统");
        goal.setWeeklyBudgetMinutes(420);
        goal.setSuccessCriteriaJson("[{\"type\":\"OUTCOME\",\"description\":\"完成验收\",\"completed\":false}]");
        goal.setStartDate(LocalDate.now());
        goal.setDueDate(LocalDate.now().plusDays(30));
        when(goals.selectOne(any())).thenReturn(goal);
        when(goals.lockOwnedByPublicId("goal-1", 42L)).thenReturn(goal);

        when(jdbc.queryForObject(startsWith("SELECT COALESCE(SUM(available_minutes),0) FROM availability_rule"),
                eq(Integer.class), eq(42L))).thenReturn(600);
        when(jdbc.query(startsWith("SELECT capacity_ratio FROM learning_preference"),
                any(ResultSetExtractor.class), eq(42L))).thenReturn(new BigDecimal("0.85"));

        LearningProjectEntity draft = projectEntity("proj-1", "DRAFT");
        draft.setId(1L);
        draft.setDueDate(LocalDate.now().plusDays(30));
        when(jdbc.query(startsWith("SELECT p.id"), any(RowMapper.class), eq(1L), eq(42L)))
                .thenReturn(List.of(1L));
        when(projects.selectById(1L)).thenReturn(draft);
        when(projects.selectOne(any())).thenReturn(draft);
        when(projects.lockByPublicId("proj-1")).thenReturn(draft);
        when(projects.updateById(draft)).thenReturn(1);

        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM goal_project gp"),
                eq(Integer.class), eq(1L))).thenReturn(1);
        when(jdbc.queryForObject(startsWith("SELECT COALESCE(SUM(weight),0) FROM milestone WHERE project_id=?"),
                eq(BigDecimal.class), eq(1L))).thenReturn(new BigDecimal("1.0"));
        when(jdbc.query(startsWith("SELECT MIN(g.due_date)"),
                any(ResultSetExtractor.class), eq(1L))).thenReturn(LocalDate.now().plusDays(30));
        when(goals.updateById(any(LearningGoalEntity.class))).thenReturn(1);

        service.transitionGoal("goal-1", GoalStatus.ACTIVE, "用户确认开始推进", false);

        assertThat(goal.getStatus()).isEqualTo("ACTIVE");
        assertThat(draft.getStatus()).isEqualTo("ACTIVE");
        verify(projects).updateById(draft);
        verify(goals).updateById(goal);
    }

    @Test
    void activeProjectCannotBeStructurallyEdited() {
        setStudent();
        LearningProjectEntity active = projectEntity("proj-1", "ACTIVE");
        when(projects.lockByPublicId("proj-1")).thenReturn(active);
        GoalProjectService.ProjectInput input = new GoalProjectService.ProjectInput(
                null, "修改项目", "说明", LocalDate.now(), LocalDate.now().plusDays(10),
                "MEDIUM", List.of(), null, active.getVersion());

        assertThatThrownBy(() -> service.updateProject("proj-1", input))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("启动后不能修改");
        verify(projects, never()).updateById(any(LearningProjectEntity.class));
    }

    @Test
    void activeProjectCannotLinkOrUnlinkGoalsAndWeightCannotExceedOne() {
        setStudent();
        LearningProjectEntity active = projectEntity("proj-active", "ACTIVE");
        when(projects.lockByPublicId("proj-active")).thenReturn(active);
        assertThatThrownBy(() -> service.linkGoal("proj-active", "goal-1", BigDecimal.ONE))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.unlinkGoal("proj-active", "goal-1"))
                .isInstanceOf(BusinessException.class);

        LearningProjectEntity draft = projectEntity("proj-draft", "DRAFT");
        LearningGoalEntity goal = goalEntity("goal-1", "ACTIVE", "SKILL");
        goal.setId(10L);
        when(projects.lockByPublicId("proj-draft")).thenReturn(draft);
        when(goals.selectOne(any())).thenReturn(goal);
        when(jdbc.queryForObject(startsWith("SELECT COALESCE(SUM(contribution_weight),0)"),
                eq(BigDecimal.class), eq(10L))).thenReturn(new BigDecimal("0.6"));
        assertThatThrownBy(() -> service.linkGoal("proj-draft", "goal-1", new BigDecimal("0.5")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("100%");
    }

    @Test
    void crossUserProjectRowLockCannotAuthorizeMutation() {
        setStudent();
        LearningProjectEntity other = projectEntity("other-project", "DRAFT");
        other.setUserId(99L);
        when(projects.lockByPublicId("other-project")).thenReturn(other);
        GoalProjectService.ProjectInput input = new GoalProjectService.ProjectInput(
                null, "其他用户项目", "说明", LocalDate.now(), LocalDate.now().plusDays(10),
                "MEDIUM", List.of(), null, other.getVersion());

        assertThatThrownBy(() -> service.updateProject("other-project", input))
                .isInstanceOf(BusinessException.class).hasMessageContaining("资源不存在");
    }

    @Test
    void milestoneCompletionUsesDatabaseCriteriaAndEmitsSideEffectsOnce() {
        setStudent();
        LearningProjectEntity active = projectEntity("proj-1", "ACTIVE");
        MilestoneEntity milestone = new MilestoneEntity();
        milestone.setId(11L);
        milestone.setProjectId(active.getId());
        milestone.setPublicId("ms-1");
        milestone.setStatus("NOT_STARTED");
        milestone.setVersion(2);
        milestone.setWeight(new BigDecimal("0.5"));
        milestone.setAcceptanceJson("[{\"description\":\"提交结构化笔记\"},{\"description\":\"完成测验\"}]");
        when(milestones.selectOne(any())).thenReturn(milestone);
        when(milestones.lockByPublicId("ms-1")).thenReturn(milestone);
        when(projects.selectOne(any())).thenReturn(active);
        when(projects.lockById(active.getId())).thenReturn(active);
        when(milestones.updateById(milestone)).thenReturn(1);

        GoalProjectService.MilestoneCompletionInput input = new GoalProjectService.MilestoneCompletionInput(
                2, "提交笔记与测验截图", List.of(
                Map.of("index", 0, "confirmed", true, "evidence", "笔记链接"),
                Map.of("index", 1, "confirmed", true, "evidence", "测验截图")));
        MilestoneEntity result = service.completeMilestone("ms-1", input);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getCompletionEvidenceJson()).contains("提交结构化笔记", "完成测验");
        verify(assessments, times(1)).recordProjectMilestoneEvidence(
                eq(42L), eq(active.getId()), eq(11L), eq(new BigDecimal("0.5")), any());
        verify(jdbc, times(1)).update(startsWith("INSERT INTO outbox_event"), any(Object[].class));

        service.completeMilestone("ms-1", input);
        verify(assessments, times(1)).recordProjectMilestoneEvidence(anyLong(), anyLong(), anyLong(), any(), any());
        verify(jdbc, times(1)).update(startsWith("INSERT INTO outbox_event"), any(Object[].class));
    }

    @Test
    void milestoneCompletionRejectsMissingDatabaseCriterion() {
        setStudent();
        LearningProjectEntity active = projectEntity("proj-1", "ACTIVE");
        MilestoneEntity milestone = new MilestoneEntity();
        milestone.setProjectId(active.getId());
        milestone.setPublicId("ms-1");
        milestone.setStatus("NOT_STARTED");
        milestone.setVersion(2);
        milestone.setAcceptanceJson("[{\"description\":\"A\"},{\"description\":\"B\"}]");
        when(milestones.selectOne(any())).thenReturn(milestone);
        when(milestones.lockByPublicId("ms-1")).thenReturn(milestone);
        when(projects.selectOne(any())).thenReturn(active);
        when(projects.lockById(active.getId())).thenReturn(active);

        assertThatThrownBy(() -> service.completeMilestone("ms-1",
                new GoalProjectService.MilestoneCompletionInput(2, "证据",
                        List.of(Map.of("index", 0, "confirmed", true)))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("缺项");
        verifyNoInteractions(assessments);
    }

    @Test
    void goalProgressWithoutValidProjectsMilestonesOrTasksIsZero() {
        setStudent();
        LearningGoalEntity goal = goalEntity("goal-1", "ACTIVE", "SKILL");
        goal.setId(1L);
        when(goals.selectOne(any())).thenReturn(goal);
        AtomicReference<String> projectSql = new AtomicReference<>();
        AtomicReference<String> taskSql = new AtomicReference<>();
        doAnswer(invocation -> {
            projectSql.set(invocation.getArgument(0));
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), eq(1L));
        doAnswer(invocation -> {
            taskSql.set(invocation.getArgument(0));
            return Map.of("done_minutes", 0, "total_minutes", 0, "done_count", 0, "total_count", 0);
        }).when(jdbc).queryForMap(anyString(), eq(1L));

        Map<String, Object> result = service.progress("goal-1");

        assertThat(result.get("value")).isEqualTo(new BigDecimal("0.0"));
        assertThat(((Map<?, ?>) result.get("nonProjectTasks")).get("denominator"))
                .isEqualTo(BigDecimal.ZERO);
        assertThat(projectSql.get()).contains("p.deleted_at IS NULL", "p.status NOT IN ('CANCELED','ARCHIVED')",
                "m.status<>'CANCELED'", "m.deleted_at IS NULL");
        assertThat(taskSql.get()).contains("task.lifecycle_status<>'CANCELED'", "task.deleted_at IS NULL",
                "valid_project.status NOT IN ('CANCELED','ARCHIVED')");
    }

    @Test
    void completedAndCanceledGoalsCannotBeEdited() {
        setStudent();
        for (String status : List.of("COMPLETED", "CANCELED")) {
            LearningGoalEntity terminal = goalEntity("goal-" + status, status, "SKILL");
            terminal.setVersion(1);
            when(goals.selectOne(any())).thenReturn(terminal);
            GoalProjectService.GoalInput input = new GoalProjectService.GoalInput(
                    200L, null, "试图修改终态目标", "SKILL", "说明", "MEDIUM",
                    LocalDate.now(), LocalDate.now().plusDays(10), 300,
                    List.of(Map.of("type", "OUTCOME", "description", "成果", "completed", false)),
                    1, "尝试修改", "CUSTOM", null, null, null);
            assertThatThrownBy(() -> service.updateGoal(terminal.getPublicId(), input))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("不可修改");
        }
        verify(goals, never()).updateById(any(LearningGoalEntity.class));
    }
}
