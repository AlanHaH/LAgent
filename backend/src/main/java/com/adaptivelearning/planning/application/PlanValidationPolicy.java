package com.adaptivelearning.planning.application;

import com.adaptivelearning.goalproject.domain.LearningGoalEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;

public final class PlanValidationPolicy {
    private PlanValidationPolicy() {}
    public record Change(String action,String title,ZonedDateTime scheduledStart,ZonedDateTime dueAt,int estimatedMinutes,
                         boolean lockedSchedule,boolean confirmRequired){}
    public record Issue(String code,String severity,String fieldPath,String message,Map<String,Object> details){}

    public static List<Issue> validate(List<Change> changes, LocalDate goalStart,LocalDate goalDue){
        List<Issue> issues=new ArrayList<>();
        Set<String> duplicate=new HashSet<>();
        for(int i=0;i<changes.size();i++){
            Change c=changes.get(i);String path="changes["+i+"]";
            if(!Set.of("ADD_TASK","RESCHEDULE_TASK","UPDATE_TASK","SPLIT_TASK","CANCEL_TASK").contains(c.action()))
                issues.add(issue("ENUM_INVALID","ERROR",path+".action","未知变更动作",Map.of()));
            if(c.title()==null||c.title().trim().length()<2||c.title().length()>200)
                issues.add(issue("TITLE_INVALID","ERROR",path+".title","任务标题必须为 2～200 字",Map.of()));
            if("CANCEL_TASK".equals(c.action())) continue;
            if(c.estimatedMinutes()<10||c.estimatedMinutes()>120)
                issues.add(issue("TASK_DURATION_INVALID","ERROR",path+".estimatedMinutes","单任务建议时长必须为 10～120 分钟",Map.of("minutes",c.estimatedMinutes())));
            if(c.scheduledStart()==null||c.dueAt()==null||!c.dueAt().isAfter(c.scheduledStart()))
                issues.add(issue("DATE_INVALID","ERROR",path,"任务时间范围无效",Map.of()));
            else{
                LocalDate date=c.scheduledStart().toLocalDate();
                if(date.isBefore(goalStart)||date.isAfter(goalDue))issues.add(issue("GOAL_DATE_EXCEEDED","ERROR",path+".scheduledStart","任务日期超出目标周期",Map.of("date",date)));
                String key=c.title().trim().toLowerCase()+"|"+date;if(!duplicate.add(key))issues.add(issue("DUPLICATE_TASK","ERROR",path,"同一天存在重复任务",Map.of()));
            }
        }
        return issues;
    }
    /** 计划依赖的目标业务字段指纹；状态由独立状态守卫处理。 */
    public static String goalFingerprint(LearningGoalEntity goal){
        String payload=String.join("|",n(goal.getName()),String.valueOf(goal.getStartDate()),String.valueOf(goal.getDueDate()),
                String.valueOf(goal.getDirectionId()),n(goal.getCustomDirection()),n(goal.getDescription()),
                n(goal.getType()),n(goal.getPriority()),String.valueOf(goal.getWeeklyBudgetMinutes()),
                n(goal.getSuccessCriteriaJson()),String.valueOf(goal.getProfileVersionId()));
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    /** 提案是否因目标变化而过期。新提案带指纹快照时比较指纹；旧提案无指纹时回退为乐观锁版本比较。 */
    public static boolean goalContextStale(String snapshotFingerprint,LearningGoalEntity current,
                                           int snapshotGoalVersion,Integer currentVersion){
        if(snapshotFingerprint!=null&&!snapshotFingerprint.isBlank())
            return !snapshotFingerprint.equals(goalFingerprint(current));
        return currentVersion==null||currentVersion!=snapshotGoalVersion;
    }

    private static String n(String s){return s==null?"":s;}
    private static Issue issue(String c,String s,String p,String m,Map<String,Object>d){return new Issue(c,s,p,m,d);}
}
