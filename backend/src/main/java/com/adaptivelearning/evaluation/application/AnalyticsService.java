package com.adaptivelearning.evaluation.application;

import com.adaptivelearning.evaluation.domain.*;
import com.adaptivelearning.evaluation.infrastructure.EvaluationMappers.ReportMapper;
import com.adaptivelearning.execution.application.StudySessionService;
import com.adaptivelearning.execution.domain.StudySessionEntity;
import com.adaptivelearning.execution.infrastructure.ExecutionMappers.SessionMapper;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.*;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class AnalyticsService {
  private final JdbcTemplate jdbc;private final SessionMapper sessionMapper;private final ReportMapper reportMapper;private final ObjectMapper json;
  public record Metric(Object value,BigDecimal numerator,BigDecimal denominator,LocalDate periodStart,LocalDate periodEnd,String timezone,Instant refreshedAt,String metricVersion){}
  public record DailyTime(LocalDate date,long autoSeconds,long manualSeconds,long totalSeconds){}
  public record Overview(Map<String,Metric>metrics,List<DailyTime>studyTime,List<Map<String,Object>>mastery){}

  public Overview overview(LocalDate start,LocalDate end){Range r=range(start,end);Map<String,Metric>m=taskMetrics(r);List<DailyTime>time=studyTime(r);long auto=time.stream().mapToLong(DailyTime::autoSeconds).sum(),manual=time.stream().mapToLong(DailyTime::manualSeconds).sum();m.put("effectiveStudySeconds",metric(auto+manual,BigDecimal.valueOf(auto+manual),BigDecimal.ONE,r));m.put("automaticStudySeconds",metric(auto,BigDecimal.valueOf(auto),BigDecimal.ONE,r));m.put("manualStudySeconds",metric(manual,BigDecimal.valueOf(manual),BigDecimal.ONE,r));return new Overview(m,time,mastery());}
  public List<DailyTime> studyTime(LocalDate start,LocalDate end){return studyTime(range(start,end));}
  public Map<String,Metric>taskPerformance(LocalDate start,LocalDate end){return taskMetrics(range(start,end));}
  public List<Map<String,Object>>mastery(){return jdbc.query("""
    SELECT km.knowledge_point_id,kp.name,km.score,km.confidence,km.level,km.evidence_count,km.calculated_at
    FROM knowledge_mastery km JOIN knowledge_point kp ON kp.id=km.knowledge_point_id WHERE km.user_id=? ORDER BY kp.name
    """,(rs,row)->Map.of("knowledgePointId",rs.getLong(1),"name",rs.getString(2),"score",rs.getBigDecimal(3),"confidence",rs.getBigDecimal(4),"level",rs.getString(5),"evidenceCount",rs.getInt(6),"calculatedAt",rs.getTimestamp(7).toInstant()),SecurityUtils.currentUserId());}

  public StudyReportEntity generateReport(String type,LocalDate start,LocalDate end){Range r=range(start,end);Overview o=overview(start,end);Integer revision=jdbc.queryForObject("SELECT COALESCE(MAX(revision_no),0)+1 FROM study_report WHERE user_id=? AND type=? AND period_start=? AND period_end=?",Integer.class,r.userId,type,start,end);StudyReportEntity report=new StudyReportEntity();report.setPublicId(UUID.randomUUID().toString());report.setUserId(r.userId);report.setType(type);report.setPeriodStart(start);report.setPeriodEnd(end);report.setTimezone(r.timezone);report.setRevisionNo(revision==null?1:revision);report.setMetricSnapshotJson(toJson(o));Metric completion=o.metrics().get("taskCompletionRate");Metric time=o.metrics().get("effectiveStudySeconds");String completionText=completion.value()==null?"本周期没有计划任务，因此完成率不适用":completion.value()+"%";report.setNarrative("本周期有效学习 "+time.value()+" 秒，任务完成率为 "+completionText+"。报告数字来自统一统计服务；建议结合掌握度置信度安排下一阶段学习。");report.setStatus("COMPLETED");report.setCreatedAt(Instant.now());reportMapper.insert(report);return report;}
  public List<StudyReportEntity>reports(){return reportMapper.selectList(new LambdaQueryWrapper<StudyReportEntity>().eq(StudyReportEntity::getUserId,SecurityUtils.currentUserId()).orderByDesc(StudyReportEntity::getPeriodEnd,StudyReportEntity::getRevisionNo));}
  public StudyReportEntity report(String id){StudyReportEntity r=reportMapper.selectOne(new LambdaQueryWrapper<StudyReportEntity>().eq(StudyReportEntity::getPublicId,id).eq(StudyReportEntity::getUserId,SecurityUtils.currentUserId()));if(r==null)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"报告不存在");return r;}

  private Map<String,Metric>taskMetrics(Range r){Map<String,Object>v=jdbc.queryForMap("""
    SELECT COUNT(*) planned,
      SUM(CASE WHEN lifecycle_status='COMPLETED' AND completed_at<? THEN 1 ELSE 0 END) completed,
      SUM(CASE WHEN lifecycle_status='COMPLETED' AND due_at IS NOT NULL AND completed_at<=due_at AND completed_at<? THEN 1 ELSE 0 END) on_time,
      SUM(CASE WHEN lifecycle_status='COMPLETED' AND due_at IS NOT NULL AND completed_at<? THEN 1 ELSE 0 END) completed_with_due,
      SUM(CASE WHEN due_at IS NOT NULL AND due_at<? AND lifecycle_status NOT IN ('COMPLETED','CANCELED') THEN 1 ELSE 0 END) overdue,
      SUM(CASE WHEN due_at IS NOT NULL AND due_at<? THEN 1 ELSE 0 END) due_count
    FROM learning_task WHERE user_id=? AND lifecycle_status<>'CANCELED' AND deleted_at IS NULL
      AND ((scheduled_start>=? AND scheduled_start<?) OR (due_at>=? AND due_at<?)) AND created_at<?
    """,r.to,r.to,r.to,r.to,r.to,r.userId,r.from,r.to,r.from,r.to,r.to);long planned=n(v,"planned"),completed=n(v,"completed"),onTime=n(v,"on_time"),completedWithDue=n(v,"completed_with_due"),overdue=n(v,"overdue"),due=n(v,"due_count");Map<String,Metric>m=new LinkedHashMap<>();m.put("plannedTasks",metric(planned,BigDecimal.valueOf(planned),BigDecimal.ONE,r));m.put("completedTasks",metric(completed,BigDecimal.valueOf(completed),BigDecimal.ONE,r));m.put("taskCompletionRate",ratio(completed,planned,r));m.put("onTimeCompletionRate",ratio(onTime,completedWithDue,r));m.put("overdueRate",ratio(overdue,due,r));return m;}
  private List<DailyTime>studyTime(Range r){List<StudySessionEntity>sessions=sessionMapper.selectList(new LambdaQueryWrapper<StudySessionEntity>().eq(StudySessionEntity::getUserId,r.userId).eq(StudySessionEntity::getStatus,"COMPLETED").lt(StudySessionEntity::getStartedAt,r.to).gt(StudySessionEntity::getEndedAt,r.from));Map<LocalDate,long[]>days=new TreeMap<>();for(LocalDate d=r.start;!d.isAfter(r.end);d=d.plusDays(1))days.put(d,new long[2]);for(StudySessionEntity s:sessions)for(var a:StudySessionService.allocate(s,r.zone)){long[]x=days.get(a.date());if(x!=null)x["MANUAL".equals(s.getSource())?1:0]+=a.effectiveSeconds();}return days.entrySet().stream().map(e->new DailyTime(e.getKey(),e.getValue()[0],e.getValue()[1],e.getValue()[0]+e.getValue()[1])).toList();}
  private Metric ratio(long numerator,long denominator,Range r){Object value=denominator==0?null:BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator),1,RoundingMode.HALF_UP);return metric(value,BigDecimal.valueOf(numerator),BigDecimal.valueOf(denominator),r);}
  private Metric metric(Object value,BigDecimal n,BigDecimal d,Range r){return new Metric(value,n,d,r.start,r.end,r.timezone,Instant.now(),"1.0");}
  private long n(Map<String,Object>m,String k){Object v=m.get(k);return v==null?0:((Number)v).longValue();}
  private Range range(LocalDate start,LocalDate end){if(start==null||end==null||end.isBefore(start)||Duration.between(start.atStartOfDay(),end.plusDays(1).atStartOfDay()).toDays()>366)throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,"统计区间不合法");long user=SecurityUtils.currentUserId();String tz=jdbc.queryForObject("SELECT timezone FROM sys_user WHERE id=?",String.class,user);ZoneId zone=ZoneId.of(tz);return new Range(user,start,end,tz,zone,start.atStartOfDay(zone).toInstant(),end.plusDays(1).atStartOfDay(zone).toInstant());}
  private String toJson(Object v){try{return json.writeValueAsString(v);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
  private record Range(long userId,LocalDate start,LocalDate end,String timezone,ZoneId zone,Instant from,Instant to){}
}
