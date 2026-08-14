package com.adaptivelearning.goalproject.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.adaptivelearning.profile.domain.ProfileVersionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GoalRecommendationBatchStoreTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final GoalRecommendationBatchStore store = new GoalRecommendationBatchStore(jdbc, json);

    @Test
    void verifiesCandidateOwnerSourceProfileAndCurrentFormalContext() throws Exception {
        var candidate = candidate("rec-1", "AI");
        var response = new GoalRecommendationService.RecommendationResponse(
                "700", 3, Instant.now(), "AI", List.of(candidate), null);
        when(jdbc.queryForList(contains("FROM goal_recommendation_batch b"), eq(42L), eq(700L)))
                .thenReturn(List.of(Map.of("profile_version_id", 700L, "profile_version_no", 3,
                        "source", "AI", "response_json", json.writeValueAsString(response),
                        "snapshot_json", snapshot())));
        when(jdbc.queryForList(contains("FROM user_profile p"), eq(42L))).thenReturn(List.of(Map.of(
                "profile_status", "GENERATED", "current_version_no", 3, "profile_version_id", 700L)));

        var verified = store.verifyForAdoption(42L, "rec-1", "AI_RECOMMENDED", 700L);

        assertThat(verified.sourceType()).isEqualTo("AI_RECOMMENDED");
        assertThat(verified.candidate()).isEqualTo(candidate);
        assertThat(verified.context().directions()).singleElement()
                .satisfies(direction -> assertThat(direction.name()).isEqualTo("Java"));
    }

    @Test
    void rejectsForgedCandidateOtherUserAndSourceMismatch() throws Exception {
        var response = new GoalRecommendationService.RecommendationResponse(
                "700", 3, Instant.now(), "AI", List.of(candidate("rec-1", "AI")), null);
        when(jdbc.queryForList(contains("FROM goal_recommendation_batch b"), eq(42L), eq(700L)))
                .thenReturn(List.of(Map.of("profile_version_id", 700L, "profile_version_no", 3,
                        "source", "AI", "response_json", json.writeValueAsString(response),
                        "snapshot_json", snapshot())));

        assertThatThrownBy(() -> store.verifyForAdoption(42L, "forged", "AI_RECOMMENDED", 700L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThatThrownBy(() -> store.verifyForAdoption(42L, "rec-1", "RULE_RECOMMENDED", 700L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("来源");
        when(jdbc.queryForList(contains("FROM goal_recommendation_batch b"), eq(99L), eq(700L)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> store.verifyForAdoption(99L, "rec-1", "AI_RECOMMENDED", 700L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void staleProfileVersionCannotAdoptCandidate() throws Exception {
        var response = new GoalRecommendationService.RecommendationResponse(
                "700", 3, Instant.now(), "AI", List.of(candidate("rec-1", "AI")), null);
        when(jdbc.queryForList(contains("FROM goal_recommendation_batch b"), eq(42L), eq(700L)))
                .thenReturn(List.of(Map.of("profile_version_id", 700L, "profile_version_no", 3,
                        "source", "AI", "response_json", json.writeValueAsString(response),
                        "snapshot_json", snapshot())));
        when(jdbc.queryForList(contains("FROM user_profile p"), eq(42L))).thenReturn(List.of(Map.of(
                "profile_status", "DRAFT", "current_version_no", 4, "profile_version_id", 701L)));

        assertThatThrownBy(() -> store.verifyForAdoption(42L, "rec-1", "AI_RECOMMENDED", 700L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.PROFILE_CONTEXT_STALE);
    }

    @Test
    void profileChangingDuringGenerationPreventsBatchPersistence() {
        ProfileVersionEntity version = new ProfileVersionEntity();
        version.setId(700L);
        version.setVersionNo(3);
        when(jdbc.queryForList(contains("FROM user_profile p"), eq(42L))).thenReturn(List.of(Map.of(
                "profile_status", "DRAFT", "current_version_no", 4, "profile_version_id", 701L)));

        assertThatThrownBy(() -> store.save(42L, version,
                new GoalRecommendationService.RecommendationResponse(
                        "700", 3, Instant.now(), "AI", List.of(candidate("rec-1", "AI")), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(ErrorCode.PROFILE_CONTEXT_STALE);
        verify(jdbc, never()).update(contains("INSERT INTO goal_recommendation_batch"), any(Object[].class));
    }

    private GoalRecommendationService.Recommendation candidate(String id, String source) {
        return new GoalRecommendationService.Recommendation(id, 10L, null, "Java", "BEGINNER",
                "Java 阶段目标", "SKILL", "完成阶段学习与成果验收", "MEDIUM",
                LocalDate.now(), LocalDate.now().plusDays(10), 300,
                List.of(Map.of("type", "OUTCOME", "description", "提交成果", "completed", false)),
                "正式画像推荐", List.of("学习", "验收"), source);
    }

    private String snapshot() {
        return """
                {"planStartDate":"2026-08-01","planEndDate":"2026-09-01",
                 "directions":[{"directionId":"10","name":"Java","currentStage":"BEGINNER","primary":true}],
                 "preference":{"capacityRatio":0.5},"weeklyAvailableMinutes":600,
                 "source":{"selfAssessmentCount":1},"confidence":0.2,
                 "recommendedDifficulty":2,"dailyRecommendedTasks":2,"riskNotices":[]}
                """;
    }
}
