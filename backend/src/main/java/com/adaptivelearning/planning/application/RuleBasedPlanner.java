package com.adaptivelearning.planning.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/** Java 权威排期器：AI 只提供任务内容，本类只在正式画像时段内确定开始时间。 */
@Component
public class RuleBasedPlanner {
    private static final int MIN_MINUTES = 10;
    private static final int MAX_MINUTES = 120;
    private static final int DEFAULT_MINUTES = 60;

    public record Slot(int weekday, LocalTime start, int minutes) { }
    public record DayException(LocalDate date, int availableMinutes) { }
    public record OccupiedTask(String ref, ZonedDateTime start, int estimatedMinutes) {
        public ZonedDateTime end() { return start.plusMinutes(estimatedMinutes); }
    }
    public record TaskSource(long chunkId, String documentId, String documentName, int chunkNo,
                             String quotePreview, Integer pageFrom, Integer pageTo) { }

    public record TaskContent(String title, String taskType, String priority, int estimatedMinutes,
                              List<Long> knowledgePointIds, List<TaskSource> knowledgeSources,
                              String learningObjective, List<String> sourceQueries, boolean explorationRequired,
                              List<String> acceptance, String reason, String clientRef, Long milestoneId,
                              List<String> coveredGoalCriterionIds,
                              List<String> coveredMilestoneCriterionIds, LocalDate latestDate) {
        public TaskContent(String title, String taskType, String priority, int estimatedMinutes,
                           List<Long> knowledgePointIds, List<TaskSource> knowledgeSources,
                           String learningObjective, List<String> sourceQueries, boolean explorationRequired,
                           List<String> acceptance, String reason) {
            this(title, taskType, priority, estimatedMinutes, knowledgePointIds, knowledgeSources,
                    learningObjective, sourceQueries, explorationRequired, acceptance, reason,
                    null, null, List.of(), List.of(), null);
        }
    }

    public record TaskDraft(String title, String taskType, String priority, ZonedDateTime start, ZonedDateTime due,
                            int estimatedMinutes, List<Long> knowledgePointIds, List<TaskSource> knowledgeSources,
                            String learningObjective, List<String> sourceQueries, boolean explorationRequired,
                            List<String> acceptance, String reason, String clientRef, Long milestoneId,
                            List<String> coveredGoalCriterionIds,
                            List<String> coveredMilestoneCriterionIds) {
        public TaskDraft(String title, String taskType, String priority, ZonedDateTime start, ZonedDateTime due,
                         int estimatedMinutes, List<Long> knowledgePointIds, List<TaskSource> knowledgeSources,
                         String learningObjective, List<String> sourceQueries, boolean explorationRequired,
                         List<String> acceptance, String reason) {
            this(title, taskType, priority, start, due, estimatedMinutes, knowledgePointIds, knowledgeSources,
                    learningObjective, sourceQueries, explorationRequired, acceptance, reason,
                    null, null, List.of(), List.of());
        }
    }

    /** 仅供历史测试/兼容入口使用；正式 PlanningService 会传入画像时段。 */
    public List<TaskDraft> create(LocalDate goalStart, LocalDate goalDue, ZoneId zone,
                                  List<Map<String, Object>> knowledgePoints, String goalName) {
        List<TaskContent> contents = new ArrayList<>();
        int count = Math.max(4, Math.min(10, Math.max(1, knowledgePoints.size()) * 2));
        for (int index = 0; index < count; index++) {
            Map<String, Object> point = knowledgePoints.isEmpty()
                    ? Map.of("id", 0L, "name", goalName)
                    : knowledgePoints.get(index % knowledgePoints.size());
            long id = ((Number) point.get("id")).longValue();
            String name = String.valueOf(point.get("name"));
            contents.add(new TaskContent("学习并练习「" + name + "」", "LEARNING", "HIGH", DEFAULT_MINUTES,
                    id == 0 ? List.of() : List.of(id), List.of(), "能够解释并应用「" + name + "」",
                    List.of(name + " 官方教程", name + " 练习"), id == 0,
                    List.of("完成学习笔记", "完成练习并通过知识块测试"),
                    "依据目标「" + goalName + "」、知识依赖和当前可用时间生成"));
        }
        // 兼容入口没有正式画像时段，不能再伪造 09:00，因此返回空集合。
        return List.of();
    }

