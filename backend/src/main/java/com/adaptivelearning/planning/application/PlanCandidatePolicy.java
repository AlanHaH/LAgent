package com.adaptivelearning.planning.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;

import java.time.LocalDate;
import java.util.*;

/** Java 对 AI 任务候选及最终覆盖关系的独立、确定性校验。 */
public final class PlanCandidatePolicy {
    private static final Set<String> TASK_TYPES = Set.of("LEARNING", "PRACTICE", "REVIEW", "ASSESSMENT");
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "URGENT");

    public record Criterion(String id, String text) { }
    public record Milestone(long id, String publicId, LocalDate dueDate, Set<String> criterionIds) {
        public Milestone { criterionIds = criterionIds == null ? Set.of() : Set.copyOf(criterionIds); }
    }
    public record Candidate(String clientRef, String title, String taskType, String priority,
                            int estimatedMinutes, List<Long> knowledgePointIds, List<Long> sourceChunkIds,
                            String learningObjective, List<String> acceptanceCriteria, Long milestoneId,
                            List<String> coveredGoalCriterionIds,
                            List<String> coveredMilestoneCriterionIds) { }
    public record FinalTask(String ref, String lifecycleStatus, Long milestoneId,
                            List<String> acceptanceCriteria, Set<String> coveredGoalCriterionIds,
                            Set<String> coveredMilestoneCriterionIds) { }
    public record Context(boolean explorationMode, Set<Long> allowedKnowledgePointIds,
                          Set<Long> allowedChunkIds, Set<String> goalCriterionIds,
                          Map<Long, Milestone> milestones) {
        public Context {
            allowedKnowledgePointIds = copy(allowedKnowledgePointIds);
            allowedChunkIds = copy(allowedChunkIds);
            goalCriterionIds = copy(goalCriterionIds);
            milestones = milestones == null ? Map.of() : Map.copyOf(milestones);
        }
    }

    public List<Candidate> validateCandidates(List<Candidate> candidates, Context context) {
        if (candidates == null || candidates.isEmpty()) throw invalid("AI 未返回任务候选");
        Set<String> refs = new HashSet<>();
        Set<String> duplicateTitles = new HashSet<>();
        List<Candidate> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.clientRef() == null || candidate.clientRef().isBlank() || !refs.add(candidate.clientRef()))
                throw invalid("任务候选缺少稳定且唯一的 clientRef");
            String title = candidate.title() == null ? "" : candidate.title().trim();
            if (title.length() < 2 || title.length() > 200) throw invalid("任务标题必须为 2～200 字");
            if (!duplicateTitles.add(title.toLowerCase(Locale.ROOT))) throw invalid("AI 返回了明显重复的任务候选");
            if (!TASK_TYPES.contains(candidate.taskType())) throw invalid("AI 返回了非法 taskType");
            if (!PRIORITIES.contains(candidate.priority())) throw invalid("AI 返回了非法 priority");
            if (candidate.estimatedMinutes() < 10 || candidate.estimatedMinutes() > 120)
                throw invalid("单任务时长必须为 10～120 分钟，不能静默裁剪");
            if (candidate.learningObjective() == null || candidate.learningObjective().isBlank())
                throw invalid("任务 learningObjective 不能为空");
            List<String> acceptance = normalized(candidate.acceptanceCriteria());
            if (acceptance.isEmpty() || acceptance.size() > 5) throw invalid("任务必须包含 1～5 条可验收条件");
            if (acceptance.stream().anyMatch(value -> Set.of("了解", "熟悉", "掌握", "学习").contains(value)))
                throw invalid("任务验收条件必须描述可观察、可验证的结果");
            Set<Long> knowledgeIds = new LinkedHashSet<>(safe(candidate.knowledgePointIds()));
            if (context.explorationMode() && !knowledgeIds.isEmpty())
                throw invalid("自定义方向不得伪造公共知识点关联");
            if (!context.allowedKnowledgePointIds().containsAll(knowledgeIds))
                throw invalid("AI 返回了候选范围外的 knowledgePointId");
            Set<Long> chunkIds = new LinkedHashSet<>(safe(candidate.sourceChunkIds()));
            if (!context.allowedChunkIds().containsAll(chunkIds)) throw invalid("AI 返回了未授权的资料 Chunk");
            Set<String> goalCoverage = new LinkedHashSet<>(safe(candidate.coveredGoalCriterionIds()));
            if (!context.goalCriterionIds().containsAll(goalCoverage)) throw invalid("AI 创造了不存在的 Goal criterionId");
            Set<String> milestoneCoverage = new LinkedHashSet<>(safe(candidate.coveredMilestoneCriterionIds()));
            if (candidate.milestoneId() == null && !milestoneCoverage.isEmpty())
                throw invalid("未归属里程碑的任务不能声明里程碑覆盖");
            if (candidate.milestoneId() != null) {
                Milestone milestone = context.milestones().get(candidate.milestoneId());
                if (milestone == null) throw invalid("AI 返回了不属于当前项目的 milestoneId");
                if (!milestone.criterionIds().containsAll(milestoneCoverage))
                    throw invalid("AI 创造了不存在的 Milestone criterionId");
            }
            result.add(new Candidate(candidate.clientRef(), title, candidate.taskType(), candidate.priority(),
                    candidate.estimatedMinutes(), List.copyOf(knowledgeIds), List.copyOf(chunkIds),
                    candidate.learningObjective().trim(), acceptance, candidate.milestoneId(),
                    List.copyOf(goalCoverage), List.copyOf(milestoneCoverage)));
        }
        return List.copyOf(result);
    }

    public void requireFinalCoverage(List<FinalTask> tasks, Set<String> requiredGoalCriteria,
                                     Map<Long, Milestone> requiredMilestones) {
        List<FinalTask> effective = safe(tasks).stream()
                .filter(task -> !"CANCELED".equals(task.lifecycleStatus())).toList();
        Set<String> goalCoverage = new HashSet<>();
        for (FinalTask task : effective) {
            if ((!task.coveredGoalCriterionIds().isEmpty() || !task.coveredMilestoneCriterionIds().isEmpty())
                    && normalized(task.acceptanceCriteria()).isEmpty())
                throw invalid("声明 criterion coverage 的任务必须具有可验收条件");
            goalCoverage.addAll(task.coveredGoalCriterionIds());
        }
        if (!goalCoverage.containsAll(requiredGoalCriteria == null ? Set.of() : requiredGoalCriteria))
            throw invalid("最终任务集合未覆盖全部 Goal success criteria");
        if (requiredMilestones != null) for (Milestone milestone : requiredMilestones.values()) {
            boolean covered = effective.stream().anyMatch(task -> Objects.equals(task.milestoneId(), milestone.id()));
            if (!covered) throw invalid("最终任务集合未覆盖里程碑「" + milestone.publicId() + "」");
        }
    }

    private static List<String> normalized(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank())
                .distinct().toList();
    }
    private static <T> Set<T> copy(Set<T> value) { return value == null ? Set.of() : Set.copyOf(value); }
    private static <T> List<T> safe(List<T> value) { return value == null ? List.of() : value; }
    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID, message);
    }
}
