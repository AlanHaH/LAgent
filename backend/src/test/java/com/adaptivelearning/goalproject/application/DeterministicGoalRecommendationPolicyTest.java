package com.adaptivelearning.goalproject.application;

import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicGoalRecommendationPolicyTest {
    private final DeterministicGoalRecommendationPolicy policy = new DeterministicGoalRecommendationPolicy();
    private final LocalDate today = LocalDate.of(2026, 8, 13);

    @Test
    void fallbackIsStablePrimaryFirstAndHasFixedAcceptanceStructure() {
        GoalRecommendationContext context = context(List.of(
                new GoalRecommendationContext.Direction(2L, "数据结构", "INTERMEDIATE", false),
                new GoalRecommendationContext.Direction(1L, "Java", "BEGINNER", true)));

        var first = policy.fallback(context, List.of(), 3, today);
        var second = policy.fallback(context, List.of(), 3, today);

        assertThat(first).hasSize(2);
        assertThat(first.get(0).directionId()).isEqualTo(1L);
        assertThat(first.get(0).id()).isEqualTo(second.get(0).id());
        assertThat(first.get(0).startDate()).isEqualTo(today);
        assertThat(first.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(first.get(0).weeklyBudgetMinutes()).isEqualTo(300);
        assertThat(first.get(0).successCriteria()).hasSize(3);
        assertThat(first.get(0).milestones()).containsExactlyElementsOf(
                DeterministicGoalRecommendationPolicy.MILESTONES);
    }

    @Test
    void draftActiveAndPausedBlockSameDirectionAndStageButTerminalGoalsDoNotEnterFilter() {
        GoalRecommendationContext context = context(List.of(
                new GoalRecommendationContext.Direction(1L, "Java", "BEGINNER", true),
                new GoalRecommendationContext.Direction(2L, "算法", "BEGINNER", false)));
        var active = List.of(new DeterministicGoalRecommendationPolicy.ActiveGoal("catalog:1", "BEGINNER"));

        assertThat(policy.fallback(context, active, 3, today))
                .extracting(GoalRecommendationService.Recommendation::directionId)
                .containsExactly(2L);
        assertThat(policy.fallback(context, List.of(), 3, today)).hasSize(2);
    }

    @Test
    void allDirectionsCanBeFilteredWithoutInventingDuplicateNames() {
        GoalRecommendationContext context = context(List.of(
                new GoalRecommendationContext.Direction(null, "摄影", "ADVANCED", true)));
        var active = List.of(new DeterministicGoalRecommendationPolicy.ActiveGoal("custom:摄影", null));

        assertThat(policy.fallback(context, active, 3, today)).isEmpty();
    }

    @Test
    void javaRejectsAiDirectionOutsideFormalSnapshot() {
        GoalRecommendationContext context = context(List.of(
                new GoalRecommendationContext.Direction(1L, "Java", "BEGINNER", true)));
        var invalid = new PythonAiServiceClient.GoalRecommendationItem(999L, null, "非法方向目标", "SKILL",
                "完成一轮结构化学习和书面成果验收", "MEDIUM", 10, 200,
                List.of("提交笔记", "完成练习"), "模型生成", List.of("资料", "成果"));

        assertThatThrownBy(() -> policy.validateAi(context, List.of(invalid), 3, today))
                .isInstanceOf(BusinessException.class);
    }

    private GoalRecommendationContext context(List<GoalRecommendationContext.Direction> directions) {
        return new GoalRecommendationContext(700L, 3, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 9, 1), "背景", directions,
                Map.of("capacityRatio", 0.5), 600, new BigDecimal("0.5"), 1,
                new BigDecimal("0.20"), 2, 2, List.of("建议完成诊断"));
    }
}