    public List<TaskDraft> schedule(List<TaskContent> contents, LocalDate goalStart, LocalDate goalDue, ZoneId zone) {
        return schedule(contents, goalStart, goalDue, zone, List.of(), List.of(), new BigDecimal("0.85"));
    }

    public List<TaskDraft> schedule(List<TaskContent> contents, LocalDate goalStart, LocalDate goalDue, ZoneId zone,
                                    List<Slot> slots, List<DayException> exceptions, BigDecimal capacityRatio) {
        return schedule(contents, goalStart, goalDue, zone, slots, exceptions, capacityRatio,
                List.of(), 2, 45);
    }

    /**
     * 稳定首次适配排期。dailyRecommendedTasks 与 focusMinutes 只影响偏好，不构成拒绝条件。
     * 每个任务必须完整落在单一 slot，日期例外只覆盖分钟容量，不创造开始时间。
     */
    public List<TaskDraft> schedule(List<TaskContent> contents, LocalDate earliest, LocalDate latest, ZoneId zone,
                                    List<Slot> slots, List<DayException> exceptions, BigDecimal capacityRatio,
                                    List<OccupiedTask> occupiedTasks, int dailyRecommendedTasks, int focusMinutes) {
        if (contents == null || contents.isEmpty()) return List.of();
        if (earliest == null || latest == null || latest.isBefore(earliest))
            throw capacity("计划日期范围无效");
        if (slots == null || slots.isEmpty()) throw capacity("正式画像没有可定位的学习时段");

        BigDecimal ratio = capacityRatio == null ? new BigDecimal("0.85")
                : capacityRatio.max(new BigDecimal("0.1")).min(BigDecimal.ONE);
        Map<Integer, List<Slot>> weekly = new HashMap<>();
        for (Slot slot : slots) {
            if (slot.minutes() <= 0) continue;
            weekly.computeIfAbsent(slot.weekday(), ignored -> new ArrayList<>()).add(slot);
        }
        weekly.values().forEach(values -> values.sort(Comparator.comparing(Slot::start)));
        Map<LocalDate, Integer> overrides = new HashMap<>();
        if (exceptions != null) for (DayException exception : exceptions)
            overrides.put(exception.date(), exception.availableMinutes());

        List<OccupiedTask> occupied = new ArrayList<>(occupiedTasks == null ? List.of() : occupiedTasks);
        List<TaskDraft> result = new ArrayList<>();
        ZonedDateTime cursor = ZonedDateTime.of(earliest, LocalTime.MIN, zone);
        int softDailyTarget = Math.max(1, dailyRecommendedTasks);

        for (TaskContent content : contents) {
            requireDuration(content.estimatedMinutes());
            LocalDate contentLatest = content.latestDate() == null || content.latestDate().isAfter(latest)
                    ? latest : content.latestDate();
            Placement placement = findPlacement(content.estimatedMinutes(), cursor, contentLatest, zone,
                    weekly, overrides, ratio, occupied, softDailyTarget);
            if (placement == null) throw capacity("可用时段或容量不足，无法安排全部候选任务");
            // dueAt 是截止时间；当前确定性排期以该 slot 结束作为最晚完成时间，而非占用结束。
            ZonedDateTime due = placement.slotEnd();
            TaskDraft draft = new TaskDraft(content.title(), content.taskType(), content.priority(),
                    placement.start(), due, content.estimatedMinutes(), safe(content.knowledgePointIds()),
                    safe(content.knowledgeSources()), content.learningObjective(), safe(content.sourceQueries()),
                    content.explorationRequired(), safe(content.acceptance()), content.reason(), content.clientRef(),
                    content.milestoneId(), safe(content.coveredGoalCriterionIds()),
                    safe(content.coveredMilestoneCriterionIds()));
            result.add(draft);
            occupied.add(new OccupiedTask(content.clientRef(), draft.start(), draft.estimatedMinutes()));
            cursor = draft.start();
        }
        return List.copyOf(result);
    }

