package com.adaptivelearning.planning.application;

import java.time.*;
import java.util.*;

public final class PlanValidationPolicy {
    private PlanValidationPolicy() {}
    public record Change(String action,String title,ZonedDateTime scheduledStart,ZonedDateTime dueAt,int estimatedMinutes,
                         boolean lockedSchedule,boolean confirmRequired){}
    public record Issue(String code,String severity,String fieldPath,String message,Map<String,Object> details){}

    public static List<Issue> validate(List<Change> changes, LocalDate goalStart,LocalDate goalDue,
                                       Map<LocalDate,Integer> availableMinutes,double capacityRatio){
        List<Issue> issues=new ArrayList<>();Map<LocalDate,Integer> load=new HashMap<>();
        Set<String> duplicate=new HashSet<>();
        for(int i=0;i<changes.size();i++){
            Change c=changes.get(i);String path="changes["+i+"]";
            if(!Set.of("ADD_TASK","RESCHEDULE_TASK","UPDATE_TASK","SPLIT_TASK","CANCEL_TASK").contains(c.action()))
                issues.add(issue("ENUM_INVALID","ERROR",path+".action","未知变更动作",Map.of()));
            if(c.title()==null||c.title().trim().length()<2||c.title().length()>200)
                issues.add(issue("TITLE_INVALID","ERROR",path+".title","任务标题必须为 2～200 字",Map.of()));
            if(c.estimatedMinutes()<10||c.estimatedMinutes()>120)
                issues.add(issue("TASK_DURATION_INVALID","ERROR",path+".estimatedMinutes","单任务建议时长必须为 10～120 分钟",Map.of("minutes",c.estimatedMinutes())));
            if(c.scheduledStart()==null||c.dueAt()==null||!c.dueAt().isAfter(c.scheduledStart()))
                issues.add(issue("DATE_INVALID","ERROR",path,"任务时间范围无效",Map.of()));
            else{
                LocalDate date=c.scheduledStart().toLocalDate();
                if(date.isBefore(goalStart)||date.isAfter(goalDue))issues.add(issue("GOAL_DATE_EXCEEDED","ERROR",path+".scheduledStart","任务日期超出目标周期",Map.of("date",date)));
                load.merge(date,c.estimatedMinutes(),Integer::sum);
                String key=c.title().trim().toLowerCase()+"|"+date;if(!duplicate.add(key))issues.add(issue("DUPLICATE_TASK","ERROR",path,"同一天存在重复任务",Map.of()));
            }
        }
        for(var entry:load.entrySet()){
            int capacity=(int)Math.floor(availableMinutes.getOrDefault(entry.getKey(),0)*capacityRatio);
            if(entry.getValue()>capacity)issues.add(issue("PLAN_CAPACITY_EXCEEDED","ERROR","dates."+entry.getKey(),"当日计划负载超过容量",Map.of("date",entry.getKey(),"plannedMinutes",entry.getValue(),"capacityMinutes",capacity)));
        }
        return issues;
    }
    private static Issue issue(String c,String s,String p,String m,Map<String,Object>d){return new Issue(c,s,p,m,d);}
}

