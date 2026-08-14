package com.adaptivelearning.planning.application;

import com.adaptivelearning.planning.domain.OutboxEventEntity;
import com.adaptivelearning.planning.infrastructure.PlanningMappers.OutboxMapper;
import com.adaptivelearning.support.application.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OutboxProcessor {
    private final OutboxMapper outboxMapper;
    private final PlanningService planningService;
    private final NotificationService notifications;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    @Value("${app.scheduling.enabled:true}") private boolean schedulingEnabled;

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay:PT5S}")
    public void poll() {
        if (!schedulingEnabled) return;
        List<Long> ids = jdbc.query("""
                SELECT id FROM outbox_event
                WHERE status='PENDING' AND next_retry_at<=?
                ORDER BY created_at LIMIT 50
                """, (rs, row) -> rs.getLong(1), Instant.now());
        for (Long id : ids) process(id);
    }

    void process(long id) {
        if (jdbc.update("UPDATE outbox_event SET status='PROCESSING' WHERE id=? AND status='PENDING'", id) != 1)
            return;
        OutboxEventEntity event = outboxMapper.selectById(id);
        try {
            dispatch(event);
            jdbc.update("UPDATE outbox_event SET status='PROCESSED',attempts=attempts+1,processed_at=?,last_error=NULL WHERE id=?", Instant.now(),id);
        } catch (RuntimeException failure) {
            int attempts = event.getAttempts() == null ? 1 : event.getAttempts() + 1;
            String status = attempts >= 8 ? "DEAD" : "PENDING";
            long seconds = Math.min(300, 1L << Math.min(attempts, 8));
            String message=String.valueOf(failure.getMessage());
            if(message.length()>1000)message=message.substring(0,1000);
            jdbc.update("UPDATE outbox_event SET status=?,attempts=?,next_retry_at=?,last_error=? WHERE id=?",
                    status, attempts, Instant.now().plus(Duration.ofSeconds(seconds)),message,id);
        }
    }

    private void dispatch(OutboxEventEntity event) {
        if ("LEARNING_TASK".equals(event.getAggregateType()) && "TaskStatusChanged".equals(event.getEventType())) {
            JsonNode payload = read(event.getPayloadJson());
            String target = payload.path("to").asText();
            String source = payload.path("source").asText("USER");
            if ("CANCELED".equals(target)) {
                if (!"USER".equals(source)) return;
                List<Map<String, Object>> task = jdbc.queryForList("""
                        SELECT task.user_id,task.goal_id,goal.status AS goal_status,
                               EXISTS(
                                 SELECT 1 FROM task_dependency dependency
                                 JOIN learning_task successor ON successor.id=dependency.successor_task_id
                                 WHERE dependency.predecessor_task_id=task.id
                                   AND successor.user_id=task.user_id
                                   AND successor.lifecycle_status NOT IN ('COMPLETED','CANCELED')
                                   AND successor.deleted_at IS NULL
                               ) has_successor
                        FROM learning_task task
                        JOIN learning_goal goal ON goal.id=task.goal_id AND goal.deleted_at IS NULL
                        WHERE task.public_id=? AND task.deleted_at IS NULL
                        """, event.getAggregateId());
                if (task.isEmpty() || !"ACTIVE".equals(task.get(0).get("goal_status"))
                        || ((Number) task.get(0).get("has_successor")).intValue() == 0) return;
                long userId = ((Number) task.get(0).get("user_id")).longValue();
                long goalId = ((Number) task.get(0).get("goal_id")).longValue();
                planningService.submitAutomaticOptimization(userId, goalId, event.getCorrelationId());
                notifications.create(userId, "PLAN_OPTIMIZATION", "前置任务已取消，需要重新规划",
                        "后继任务仍保持阻塞。系统会生成待你审阅的优化提案，不会直接修改正式任务。",
                        "GOAL", String.valueOf(goalId), "CANCELED_PREDECESSOR:" + event.getCorrelationId());
                return;
            }
            if (!"COMPLETED".equals(target)) return;
            List<Map<String,Object>> task = jdbc.queryForList("""
                    SELECT task.user_id,task.goal_id,task.title,
                           EXISTS(SELECT 1 FROM learning_block block
                                  WHERE block.task_id=task.id AND block.deleted_at IS NULL) has_block
                    FROM learning_task task
                    WHERE task.public_id=? AND task.deleted_at IS NULL
                    """, event.getAggregateId());
            if (task.isEmpty()) return;
            if (((Number) task.get(0).get("has_block")).intValue() != 0) return;
            long userId = ((Number) task.get(0).get("user_id")).longValue();
            long goalId = ((Number) task.get(0).get("goal_id")).longValue();
            planningService.submitAutomaticOptimization(userId, goalId, event.getCorrelationId());
            notifications.create(userId,"PLAN_OPTIMIZATION","学习反馈已进入计划分析",
                    "系统会生成待你审阅的优化提案，不会直接修改正式任务。","GOAL",
                    String.valueOf(goalId),"AUTO_OPTIMIZATION:"+event.getCorrelationId());
            return;
        }
        if ("LEARNING_BLOCK".equals(event.getAggregateType())
                && "LearningBlockAssessed".equals(event.getEventType())) {
            JsonNode payload = read(event.getPayloadJson());
            long userId = payload.path("userId").asLong();
            long goalId = payload.path("goalId").asLong();
            boolean passed = payload.path("passed").asBoolean();
            planningService.submitAutomaticOptimization(userId, goalId, event.getCorrelationId());
            notifications.create(userId, "PLAN_OPTIMIZATION",
                    passed ? "知识块验收结果已进入计划分析" : "块测结果已进入补强分析",
                    passed ? "系统会依据已通过的学习证据生成待你审阅的优化提案。"
                            : "系统会根据本次块测结果评估是否需要补强，不会直接修改正式任务。",
                    "GOAL", String.valueOf(goalId), "BLOCK_ASSESSMENT:" + event.getCorrelationId());
            return;
        }
        if ("PROJECT_MILESTONE".equals(event.getAggregateType())
                && "MilestoneCompleted".equals(event.getEventType())) {
            JsonNode payload = read(event.getPayloadJson());
            long userId = payload.path("userId").asLong();
            long projectId = payload.path("projectId").asLong();
            List<Long> goals = jdbc.query("""
                    SELECT goal.id FROM learning_goal goal
                    JOIN goal_project link ON link.goal_id=goal.id
                    WHERE link.project_id=? AND goal.user_id=? AND goal.status='ACTIVE'
                      AND goal.deleted_at IS NULL
                    """, (rs, row) -> rs.getLong(1), projectId, userId);
            for (Long goalId : goals) {
                planningService.submitAutomaticOptimization(userId, goalId,
                        event.getCorrelationId() + "-" + goalId);
            }
            return;
        }
        if ("LEARNING_FEEDBACK".equals(event.getAggregateType())) {
            JsonNode payload = read(event.getPayloadJson());
            long userId = payload.path("userId").asLong();
            List<Long> goals = jdbc.query("SELECT id FROM learning_goal WHERE user_id=? AND status='ACTIVE' AND deleted_at IS NULL",
                    (rs,row)->rs.getLong(1),userId);
            for(Long goalId:goals) planningService.submitAutomaticOptimization(userId,goalId,event.getCorrelationId()+"-"+goalId);
        }
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (Exception failure) { throw new IllegalArgumentException("invalid outbox payload", failure); }
    }
}