    private Placement findPlacement(int minutes, ZonedDateTime cursor, LocalDate latest, ZoneId zone,
                                    Map<Integer, List<Slot>> weekly, Map<LocalDate, Integer> overrides,
                                    BigDecimal ratio, List<OccupiedTask> occupied, int softDailyTarget) {
        List<LocalDate> dates = cursor.toLocalDate().datesUntil(latest.plusDays(1)).toList();
        // 第一轮优先任务数低于建议节奏的日期；第二轮允许超过软目标。
        for (boolean preferBelowTarget : List.of(true, false)) {
            for (LocalDate date : dates) {
                List<Slot> daySlots = weekly.getOrDefault(date.getDayOfWeek().getValue(), List.of());
                if (daySlots.isEmpty()) continue;
                Integer override = overrides.get(date);
                if (override != null && override == 0) continue;
                int existingMinutes = occupied.stream().filter(task -> task.start().toLocalDate().equals(date))
                        .mapToInt(OccupiedTask::estimatedMinutes).sum();
                long existingCount = occupied.stream().filter(task -> task.start().toLocalDate().equals(date)).count();
                if (preferBelowTarget && existingCount >= softDailyTarget) continue;
                int rawCapacity = override == null ? daySlots.stream().mapToInt(Slot::minutes).sum() : override;
                int safeCapacity = BigDecimal.valueOf(rawCapacity).multiply(ratio)
                        .setScale(0, RoundingMode.DOWN).intValue();
                if (existingMinutes + minutes > safeCapacity) continue;
                for (Slot slot : daySlots) {
                    ZonedDateTime slotStart = ZonedDateTime.of(date, slot.start(), zone);
                    ZonedDateTime slotEnd = slotStart.plusMinutes(slot.minutes());
                    ZonedDateTime candidate = slotStart.isBefore(cursor) ? cursor : slotStart;
                    List<OccupiedTask> inSlot = occupied.stream()
                            .filter(task -> overlaps(task.start(), task.end(), slotStart, slotEnd))
                            .sorted(Comparator.comparing(OccupiedTask::start)).toList();
                    for (OccupiedTask task : inSlot) {
                        if (!candidate.plusMinutes(minutes).isAfter(task.start())) break;
                        if (candidate.isBefore(task.end())) candidate = task.end();
                    }
                    if (!candidate.isBefore(slotStart)) {
                        ZonedDateTime occupiedEnd = candidate.plusMinutes(minutes);
                        ZonedDateTime occupiedStart = candidate;
                        if (!occupiedEnd.isAfter(slotEnd)
                                && occupied.stream().noneMatch(task -> overlaps(occupiedStart, occupiedEnd,
                                task.start(), task.end()))) {
                            return new Placement(occupiedStart, slotEnd);
                        }
                    }
                }
            }
        }
        return null;
    }

    static boolean overlaps(ZonedDateTime aStart, ZonedDateTime aEnd,
                            ZonedDateTime bStart, ZonedDateTime bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    private static void requireDuration(int minutes) {
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES)
            throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID, "单任务时长必须为 10～120 分钟");
    }

    private static BusinessException capacity(String message) {
        return new BusinessException(ErrorCode.PLAN_CAPACITY_EXCEEDED, message);
    }

    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : List.copyOf(values); }
    private record Placement(ZonedDateTime start, ZonedDateTime slotEnd) { }
}
