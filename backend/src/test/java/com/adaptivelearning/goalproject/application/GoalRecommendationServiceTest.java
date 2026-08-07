package com.adaptivelearning.goalproject.application;

import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.GoalMapper;
import com.adaptivelearning.profile.domain.AvailabilityRuleEntity;
import com.adaptivelearning.profile.domain.ProfileDirectionEntity;
import com.adaptivelearning.profile.domain.ProfileVersionEntity;
import com.adaptivelearning.profile.domain.UserProfileEntity;
import com.adaptivelearning.profile.infrastructure.ProfileMappers.AvailabilityRuleMapper;
import com.adaptivelearning.profile.infrastructure.ProfileMappers.LearningPreferenceMapper;
import com.adaptivelearning.profile.infrastructure.ProfileMappers.ProfileDirectionMapper;
import com.adaptivelearning.profile.infrastructure.ProfileMappers.ProfileVersionMapper;
import com.adaptivelearning.profile.infrastructure.ProfileMappers.UserProfileMapper;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GoalRecommendationServiceTest {
    private final UserProfileMapper profiles = mock(UserProfileMapper.class);
    private final ProfileDirectionMapper directions = mock(ProfileDirectionMapper.class);
    private final ProfileVersionMapper versions = mock(ProfileVersionMapper.class);
    private final LearningPreferenceMapper preferences = mock(LearningPreferenceMapper.class);
    private final AvailabilityRuleMapper availability = mock(AvailabilityRuleMapper.class);
    private final GoalMapper goals = mock(GoalMapper.class);
    private final PythonAiServiceClient pythonAi = mock(PythonAiServiceClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final GoalRecommendationService service = new GoalRecommendationService(
            profiles, directions, versions, preferences, availability, goals, pythonAi, jdbc, objectMapper);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void latestReturnsPersistedBatchWithoutCallingModel() throws Exception {
        authenticate();
        GoalRecommendationService.RecommendationResponse saved =
                new GoalRecommendationService.RecommendationResponse(
                        "700", 3, Instant.parse("2026-07-28T01:00:00Z"), "AI",
                        List.of(new GoalRecommendationService.Recommendation(
                                "rec-1", null, "心理学", "心理学", "心理学入门",
                                "SKILL", "建立基础框架", "MEDIUM",
                                LocalDate.of(2026, 7, 28), LocalDate.of(2026, 8, 3), 420,
                                List.of(), "匹配当前画像", List.of("完成概念图"), "AI")));
        when(jdbc.query(startsWith("SELECT response_json"),
                ArgumentMatchers.<RowMapper<String>>any(), eq(42L)))
                .thenReturn(List.of(objectMapper.writeValueAsString(saved)));

        GoalRecommendationService.RecommendationResponse result = service.latest();

        assertThat(result).isEqualTo(saved);
        verifyNoInteractions(pythonAi);
    }

    @Test
    void recommendPersistsNewBatchAfterModelSucceeds() {
        authenticate();
        UserProfileEntity profile = new UserProfileEntity();
        profile.setId(10L);
        profile.setUserId(42L);
        profile.setProfileStatus("GENERATED");
        profile.setCurrentVersionNo(3);
        profile.setPlanStartDate(LocalDate.now());
        profile.setPlanEndDate(LocalDate.now().plusDays(30));
        when(profiles.selectOne(any())).thenReturn(profile);

        ProfileVersionEntity version = new ProfileVersionEntity();
        version.setId(700L);
        version.setProfileId(10L);
        version.setVersionNo(3);
        when(versions.selectOne(any())).thenReturn(version);

        ProfileDirectionEntity direction = new ProfileDirectionEntity();
        direction.setProfileId(10L);
        direction.setCustomDirection("心理学");
        direction.setCurrentStage("BEGINNER");
        direction.setIsPrimary(true);
        direction.setStatus("ACTIVE");
        when(directions.selectList(any())).thenReturn(List.of(direction));

        AvailabilityRuleEntity slot = new AvailabilityRuleEntity();
        slot.setAvailableMinutes(840);
        when(availability.selectList(any())).thenReturn(List.of(slot));
        when(goals.selectList(any())).thenReturn(List.<LearningGoalEntity>of());
        when(pythonAi.isConfigured()).thenReturn(true);
        when(pythonAi.goalRecommendations(any())).thenReturn(
                new PythonAiServiceClient.GoalRecommendationResult(
                        List.of(new PythonAiServiceClient.GoalRecommendationItem(
                                null, "心理学", "心理学一周入门", "SKILL",
                                "建立心理学基础框架", "MEDIUM", 7, 420,
                                List.of("完成概念图"), "适合零基础画像",
                                List.of("完成基础阅读", "完成概念图"))),
                        "goal-recommendation-v2"));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        GoalRecommendationService.RecommendationResponse result = service.recommend(3);

        assertThat(result.recommendations()).hasSize(1);
        verify(jdbc).update(startsWith("INSERT INTO goal_recommendation_batch"), any(Object[].class));
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(42L, "u-42", "student", "", Set.of("STUDENT"), Set.of()),
                null, List.of()));
    }
}
