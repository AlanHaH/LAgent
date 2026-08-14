package com.adaptivelearning.goalproject.application;

import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.GoalMapper;
import com.adaptivelearning.profile.domain.ProfileVersionEntity;
import com.adaptivelearning.profile.domain.UserProfileEntity;
import com.adaptivelearning.profile.infrastructure.ProfileMappers.ProfileVersionMapper;
import com.adaptivelearning.profile.infrastructure.ProfileMappers.UserProfileMapper;
import com.adaptivelearning.shared.ai.AiModelException;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class GoalRecommendationService {
    private final UserProfileMapper profileMapper;
    private final ProfileVersionMapper versionMapper;
    private final GoalMapper goalMapper;
    private final PythonAiServiceClient pythonAi;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final GoalRecommendationBatchStore batchStore;
    private final DeterministicGoalRecommendationPolicy policy = new DeterministicGoalRecommendationPolicy();

    public record Recommendation(String id,
                                 @JsonSerialize(using = ToStringSerializer.class) Long directionId,
                                 String customDirection, String directionName, String currentStage, String name,
                                 String type, String description, String priority,
                                 LocalDate startDate, LocalDate dueDate, int weeklyBudgetMinutes,
                                 List<Map<String, Object>> successCriteria, String reason,
                                 List<String> milestones, String source) { }

    public record RecommendationResponse(String profileVersionId, int profileVersionNo,
                                         Instant generatedAt, String source,
                                         List<Recommendation> recommendations,
                                         String message) { }

    public RecommendationResponse recommend(int requestedCount) {
        long userId = SecurityUtils.currentUserId();
        int count = Math.max(1, Math.min(requestedCount, 3));
        UserProfileEntity profile = currentGeneratedProfile(userId);
        ProfileVersionEntity version = versionMapper.selectOne(new LambdaQueryWrapper<ProfileVersionEntity>()
                .eq(ProfileVersionEntity::getProfileId, profile.getId())
                .eq(ProfileVersionEntity::getVersionNo, profile.getCurrentVersionNo()));
        if (version == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "当前正式画像版本不存在，请重新生成画像");
        }
        GoalRecommendationContext context = GoalRecommendationContext.from(
                version.getId(), version.getVersionNo(), version.getSnapshotJson(), objectMapper);
        validateCatalogDirections(context);
        LocalDate today = LocalDate.now();
        if (context.planEndDate().isBefore(today.isAfter(context.planStartDate()) ? today : context.planStartDate())) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "当前正式画像学习周期已结束，请先更新画像时间");
        }

        List<LearningGoalEntity> activeGoals = goalMapper.selectList(new LambdaQueryWrapper<LearningGoalEntity>()
                .eq(LearningGoalEntity::getUserId, userId)
                .in(LearningGoalEntity::getStatus, "DRAFT", "ACTIVE", "PAUSED")).stream()
                .filter(goal -> Set.of("DRAFT", "ACTIVE", "PAUSED").contains(goal.getStatus())).toList();
        List<DeterministicGoalRecommendationPolicy.ActiveGoal> activeGoalKeys = activeGoals.stream()
                .map(this::activeGoal).toList();

        List<Recommendation> recommendations;
        String source;
        String message = null;
        if (!pythonAi.isConfigured()) {
            throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR, "AI 服务配置不完整", Map.of(), null);
        }
        try {
            PythonAiServiceClient.GoalRecommendationResult ai = pythonAi.goalRecommendations(
                    aiRequest(userId, version, context, activeGoals, count, today));
            recommendations = policy.validateAi(context, ai.recommendations(), count, today);
            source = "AI";
        } catch (AiModelException error) {
            if (!fallbackEligible(error)) throw error;
            recommendations = policy.fallback(context, activeGoalKeys, count, today);
            source = "RULE_FALLBACK";
            message = recommendations.isEmpty()
                    ? "当前画像中的方向均已有同阶段活动目标，因此没有生成重复候选。"
                    : "AI 服务暂时不可用，已根据正式画像生成确定性规则候选。";
        } catch (BusinessException error) {
            if (error.getCode() != ErrorCode.MODEL_OUTPUT_INVALID) throw error;
            recommendations = policy.fallback(context, activeGoalKeys, count, today);
            source = "RULE_FALLBACK";
            message = recommendations.isEmpty()
                    ? "当前画像中的方向均已有同阶段活动目标，因此没有生成重复候选。"
                    : "AI 候选未通过 Java 校验，已根据正式画像生成确定性规则候选。";
        }

        RecommendationResponse response = new RecommendationResponse(String.valueOf(version.getId()),
                version.getVersionNo(), Instant.now(), source, recommendations, message);
        batchStore.save(userId, version, response);
        return response;
    }

    public RecommendationResponse latest() {
        return batchStore.latest(SecurityUtils.currentUserId());
    }

    private UserProfileEntity currentGeneratedProfile(long userId) {
        UserProfileEntity profile = profileMapper.selectOne(new LambdaQueryWrapper<UserProfileEntity>()
                .eq(UserProfileEntity::getUserId, userId));
        if (profile == null || !"GENERATED".equals(profile.getProfileStatus())
                || profile.getCurrentVersionNo() == null || profile.getCurrentVersionNo() < 1) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "请先完成并确认学习画像，再生成推荐目标");
        }
        return profile;
    }

    private void validateCatalogDirections(GoalRecommendationContext context) {
        for (GoalRecommendationContext.Direction direction : context.directions()) {
            if (direction.id() == null) continue;
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM learning_direction
                    WHERE id=? AND status='ACTIVE' AND deleted_at IS NULL
                    """, Integer.class, direction.id());
            if (count == null || count == 0) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                        "正式画像中的公共学习方向已停用，请更新画像后重试");
            }
        }
    }

    private PythonAiServiceClient.GoalRecommendationRequest aiRequest(
            long userId, ProfileVersionEntity version, GoalRecommendationContext context,
            List<LearningGoalEntity> activeGoals, int count, LocalDate today) {
        return new PythonAiServiceClient.GoalRecommendationRequest(userId, today, version.getId(),
                version.getVersionNo(), context.planStartDate(), context.planEndDate(), context.backgroundText(),
                context.directions().stream().map(item -> new PythonAiServiceClient.GoalDirectionContext(
                        item.id(), item.name(), item.currentStage(), item.primary())).toList(),
                context.preference(), Math.max(10, Math.min(context.weeklyCapacityMinutes(), 6720)),
                activeGoals.stream().map(LearningGoalEntity::getName).filter(Objects::nonNull).toList(),
                context.selfAssessmentCount(), context.confidence(), context.recommendedDifficulty(),
                context.dailyRecommendedTasks(), context.riskNotices(), count);
    }

    private DeterministicGoalRecommendationPolicy.ActiveGoal activeGoal(LearningGoalEntity goal) {
        String directionKey = goal.getDirectionId() == null
                ? "custom:" + GoalRecommendationContext.normalize(goal.getCustomDirection())
                : "catalog:" + goal.getDirectionId();
        String currentStage = null;
        try {
            JsonNode snapshot = objectMapper.readTree(goal.getRecommendationSnapshotJson());
            currentStage = snapshot.path("originalCandidate").path("currentStage").asText(null);
        } catch (Exception ignored) {
            // 旧数据没有候选阶段，按方向保守阻止重复推荐。
        }
        return new DeterministicGoalRecommendationPolicy.ActiveGoal(directionKey, currentStage);
    }

    private boolean fallbackEligible(AiModelException error) {
        if (Set.of(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE, ErrorCode.MODEL_REQUEST_TIMEOUT,
                ErrorCode.MODEL_QUOTA_EXCEEDED, ErrorCode.MODEL_OUTPUT_INVALID).contains(error.getCode())) return true;
        if (error.getCode() != ErrorCode.MODEL_PROVIDER_ERROR) return false;
        if ("AI_PROVIDER_ERROR".equals(error.getDetails().get("pythonCode"))) return true;
        Object providerStatus = error.getDetails().get("providerStatus");
        return providerStatus instanceof Number status && status.intValue() >= 500;
    }
}
