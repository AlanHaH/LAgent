package com.adaptivelearning.goalproject.application;

import com.adaptivelearning.goalproject.domain.LearningGoalEntity;
import com.adaptivelearning.goalproject.infrastructure.GoalProjectMappers.GoalMapper;
import com.adaptivelearning.profile.domain.*;
import com.adaptivelearning.profile.infrastructure.ProfileMappers.*;
import com.adaptivelearning.shared.ai.AiModelException;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoalRecommendationService {
    private final UserProfileMapper profileMapper;
    private final ProfileDirectionMapper directionMapper;
    private final ProfileVersionMapper versionMapper;
    private final LearningPreferenceMapper preferenceMapper;
    private final AvailabilityRuleMapper availabilityMapper;
    private final GoalMapper goalMapper;
    private final PythonAiServiceClient pythonAi;
    private final JdbcTemplate jdbc;

    public record Recommendation(String id, long directionId, String directionName, String name,
                                 String type, String description, String priority,
                                 LocalDate startDate, LocalDate dueDate, int weeklyBudgetMinutes,
                                 List<Map<String, Object>> successCriteria, String reason,
                                 List<String> milestones, String source) { }
    public record RecommendationResponse(String profileVersionId, int profileVersionNo,
                                         Instant generatedAt, String source,
                                         List<Recommendation> recommendations) { }

    public RecommendationResponse recommend(int requestedCount) {
        long userId = SecurityUtils.currentUserId();
        int count = Math.max(1, Math.min(requestedCount, 3));
        UserProfileEntity profile = profileMapper.selectOne(new LambdaQueryWrapper<UserProfileEntity>()
                .eq(UserProfileEntity::getUserId, userId));
        if (profile == null || !"GENERATED".equals(profile.getProfileStatus())
                || profile.getCurrentVersionNo() == null || profile.getCurrentVersionNo() < 1) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "请先完成并确认学习画像，再生成推荐目标");
        }
        LocalDate start = LocalDate.now().isAfter(profile.getPlanStartDate())
                ? LocalDate.now() : profile.getPlanStartDate();
        if (profile.getPlanEndDate() == null || profile.getPlanEndDate().isBefore(start)) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "当前画像学习周期已结束，请先更新画像时间");
        }
        ProfileVersionEntity version = versionMapper.selectOne(new LambdaQueryWrapper<ProfileVersionEntity>()
                .eq(ProfileVersionEntity::getProfileId, profile.getId())
                .eq(ProfileVersionEntity::getVersionNo, profile.getCurrentVersionNo()));
        if (version == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "当前画像版本不存在，请重新生成画像");
        }
        List<ProfileDirectionEntity> storedDirections = directionMapper.selectList(
                new LambdaQueryWrapper<ProfileDirectionEntity>()
                        .eq(ProfileDirectionEntity::getProfileId, profile.getId())
                        .eq(ProfileDirectionEntity::getStatus, "ACTIVE")
                        .orderByDesc(ProfileDirectionEntity::getIsPrimary));
        List<DirectionContext> directions = storedDirections.stream()
                .map(this::directionContext)
                .flatMap(Optional::stream)
                .toList();
        if (directions.isEmpty()) {
            String custom = storedDirections.stream()
                    .map(ProfileDirectionEntity::getCustomDirection)
                    .filter(Objects::nonNull)
                    .filter(text -> !text.isBlank())
                    .findFirst()
                    .orElse("自定义方向");
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "当前画像方向「" + custom + "」未映射到学习目录，请先在画像页把学习方向改为目录方向，例如经济学、Java 后端开发或 AI Agent 与智能应用");
        }

        LearningPreferenceEntity preference = preferenceMapper.selectOne(
                new LambdaQueryWrapper<LearningPreferenceEntity>().eq(LearningPreferenceEntity::getUserId, userId));
        int availableMinutes = availabilityMapper.selectList(new LambdaQueryWrapper<AvailabilityRuleEntity>()
                        .eq(AvailabilityRuleEntity::getUserId, userId)).stream()
                .mapToInt(AvailabilityRuleEntity::getAvailableMinutes).sum();
        BigDecimal capacityRatio = preference == null || preference.getCapacityRatio() == null
                ? new BigDecimal("0.85") : preference.getCapacityRatio();
        int weeklyCapacity = Math.max(10, BigDecimal.valueOf(Math.max(availableMinutes, 10))
                .multiply(capacityRatio).intValue());
        List<String> existingNames = goalMapper.selectList(new LambdaQueryWrapper<LearningGoalEntity>()
                        .eq(LearningGoalEntity::getUserId, userId))
                .stream().map(LearningGoalEntity::getName).filter(Objects::nonNull).toList();

        List<RecommendationSeed> seeds;
        String source;
        if (pythonAi.isConfigured()) {
            try {
                PythonAiServiceClient.GoalRecommendationResult result = pythonAi.goalRecommendations(
                        new PythonAiServiceClient.GoalRecommendationRequest(userId, LocalDate.now(), version.getId(),
                                version.getVersionNo(), profile.getPlanStartDate(), profile.getPlanEndDate(),
                                profile.getBackgroundText(), directions.stream().map(item ->
                                new PythonAiServiceClient.GoalDirectionContext(item.id(), item.name(),
                                        item.currentStage(), item.primary())).toList(),
                                preferenceContext(preference), weeklyCapacity, existingNames, count));
                seeds = result.recommendations().stream().map(item -> new RecommendationSeed(
                        item.directionId(), item.name(), item.type(), item.description(), item.priority(),
                        item.durationDays(), item.weeklyBudgetMinutes(), item.successCriteria(),
                        item.reason(), item.milestones())).toList();
                source = "AI";
            } catch (AiModelException error) {
                log.warn("AI goal recommendations unavailable; using rules: {}", error.getCode());
                seeds = fallback(directions, weeklyCapacity, count);
                source = "RULE_FALLBACK";
            }
        } else {
            seeds = fallback(directions, weeklyCapacity, count);
            source = "RULE_FALLBACK";
        }

        Map<Long, String> directionNames = new HashMap<>();
        directions.forEach(item -> directionNames.put(item.id(), item.name()));
        String recommendationSource = source;
        long remainingDays = ChronoUnit.DAYS.between(start, profile.getPlanEndDate()) + 1;
        List<Recommendation> recommendations = seeds.stream().limit(count).map(seed -> {
            long duration = Math.max(1, Math.min(seed.durationDays(), remainingDays));
            LocalDate dueDate = start.plusDays(duration - 1);
            List<Map<String, Object>> criteria = seed.successCriteria().stream().limit(5)
                    .map(text -> Map.<String, Object>of("type", "OUTCOME", "description", text,
                            "completed", false)).toList();
            return new Recommendation(UUID.randomUUID().toString(), seed.directionId(),
                    directionNames.getOrDefault(seed.directionId(), "当前学习方向"), seed.name(),
                    seed.type(), seed.description(), seed.priority(), start, dueDate,
                    Math.max(10, Math.min(seed.weeklyBudgetMinutes(), weeklyCapacity)), criteria,
                    seed.reason(), seed.milestones(), recommendationSource);
        }).toList();
        return new RecommendationResponse(String.valueOf(version.getId()), version.getVersionNo(), Instant.now(), source,
                recommendations);
    }

    private List<RecommendationSeed> fallback(List<DirectionContext> directions, int capacity, int count) {
        List<RecommendationSeed> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            DirectionContext direction = directions.get(index % directions.size());
            String stage = switch (direction.currentStage()) {
                case "ADVANCED" -> "深化";
                case "INTERMEDIATE" -> "系统掌握";
                default -> "建立";
            };
            String name = stage + direction.name() + "知识体系" + (index == 0 ? "" : "（路径 " + (index + 1) + "）");
            result.add(new RecommendationSeed(direction.id(), name, "SKILL",
                    "围绕核心文档、阶段练习和可检查的书面成果推进，形成能够复述、应用和复盘的知识结构。",
                    "MEDIUM", index == 0 ? 30 : 45, Math.min(capacity, index == 0 ? 420 : 560),
                    List.of("完成核心文档阅读笔记与概念地图", "完成阶段练习并达到 80% 正确率",
                            "提交一份可复查的总结或案例分析"),
                    "根据当前方向、阶段和每周可用时间生成的稳健目标。",
                    List.of("建立核心概念框架", "完成针对性练习", "形成书面成果并自测")));
        }
        return result;
    }

    private Map<String, Object> preferenceContext(LearningPreferenceEntity preference) {
        if (preference == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("guidanceStyle", preference.getGuidanceStyle());
        result.put("taskGranularity", preference.getTaskGranularity());
        result.put("focusMinutes", preference.getFocusMinutes());
        result.put("difficultyMin", preference.getDifficultyMin());
        result.put("difficultyMax", preference.getDifficultyMax());
        return result;
    }

    private String directionName(long directionId) {
        String name = jdbc.query("SELECT name FROM learning_direction WHERE id=? AND status='ACTIVE'",
                rs -> rs.next() ? rs.getString(1) : null, directionId);
        if (name == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "画像中的学习方向不存在");
        return name;
    }

    private Optional<DirectionContext> directionContext(ProfileDirectionEntity item) {
        if (item.getDirectionId() != null) {
            return Optional.of(new DirectionContext(item.getDirectionId(), directionName(item.getDirectionId()),
                    item.getCurrentStage(), Boolean.TRUE.equals(item.getIsPrimary())));
        }
        return matchCatalogDirection(item.getCustomDirection())
                .map(match -> new DirectionContext(match.id(), match.name(), item.getCurrentStage(),
                        Boolean.TRUE.equals(item.getIsPrimary())));
    }

    private Optional<CatalogDirection> matchCatalogDirection(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        String normalized = normalizeDirection(query);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,code,name FROM learning_direction WHERE status='ACTIVE' AND deleted_at IS NULL ORDER BY sort_no,id");
        Optional<CatalogDirection> exact = rows.stream()
                .map(row -> new CatalogDirection(((Number) row.get("id")).longValue(),
                        String.valueOf(row.get("code")), String.valueOf(row.get("name"))))
                .filter(item -> normalizeDirection(item.name()).equals(normalized)
                        || normalizeDirection(item.code()).equals(normalized))
                .findFirst();
        if (exact.isPresent()) return exact;
        return rows.stream()
                .map(row -> new CatalogDirection(((Number) row.get("id")).longValue(),
                        String.valueOf(row.get("code")), String.valueOf(row.get("name"))))
                .filter(item -> normalizeDirection(item.name()).contains(normalized)
                        || normalized.contains(normalizeDirection(item.name())))
                .findFirst();
    }

    private String normalizeDirection(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-·]+", "");
    }

    private record DirectionContext(long id, String name, String currentStage, boolean primary) { }
    private record CatalogDirection(long id, String code, String name) { }
    private record RecommendationSeed(long directionId, String name, String type, String description,
                                      String priority, int durationDays, int weeklyBudgetMinutes,
                                      List<String> successCriteria, String reason,
                                      List<String> milestones) { }
}
