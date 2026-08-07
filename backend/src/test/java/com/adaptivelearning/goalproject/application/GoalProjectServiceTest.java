package com.adaptivelearning.goalproject.application;

import com.adaptivelearning.goalproject.domain.GoalProjectEntity;
import com.adaptivelearning.goalproject.domain.GoalStatus;
import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
import com.adaptivelearning.goalproject.domain.LearningProjectEntity;
import com.adaptivelearning.goalproject.domain.MilestoneEntity;
import com.adaptivelearning.evaluation.application.AssessmentService;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.GoalMapper;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.GoalProjectMapper;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.MilestoneMapper;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.ProjectMapper;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.security.CurrentUser;
import com.adaptivelearning.support.application.AuditService;
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
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final GoalProjectService service = new GoalProjectService(goals, projects, milestones,
            goalLinks, jdbc, objectMapper, audit, assessments);

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
    void recommendedGoalCanUseCatalogDirectionMappedFromCustomProfileDirection() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(42L, "u-42", "student", "", Set.of("STUDENT"), Set.of()), null, List.of()));
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM user_profile_direction"),
                eq(Integer.class), eq(42L), eq(200L))).thenReturn(0);
        when(jdbc.queryForMap(startsWith("SELECT code, name FROM learning_direction"),
                eq(200L))).thenReturn(Map.of("code", "economics", "name", "经济学"));
        when(jdbc.queryForList(startsWith("SELECT d.custom_direction FROM user_profile_direction"),
                eq(42L))).thenReturn(List.of(Map.of("custom_direction", "经济学")));
        when(jdbc.query(startsWith("SELECT pv.version_no"),
                any(ResultSetExtractor.class), eq(700L), eq(42L))).thenReturn(7);

        service.createGoal(new GoalProjectService.GoalInput(200L, null, "经济学一周入门", "SKILL",
                "来自画像推荐", "MEDIUM", LocalDate.now(), LocalDate.now().plusDays(6),
                420, List.of(Map.of("type", "OUTCOME", "description", "完成笔记", "completed", false)),
                null, null, "AI_RECOMMENDED", 700L, "rec-1", "匹配经济学画像"));

        ArgumentCaptor<LearningGoalEntity> captor = ArgumentCaptor.forClass(LearningGoalEntity.class);
        verify(goals).insert(captor.capture());
        assertThat(captor.getValue().getDirectionId()).isEqualTo(200L);
        assertThat(captor.getValue().getSourceType()).isEqualTo("AI_RECOMMENDED");
        assertThat(captor.getValue().getProfileVersionId()).isEqualTo(700L);
        assertThat(captor.getValue().getRecommendationSnapshotJson()).contains("rec-1");
    }

    @Test
    void recommendedGoalCanKeepCustomProfileDirection() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(42L, "u-42", "student", "", Set.of("STUDENT"), Set.of()), null, List.of()));
        when(jdbc.queryForList(startsWith("SELECT d.custom_direction FROM user_profile_direction"),
                eq(42L))).thenReturn(List.of(Map.of("custom_direction", "心理学")));
        when(jdbc.query(startsWith("SELECT pv.version_no"),
                any(ResultSetExtractor.class), eq(700L), eq(42L))).thenReturn(7);

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

    private void setStudent() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(42L, "u-42", "student", "", Set.of("STUDENT"), Set.of()), null, List.of()));
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

        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM goal_project WHERE project_id=?"),
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
}
