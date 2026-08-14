package com.adaptivelearning.goalproject.application;

import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Deterministic Java validation and fallback policy for goal candidates. */
public class DeterministicGoalRecommendationPolicy {
    static final List<String> SUCCESS_CRITERIA = List.of(
            "完成核心资料学习并提交至少一份结构化笔记",
            "完成至少一组练习或测验并记录薄弱点",
            "提交一份阶段书面总结或可检查成果");
    static final List<String> MILESTONES = List.of(
            "核心资料学习与记录",
            "练习、测验与纠错",
            "书面成果验收与复盘");

    public record ActiveGoal(String directionKey, String currentStage) {
        boolean blocks(GoalRecommendationContext.Direction direction) {
            return directionKey.equals(direction.key())
                    && (currentStage == null || currentStage.isBlank()
                    || currentStage.equals(direction.currentStage()));
        }
    }

    public List<GoalRecommendationService.Recommendation> validateAi(
            GoalRecommendationContext context,
            List<PythonAiServiceClient.GoalRecommendationItem> candidates,
            int count,
            LocalDate today) {
        if (candidates == null || candidates.isEmpty()) invalid("AI 未返回目标候选");
        LocalDate start = today.isAfter(context.planStartDate()) ? today : context.planStartDate();
        long remainingDays = ChronoUnit.DAYS.between(start, context.planEndDate()) + 1;
        if (remainingDays < 1) invalid("正式画像学习周期已经结束");
        int capacity = boundedCapacity(context.weeklyCapacityMinutes());
        Set<String> uniqueDirections = new HashSet<>();
        List<GoalRecommendationService.Recommendation> result = new ArrayList<>();
        for (PythonAiServiceClient.GoalRecommendationItem item : candidates.stream().limit(count).toList()) {
            GoalRecommendationContext.Direction direction;
            try {
                direction = context.requireDirection(item.directionId(), item.customDirection());
            } catch (BusinessException error) {
                invalid("AI 目标候选引用了正式画像之外的方向");
                return List.of(); // unreachable
            }
            if (!uniqueDirections.add(direction.key())) invalid("AI 为同一方向返回了重复目标候选");
            if (item.type() == null || !Set.of("SKILL", "EXAM", "PROJECT").contains(item.type())
                    || item.priority() == null || !Set.of("LOW", "MEDIUM", "HIGH", "URGENT").contains(item.priority())
                    || item.name() == null || item.name().trim().length() < 2 || item.name().length() > 100
                    || item.description() == null || item.description().trim().length() < 10
                    || item.description().length() > 2000
                    || item.reason() == null || item.reason().trim().length() < 5 || item.reason().length() > 500
                    || item.durationDays() < 1 || item.durationDays() > remainingDays
                    || item.weeklyBudgetMinutes() < 10 || item.weeklyBudgetMinutes() > capacity
                    || invalidTextList(item.successCriteria(), 2, 5)
                    || invalidTextList(item.milestones(), 2, 5)) {
                invalid("AI 目标候选未通过 Java 业务规则校验");
            }
            List<Map<String, Object>> criteria = item.successCriteria().stream().map(text ->
                    Map.<String, Object>of("type", "OUTCOME", "description", text.trim(), "completed", false)).toList();
            result.add(new GoalRecommendationService.Recommendation(
                    UUID.randomUUID().toString(), direction.id(), direction.id() == null ? direction.name() : null,
                    direction.name(), direction.currentStage(), item.name().trim(), item.type(),
                    item.description().trim(), item.priority(), start,
                    start.plusDays(item.durationDays() - 1L), item.weeklyBudgetMinutes(), criteria,
                    item.reason(), item.milestones().stream().map(String::trim).toList(), "AI"));
        }
        if (result.isEmpty()) invalid("AI 未返回有效目标候选");
        return List.copyOf(result);
    }

    public List<GoalRecommendationService.Recommendation> fallback(
            GoalRecommendationContext context,
            List<ActiveGoal> activeGoals,
            int count,
            LocalDate today) {
        LocalDate start = today.isAfter(context.planStartDate()) ? today : context.planStartDate();
        if (context.planEndDate().isBefore(start)) invalid("正式画像学习周期已经结束");
        int weeklyBudget = boundedCapacity(context.weeklyCapacityMinutes());
        return context.directions().stream()
                .sorted((left, right) -> Boolean.compare(right.primary(), left.primary()))
                .filter(direction -> activeGoals.stream().noneMatch(goal -> goal.blocks(direction)))
                .limit(count)
                .map(direction -> new GoalRecommendationService.Recommendation(
                        stableId(context, direction, today), direction.id(),
                        direction.id() == null ? direction.name() : null, direction.name(), direction.currentStage(),
                        direction.name() + "·" + stageName(direction.currentStage()) + "阶段学习目标",
                        "SKILL", "在正式画像周期内完成" + direction.name() + "的阶段性学习、练习和书面验收。",
                        "MEDIUM", start, context.planEndDate(), weeklyBudget,
                        SUCCESS_CRITERIA.stream().map(text -> Map.<String, Object>of(
                                "type", "OUTCOME", "description", text, "completed", false)).toList(),
                        reason(context, direction), MILESTONES, "RULE_FALLBACK"))
                .toList();
    }

    private String reason(GoalRecommendationContext context, GoalRecommendationContext.Direction direction) {
        String risks = context.riskNotices().isEmpty() ? "无额外风险提示" : String.join("；", context.riskNotices());
        return "依据正式画像中的" + direction.name() + "（" + stageName(direction.currentStage()) + "阶段）、每周容量"
                + boundedCapacity(context.weeklyCapacityMinutes()) + "分钟、推荐难度"
                + context.recommendedDifficulty() + "生成。风险提示：" + risks;
    }

    private String stableId(GoalRecommendationContext context, GoalRecommendationContext.Direction direction,
                            LocalDate today) {
        String input = context.profileVersionId() + "|" + context.profileVersionNo() + "|"
                + direction.key() + "|" + direction.currentStage() + "|" + today;
        return UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private int boundedCapacity(int value) {
        return Math.max(10, Math.min(value, 6720));
    }

    private boolean invalidTextList(List<String> values, int min, int max) {
        return values == null || values.size() < min || values.size() > max
                || values.stream().anyMatch(value -> value == null || value.isBlank());
    }

    private static String stageName(String stage) {
        return switch (Objects.toString(stage, "").toUpperCase(Locale.ROOT)) {
            case "INTERMEDIATE" -> "进阶";
            case "ADVANCED" -> "高级";
            default -> "入门";
        };
    }

    private static void invalid(String message) {
        throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID, message);
    }
}
