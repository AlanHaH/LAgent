package com.adaptivelearning.planning.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** M05 私有的正式任务容量模拟规则。 */
public final class PlanningCapacityPolicy {
    private static final Set<String> OCCUPYING = Set.of("NOT_STARTED", "IN_PROGRESS", "PAUSED", "BLOCKED");
    private PlanningCapacityPolicy() { }

    public record Task(String ref, long goalId, Long projectId, Long milestoneId, String status,
                       ZonedDateTime scheduledStart, ZonedDateTime dueAt, int estimatedMinutes,
                       LocalDate earliestDate, LocalDate latestDate) {
        public ZonedDateTime occupiedEnd() { return scheduledStart.plusMinutes(estimatedMinutes); }
    }
    public record Context(ZoneId zone, int weekStart, BigDecimal capacityRatio,
                          List<RuleBasedPlanner.Slot> slots,
                          List<RuleBasedPlanner.DayException> exceptions,
                          Map<Long, Integer> goalWeeklyBudgets) { }
    public record Issue(String code, String field, String message) { }

    public static List<Issue> validate(List<Task> tasks, Context context, Instant now) {
        List<Task> occupying = (tasks == null ? List.<Task>of() : tasks).stream()
                .filter(task -> task.status() == null || OCCUPYING.contains(task.status()))
                .filter(task -> task.scheduledStart() != null && task.dueAt() != null)
                .filter(task -> task.occupiedEnd().toInstant().isAfter(now))
                .sorted(Comparator.comparing(Task::scheduledStart).thenComparing(Task::ref)).toList();
        List<Issue> issues = new ArrayList<>();
        Map<LocalDate, Integer> overrides = new HashMap<>();
        if (context.exceptions() != null) for (RuleBasedPlanner.DayException exception : context.exceptions())
            overrides.put(exception.date(), exception.availableMinutes());
        Map<Integer, List<RuleBasedPlanner.Slot>> slots = new HashMap<>();
        if (context.slots() != null) for (RuleBasedPlanner.Slot slot : context.slots())
            slots.computeIfAbsent(slot.weekday(), ignored -> new ArrayList<>()).add(slot);

        for (Task task : occupying) {
            String field = "tasks[" + task.ref() + "]";
            if (task.estimatedMinutes() < 10 || task.estimatedMinutes() > 120)
                issues.add(issue("TASK_DURATION_INVALID", field, "单任务时长必须为 10～120 分钟"));
            if (task.occupiedEnd().isAfter(task.dueAt()))
                issues.add(issue("TASK_DEADLINE_INVALID", field, "任务占用结束时间不能晚于 dueAt"));
            LocalDate date = task.scheduledStart().toLocalDate();
            if (task.earliestDate() != null && date.isBefore(task.earliestDate())
                    || task.latestDate() != null && date.isAfter(task.latestDate()))
                issues.add(issue("TASK_DATE_BOUNDARY", field, "任务日期超出 Goal/Project/Milestone 边界"));
            if (task.latestDate() != null && (task.occupiedEnd().toLocalDate().isAfter(task.latestDate())
                    || task.dueAt().toLocalDate().isAfter(task.latestDate())))
                issues.add(issue("TASK_DEADLINE_BOUNDARY", field, "任务截止时间超出 Goal/Project/Milestone 边界"));
            Integer override = overrides.get(date);
            if (override != null && override == 0)
                issues.add(issue("AVAILABILITY_EXCEPTION_ZERO", field, "当天日期例外禁止排期"));
            boolean insideSlot = slots.getOrDefault(date.getDayOfWeek().getValue(), List.of()).stream()
                    .anyMatch(slot -> {
                        ZonedDateTime start = ZonedDateTime.of(date, slot.start(), context.zone());
                        return !task.scheduledStart().isBefore(start)
                                && !task.occupiedEnd().isAfter(start.plusMinutes(slot.minutes()));
                    });
            if (!insideSlot) issues.add(issue("OUTSIDE_AVAILABILITY", field, "任务未完整位于一个正式可用时段"));
        }
        for (int left = 0; left < occupying.size(); left++) for (int right = left + 1; right < occupying.size(); right++) {
            Task a = occupying.get(left), b = occupying.get(right);
            if (RuleBasedPlanner.overlaps(a.scheduledStart(), a.occupiedEnd(), b.scheduledStart(), b.occupiedEnd()))
                issues.add(issue("TASK_TIME_CONFLICT", a.ref() + "," + b.ref(), "正式任务时间发生冲突"));
        }

        BigDecimal ratio = context.capacityRatio() == null ? new BigDecimal("0.85") : context.capacityRatio();
        Map<LocalDate,Integer> dailyUsed=new HashMap<>();
        Map<LocalDate,Map<Long,Integer>> weeklyGoalMinutes=new HashMap<>();
        Map<LocalDate,Integer> weeklyUsed=new HashMap<>();
        for(Task task:occupying)for(MinuteSlice slice:split(task,context.weekStart())){
            dailyUsed.merge(slice.date(),slice.minutes(),Integer::sum);
            weeklyUsed.merge(slice.weekStart(),slice.minutes(),Integer::sum);
            weeklyGoalMinutes.computeIfAbsent(slice.weekStart(),ignored->new HashMap<>())
                    .merge(task.goalId(),slice.minutes(),Integer::sum);
        }
        dailyUsed.forEach((date,used)->{
            Integer override=overrides.get(date);
            int raw=override==null?slots.getOrDefault(date.getDayOfWeek().getValue(),List.of()).stream()
                    .mapToInt(RuleBasedPlanner.Slot::minutes).sum():override;
            int available=BigDecimal.valueOf(raw).multiply(ratio).setScale(0,RoundingMode.DOWN).intValue();
            if(used>available)issues.add(issue("USER_DAILY_CAPACITY_EXCEEDED",date.toString(),
                    "当天全部正式任务超过画像安全容量"));
        });
        for (Map.Entry<LocalDate, Integer> entry : weeklyUsed.entrySet()) {
            LocalDate weekStart = entry.getKey(), weekEnd = weekStart.plusDays(6);
            int used = entry.getValue();
            int available = 0;
            for (LocalDate date = weekStart; !date.isAfter(weekEnd); date = date.plusDays(1)) {
                Integer override = overrides.get(date);
                int raw = override == null
                        ? slots.getOrDefault(date.getDayOfWeek().getValue(), List.of()).stream()
                        .mapToInt(RuleBasedPlanner.Slot::minutes).sum()
                        : override;
                available += BigDecimal.valueOf(raw).multiply(ratio).setScale(0, RoundingMode.DOWN).intValue();
            }
            if (used > available) issues.add(issue("USER_WEEKLY_CAPACITY_EXCEEDED", weekStart.toString(),
                    "用户全部目标的周任务容量超过正式画像可用时间"));
            weeklyGoalMinutes.getOrDefault(weekStart,Map.of()).forEach((goalId, minutes) -> {
                Integer budget = context.goalWeeklyBudgets().get(goalId);
                if (budget != null && minutes > budget)
                    issues.add(issue("GOAL_WEEKLY_BUDGET_EXCEEDED", String.valueOf(goalId),
                            "Goal 全部计划的周任务分钟超过 weeklyBudgetMinutes"));
            });
        }
        return List.copyOf(issues);
    }

    static LocalDate startOfWeek(LocalDate date, int weekStart) {
        DayOfWeek first = DayOfWeek.of(Math.max(1, Math.min(7, weekStart)));
        return date.with(TemporalAdjusters.previousOrSame(first));
    }

    private static List<MinuteSlice> split(Task task,int weekStart){
        List<MinuteSlice>result=new ArrayList<>();ZonedDateTime cursor=task.scheduledStart(),end=task.occupiedEnd();
        while(cursor.isBefore(end)){
            ZonedDateTime dayEnd=cursor.toLocalDate().plusDays(1).atStartOfDay(cursor.getZone());
            ZonedDateTime boundary=dayEnd.isBefore(end)?dayEnd:end;
            int minutes=(int)Duration.between(cursor,boundary).toMinutes();
            if(minutes>0)result.add(new MinuteSlice(cursor.toLocalDate(),startOfWeek(cursor.toLocalDate(),weekStart),minutes));
            cursor=boundary;
        }
        return result;
    }
    private record MinuteSlice(LocalDate date,LocalDate weekStart,int minutes){}
    private static Issue issue(String code, String field, String message) { return new Issue(code, field, message); }
}
