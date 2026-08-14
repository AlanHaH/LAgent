package com.adaptivelearning.planning.application;

import com.adaptivelearning.planning.domain.OutboxEventEntity;
import com.adaptivelearning.planning.infrastructure.PlanningMappers.OutboxMapper;
import com.adaptivelearning.support.application.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxProcessorCancellationTest {
    private final OutboxMapper outbox = mock(OutboxMapper.class);
    private final PlanningService planning = mock(PlanningService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OutboxProcessor processor = new OutboxProcessor(outbox, planning, notifications,
            new ObjectMapper(), jdbc);

    @Test
    void userCanceledPredecessorTriggersOneOptimizationWhenGoalIsActive() {
        arrange("USER");
        when(jdbc.queryForList(startsWith("SELECT task.user_id"), eq("task-1")))
                .thenReturn(List.of(Map.of("user_id", 42L, "goal_id", 7L,
                        "goal_status", "ACTIVE", "has_successor", 1)));

        processor.process(1L);

        verify(planning).submitAutomaticOptimization(42L, 7L, "corr-1");
        verify(notifications).create(eq(42L), eq("PLAN_OPTIMIZATION"), anyString(), anyString(),
                eq("GOAL"), eq("7"), eq("CANCELED_PREDECESSOR:corr-1"));
    }

    @Test
    void planAndParentCancellationNeverRecursivelyOptimize() {
        for (String source : List.of("PLAN_PUBLICATION", "PARENT_GOAL", "PARENT_PROJECT", "PARENT_MILESTONE")) {
            arrange(source);
            processor.process(1L);
        }

        verify(planning, never()).submitAutomaticOptimization(anyLong(), anyLong(), anyString());
        verify(notifications, never()).create(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void inactiveGoalDoesNotCreateAutomaticOptimization() {
        arrange("USER");
        when(jdbc.queryForList(startsWith("SELECT task.user_id"), eq("task-1")))
                .thenReturn(List.of(Map.of("user_id", 42L, "goal_id", 7L,
                        "goal_status", "PAUSED", "has_successor", 1)));

        processor.process(1L);

        verify(planning, never()).submitAutomaticOptimization(anyLong(), anyLong(), anyString());
    }

    private void arrange(String source) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(1L);
        event.setAggregateType("LEARNING_TASK");
        event.setAggregateId("task-1");
        event.setEventType("TaskStatusChanged");
        event.setPayloadJson("{\"from\":\"NOT_STARTED\",\"to\":\"CANCELED\",\"source\":\"" + source + "\"}");
        event.setCorrelationId("corr-1");
        event.setAttempts(0);
        when(jdbc.update(startsWith("UPDATE outbox_event SET status='PROCESSING'"), eq(1L))).thenReturn(1);
        when(outbox.selectById(1L)).thenReturn(event);
    }
}
