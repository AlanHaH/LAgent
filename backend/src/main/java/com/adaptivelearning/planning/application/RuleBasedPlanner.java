package com.adaptivelearning.planning.application;

import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

@Component
public class RuleBasedPlanner {
    public record Slot(int weekday,LocalTime start,int minutes){}
    public record TaskDraft(String title,String taskType,String priority,ZonedDateTime start,ZonedDateTime due,
                            int estimatedMinutes,List<Long> knowledgePointIds,List<String> acceptance,String reason){}

    public List<TaskDraft> create(LocalDate goalStart,LocalDate goalDue,ZoneId zone,List<Slot> slots,
                                  Map<LocalDate,Integer> exceptions,double capacityRatio,List<Map<String,Object>> knowledgePoints,
                                  String goalName){
        LocalDate date=LocalDate.now(zone).isAfter(goalStart)?LocalDate.now(zone):goalStart;
        List<TaskDraft> tasks=new ArrayList<>();int kpIndex=0;
        while(!date.isAfter(goalDue)&&tasks.size()<Math.max(4,Math.min(10,knowledgePoints.size()*2))){
            LocalDate currentDate=date;
            List<Slot> daily=slots.stream().filter(s->s.weekday()==currentDate.getDayOfWeek().getValue()).toList();
            int exceptionMinutes=exceptions.getOrDefault(date,-1);
            if(exceptionMinutes==0){date=date.plusDays(1);continue;}
            for(Slot slot:daily){
                int raw=exceptionMinutes>=0?exceptionMinutes:slot.minutes();int minutes=Math.min(90,(int)Math.floor(raw*capacityRatio));
                if(minutes<10)continue;
                Map<String,Object> kp=knowledgePoints.isEmpty()?Map.of("id",0L,"name",goalName):knowledgePoints.get(kpIndex++%knowledgePoints.size());
                String name=String.valueOf(kp.get("name"));long kpId=((Number)kp.get("id")).longValue();
                ZonedDateTime start=ZonedDateTime.of(date,slot.start(),zone);ZonedDateTime due=start.plusMinutes(minutes);
                tasks.add(new TaskDraft("学习并练习「"+name+"」","LEARNING","HIGH",start,due,minutes,
                        kpId==0?List.of():List.of(kpId),List.of("完成学习笔记","完成至少一道自测或实践"),
                        "依据目标「"+goalName+"」、知识依赖和当前可用时间生成"));
                break;
            }
            date=date.plusDays(1);
        }
        return tasks;
    }

    public List<TaskDraft> schedule(List<TaskContent> contents,LocalDate goalStart,LocalDate goalDue,ZoneId zone,
                                    List<Slot> slots,Map<LocalDate,Integer> exceptions,double capacityRatio){
        LocalDate date=LocalDate.now(zone).isAfter(goalStart)?LocalDate.now(zone):goalStart;
        List<TaskDraft> tasks=new ArrayList<>();int idx=0;
        while(!date.isAfter(goalDue)&&idx<contents.size()){
            LocalDate currentDate=date;
            List<Slot> daily=slots.stream().filter(s->s.weekday()==currentDate.getDayOfWeek().getValue()).toList();
            int exceptionMinutes=exceptions.getOrDefault(date,-1);
            if(exceptionMinutes==0){date=date.plusDays(1);continue;}
            for(Slot slot:daily){
                if(idx>=contents.size())break;
                int raw=exceptionMinutes>=0?exceptionMinutes:slot.minutes();
                int slotCapacity=Math.min(120,(int)Math.floor(raw*capacityRatio));
                if(slotCapacity<15)continue;
                TaskContent c=contents.get(idx);
                int minutes=Math.min(c.estimatedMinutes(),slotCapacity);
                ZonedDateTime start=ZonedDateTime.of(date,slot.start(),zone);
                ZonedDateTime due=start.plusMinutes(minutes);
                tasks.add(new TaskDraft(c.title(),c.taskType(),c.priority(),start,due,minutes,
                        c.knowledgePointIds(),c.acceptance(),c.reason()));
                idx++;break;
            }
            date=date.plusDays(1);
        }
        return tasks;
    }

    public record TaskContent(String title,String taskType,String priority,int estimatedMinutes,
                              List<Long> knowledgePointIds,List<String> acceptance,String reason){}
}
