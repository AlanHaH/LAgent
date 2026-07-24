package com.adaptivelearning.goalproject.application;

import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.GoalMapper;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.GoalProjectMapper;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.MilestoneMapper;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.ProjectMapper;
import com.adaptivelearning.shared.security.CurrentUser;
import com.adaptivelearning.support.application.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

class GoalProjectServiceTest {
    private final GoalMapper goals = mock(GoalMapper.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final GoalProjectService service = new GoalProjectService(goals, mock(ProjectMapper.class),
            mock(MilestoneMapper.class), mock(GoalProjectMapper.class), jdbc, objectMapper, audit);

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

        service.createGoal(new GoalProjectService.GoalInput(200L, "经济学一周入门", "SKILL",
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

        service.createGoal(new GoalProjectService.GoalInput(200L, "经济学一周入门", "SKILL",
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
}
