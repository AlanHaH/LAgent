package com.adaptivelearning.planning.application;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

@Component
public class RuleBasedPlanner {
    /** 历史画像没有可用时段时的兼容兜底时间。 */
    private static final LocalTime DEFAULT_START = LocalTime.of(9, 0);
    /** 单任务时长上限，与 PlanValidationPolicy 的 10～120 分钟校验保持一致 */
    private static final int MAX_MINUTES = 120;
    /** 规则兜底生成时的默认单任务时长（分钟） */
    private static final int DEFAULT_MINUTES = 60;

    public record Slot(int weekday,LocalTime start,int minutes){}
    public record DayException(LocalDate date,int availableMinutes){}
    public record TaskDraft(String title,String taskType,String priority,ZonedDateTime start,ZonedDateTime due,
                            int estimatedMinutes,List<Long> knowledgePointIds,List<TaskSource> knowledgeSources,
                            String learningObjective,List<String> sourceQueries,boolean explorationRequired,
                            List<String> acceptance,String reason){}
    public record TaskSource(long chunkId,String documentId,String documentName,int chunkNo,
                             String quotePreview,Integer pageFrom,Integer pageTo){}

    /** 规则兜底：模型不可用时按知识块逐天生成任务，每天一个，固定开始时间 */
    public List<TaskDraft> create(LocalDate goalStart,LocalDate goalDue,ZoneId zone,List<Map<String,Object>> knowledgePoints,
                                  String goalName){
        LocalDate date=LocalDate.now(zone).isAfter(goalStart)?LocalDate.now(zone):goalStart;
        List<TaskDraft> tasks=new ArrayList<>();int kpIndex=0;
        while(!date.isAfter(goalDue)&&tasks.size()<Math.max(4,Math.min(10,knowledgePoints.size()*2))){
            Map<String,Object> kp=knowledgePoints.isEmpty()?Map.of("id",0L,"name",goalName):knowledgePoints.get(kpIndex++%knowledgePoints.size());
            String name=String.valueOf(kp.get("name"));long kpId=((Number)kp.get("id")).longValue();
            ZonedDateTime start=ZonedDateTime.of(date,DEFAULT_START,zone);ZonedDateTime due=start.plusMinutes(DEFAULT_MINUTES);
            tasks.add(new TaskDraft("学习并练习「"+name+"」","LEARNING","HIGH",start,due,DEFAULT_MINUTES,
                    kpId==0?List.of():List.of(kpId),List.of(),"能够解释并应用「"+name+"」",
                    List.of(name+" 官方教程",name+" 练习"),kpId==0,
                    List.of("完成学习笔记","完成练习并通过知识块测试"),
                    "依据目标「"+goalName+"」、知识依赖和当前可用时间生成"));
            date=date.plusDays(1);
        }
        return tasks;
    }

    /** 兼容入口：旧调用没有画像约束时使用每日 09:00 兜底。 */
    public List<TaskDraft> schedule(List<TaskContent> contents,LocalDate goalStart,LocalDate goalDue,ZoneId zone){
        return schedule(contents,goalStart,goalDue,zone,List.of(),List.of(),new BigDecimal("0.85"));
    }

    /** 模型候选排期：只使用用户可用时段，日期例外覆盖当天容量，并应用容量安全系数。 */
    public List<TaskDraft> schedule(List<TaskContent> contents,LocalDate goalStart,LocalDate goalDue,ZoneId zone,
                                    List<Slot> slots,List<DayException> exceptions,BigDecimal capacityRatio){
        LocalDate date=LocalDate.now(zone).isAfter(goalStart)?LocalDate.now(zone):goalStart;
        Map<Integer,List<Slot>> weekly=new HashMap<>();
        for(Slot slot:slots)weekly.computeIfAbsent(slot.weekday(),ignored->new ArrayList<>()).add(slot);
        weekly.values().forEach(values->values.sort(Comparator.comparing(Slot::start)));
        Map<LocalDate,Integer> overrides=new HashMap<>();
        for(DayException exception:exceptions)overrides.put(exception.date(),exception.availableMinutes());
        BigDecimal safeRatio=capacityRatio==null?new BigDecimal("0.85"):
                capacityRatio.max(new BigDecimal("0.1")).min(BigDecimal.ONE);
        List<TaskDraft> tasks=new ArrayList<>();int idx=0;
        while(!date.isAfter(goalDue)&&idx<contents.size()){
            TaskContent c=contents.get(idx);
            int minutes=Math.min(c.estimatedMinutes(),MAX_MINUTES);
            List<Slot> daySlots=weekly.getOrDefault(date.getDayOfWeek().getValue(),List.of());
            Integer override=overrides.get(date);
            LocalTime selectedStart=null;
            if(override!=null){
                int usable=BigDecimal.valueOf(override).multiply(safeRatio).setScale(0,RoundingMode.DOWN).intValue();
                if(usable>=minutes)selectedStart=daySlots.isEmpty()?DEFAULT_START:daySlots.get(0).start();
            }else if(daySlots.isEmpty()&&slots.isEmpty()){
                selectedStart=DEFAULT_START;
            }else{
                for(Slot slot:daySlots){
                    int usable=BigDecimal.valueOf(slot.minutes()).multiply(safeRatio)
                            .setScale(0,RoundingMode.DOWN).intValue();
                    if(usable>=minutes){selectedStart=slot.start();break;}
                }
            }
            if(selectedStart==null){date=date.plusDays(1);continue;}
            ZonedDateTime start=ZonedDateTime.of(date,selectedStart,zone);
            ZonedDateTime due=start.plusMinutes(minutes);
            tasks.add(new TaskDraft(c.title(),c.taskType(),c.priority(),start,due,minutes,
                    c.knowledgePointIds(),c.knowledgeSources(),c.learningObjective(),c.sourceQueries(),
                    c.explorationRequired(),c.acceptance(),c.reason()));
            idx++;date=date.plusDays(1);
        }
        return tasks;
    }

    public record TaskContent(String title,String taskType,String priority,int estimatedMinutes,
                              List<Long> knowledgePointIds,List<TaskSource> knowledgeSources,
                              String learningObjective,List<String> sourceQueries,boolean explorationRequired,
                              List<String> acceptance,String reason){}
}
