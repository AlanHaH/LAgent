package com.adaptivelearning.goalproject.application;

import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.GoalMapper;
import com.adaptivelearning.profile.domain.ProfileVersionEntity;
import com.adaptivelearning.profile.domain.UserProfileEntity;
import com.adaptivelearning.profile.infrastructure.ProfileMappers.ProfileVersionMapper;
import com.adaptivelearning.profile.infrastructure.ProfileMappers.UserProfileMapper;
import com.adaptivelearning.shared.ai.AiModelException;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GoalRecommendationServiceTest {
    private final UserProfileMapper profiles = mock(UserProfileMapper.class);
    private final ProfileVersionMapper versions = mock(ProfileVersionMapper.class);
    private final GoalMapper goals = mock(GoalMapper.class);
    private final PythonAiServiceClient pythonAi = mock(PythonAiServiceClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final GoalRecommendationBatchStore batches = mock(GoalRecommendationBatchStore.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final GoalRecommendationService service = new GoalRecommendationService(
            profiles, versions, goals, pythonAi, jdbc, json, batches);

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(42L, "u-42", "student", "", Set.of("STUDENT"), Set.of()),
                null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void latestReturnsPersistedBatchWithoutCallingModel() {
        GoalRecommendationService.RecommendationResponse saved = response("AI", List.of());
        when(batches.latest(42L)).thenReturn(saved);

        assertThat(service.latest()).isSameAs(saved);
        verifyNoInteractions(pythonAi);
    }

    @Test
    void recommendationInputComesOnlyFromOneFormalSnapshot() {
        prepareFormalProfile(snapshot(null, "摄影理论", "INTERMEDIATE"));
        when(pythonAi.isConfigured()).thenReturn(true);
        when(pythonAi.goalRecommendations(any())).thenReturn(new PythonAiServiceClient.GoalRecommendationResult(
                List.of(aiItem(null, "摄影理论", 300)), "goal-recommendation-v3-profile-snapshot"));

        GoalRecommendationService.RecommendationResponse result = service.recommend(3);

        ArgumentCaptor<PythonAiServiceClient.GoalRecommendationRequest> request =
                ArgumentCaptor.forClass(PythonAiServiceClient.GoalRecommendationRequest.class);
        verify(pythonAi).goalRecommendations(request.capture());
        assertThat(request.getValue().directions()).singleElement().satisfies(direction -> {
            assertThat(direction.id()).isNull();
            assertThat(direction.name()).isEqualTo("摄影理论");
            assertThat(direction.currentStage()).isEqualTo("INTERMEDIATE");
        });
        assertThat(request.getValue().weeklyAvailableMinutes()).isEqualTo(300);
        assertThat(request.getValue().confidence()).isEqualByComparingTo("0.20");
        assertThat(request.getValue().selfAssessmentCount()).isEqualTo(2);
        assertThat(result.source()).isEqualTo("AI");
        verify(batches).save(eq(42L), any(ProfileVersionEntity.class), same(result));
    }

    @Test
    void recoverableAiFailureUsesRuleFallbackAndSkipsExistingActiveDirectionStage() {
        prepareFormalProfile(snapshot(null, "心理学", "BEGINNER"));
        LearningGoalEntity active = new LearningGoalEntity();
        active.setUserId(42L);
        active.setCustomDirection("心理学");
        active.setStatus("ACTIVE");
        when(goals.selectList(any())).thenReturn(List.of(active));
        when(pythonAi.isConfigured()).thenReturn(true);
        when(pythonAi.goalRecommendations(any())).thenThrow(new AiModelException(ErrorCode.MODEL_REQUEST_TIMEOUT));

        GoalRecommendationService.RecommendationResponse result = service.recommend(3);

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.recommendations()).isEmpty();
        assertThat(result.message()).contains("已有同阶段活动目标");
        verify(batches).save(eq(42L), any(), same(result));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ACTIVE", "PAUSED"})
    void everyActiveGoalStatusBlocksRuleDuplicate(String status) {
        prepareFormalProfile(snapshot(null, "心理学", "BEGINNER"));
        LearningGoalEntity goal = new LearningGoalEntity();
        goal.setCustomDirection("心理学");
        goal.setStatus(status);
        when(goals.selectList(any())).thenReturn(List.of(goal));
        when(pythonAi.isConfigured()).thenReturn(true);
        when(pythonAi.goalRecommendations(any())).thenThrow(new AiModelException(ErrorCode.MODEL_REQUEST_TIMEOUT));

        assertThat(service.recommend(1).recommendations()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "CANCELED"})
    void terminalGoalsDoNotBlockFutureRuleRecommendation(String status) {
        prepareFormalProfile(snapshot(null, "心理学", "BEGINNER"));
        LearningGoalEntity goal = new LearningGoalEntity();
        goal.setCustomDirection("心理学");
        goal.setStatus(status);
        when(goals.selectList(any())).thenReturn(List.of(goal));
        when(pythonAi.isConfigured()).thenReturn(true);
        when(pythonAi.goalRecommendations(any())).thenThrow(new AiModelException(ErrorCode.MODEL_REQUEST_TIMEOUT));

        assertThat(service.recommend(1).recommendations()).hasSize(1);
    }

    @Test
    void quotaAndRecoverableProvider5xxUseRuleFallback() {
        for (AiModelException error : List.of(
                new AiModelException(ErrorCode.MODEL_QUOTA_EXCEEDED),
                new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR, "provider unavailable",
                        Map.of("pythonCode", "AI_PROVIDER_ERROR", "providerStatus", 503), null))) {
            reset(profiles, versions, goals, pythonAi, jdbc, batches);
            prepareFormalProfile(snapshot(null, "心理学", "BEGINNER"));
            when(pythonAi.isConfigured()).thenReturn(true);
            when(pythonAi.goalRecommendations(any())).thenThrow(error);

            assertThat(service.recommend(1).source()).isEqualTo("RULE_FALLBACK");
        }
    }

    @Test
    void providerAuthenticationErrorDoesNotFallback() {
        prepareFormalProfile(snapshot(null, "心理学", "BEGINNER"));
        when(pythonAi.isConfigured()).thenReturn(true);
        when(pythonAi.goalRecommendations(any())).thenThrow(new AiModelException(
                ErrorCode.MODEL_PROVIDER_ERROR, "provider auth failed",
                Map.of("pythonCode", "AI_PROVIDER_AUTH_ERROR", "providerStatus", 401), null));

        assertThatThrownBy(() -> service.recommend(1)).isInstanceOf(AiModelException.class);
        verify(batches, never()).save(anyLong(), any(), any());
    }

    @Test
    void missingAiConfigurationDoesNotPretendToBeRuleFallback() {
        prepareFormalProfile(snapshot(null, "心理学", "BEGINNER"));
        when(pythonAi.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.recommend(3))
                .isInstanceOf(AiModelException.class)
                .extracting(error -> ((AiModelException) error).getCode())
                .isEqualTo(ErrorCode.MODEL_PROVIDER_ERROR);
        verify(batches, never()).save(anyLong(), any(), any());
    }

    @Test
    void draftProfileCannotRecommend() {
        UserProfileEntity profile = new UserProfileEntity();
        profile.setUserId(42L);
        profile.setProfileStatus("DRAFT");
        when(profiles.selectOne(any())).thenReturn(profile);

        assertThatThrownBy(() -> service.recommend(3)).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("确认学习画像");
        verifyNoInteractions(pythonAi);
    }

    private void prepareFormalProfile(String snapshot) {
        UserProfileEntity profile = new UserProfileEntity();
        profile.setId(10L);
        profile.setUserId(42L);
        profile.setProfileStatus("GENERATED");
        profile.setCurrentVersionNo(3);
        when(profiles.selectOne(any())).thenReturn(profile);
        ProfileVersionEntity version = new ProfileVersionEntity();
        version.setId(700L);
        version.setProfileId(10L);
        version.setVersionNo(3);
        version.setSnapshotJson(snapshot);
        when(versions.selectOne(any())).thenReturn(version);
        when(goals.selectList(any())).thenReturn(List.of());
        when(jdbc.queryForObject(contains("learning_direction"), eq(Integer.class), any())).thenReturn(1);
    }

    private String snapshot(String directionId, String name, String stage) {
        return """
                {"planStartDate":"%s","planEndDate":"%s","backgroundText":"formal snapshot",
                 "directions":[{"directionId":%s,"name":"%s","currentStage":"%s","primary":true}],
                 "preference":{"capacityRatio":0.50,"difficultyMin":1,"difficultyMax":3},
                 "weeklyAvailableMinutes":600,"source":{"selfAssessmentCount":2},
                 "confidence":0.20,"recommendedDifficulty":2,"dailyRecommendedTasks":2,
                 "riskNotices":["当前仅含自评证据，建议完成诊断"]}
                """.formatted(LocalDate.now(), LocalDate.now().plusDays(30),
                directionId == null ? "null" : "\"" + directionId + "\"", name, stage);
    }

    private PythonAiServiceClient.GoalRecommendationItem aiItem(Long directionId, String custom, int weekly) {
        return new PythonAiServiceClient.GoalRecommendationItem(directionId, directionId == null ? custom : null,
                "阶段目标", "SKILL", "完成一轮结构化学习和书面成果验收", "MEDIUM", 10, weekly,
                List.of("提交结构化笔记", "完成练习并记录错因"), "符合当前正式画像",
                List.of("完成资料学习", "提交书面成果"));
    }

    private GoalRecommendationService.RecommendationResponse response(String source,
                                                                       List<GoalRecommendationService.Recommendation> items) {
        return new GoalRecommendationService.RecommendationResponse(
                "700", 3, Instant.parse("2026-07-28T01:00:00Z"), source, items, null);
    }
}
