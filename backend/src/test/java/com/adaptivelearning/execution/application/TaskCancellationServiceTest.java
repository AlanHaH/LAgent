package com.adaptivelearning.execution.application;

import com.adaptivelearning.execution.domain.LearningTaskEntity;
import com.adaptivelearning.execution.infrastructure.LearningTaskMapper;
import com.adaptivelearning.planning.domain.OutboxEventEntity;
import com.adaptivelearning.planning.infrastructure.PlanningMappers.OutboxMapper;
import com.adaptivelearning.shared.security.CurrentUser;
import com.adaptivelearning.support.application.AuditService;
import com.adaptivelearning.support.domain.UserEntity;
import com.adaptivelearning.support.infrastructure.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskCancellationServiceTest {
    private final LearningTaskMapper tasks = mock(LearningTaskMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final StudySessionService sessions = mock(StudySessionService.class);
    private final OutboxMapper outbox = mock(OutboxMapper.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final TaskCancellationService service = new TaskCancellationService(tasks, users, sessions,
            outbox, jdbc, new ObjectMapper().findAndRegisterModules(), audit);
    private LearningTaskEntity task;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(42L, "u-42", "student", "", Set.of("STUDENT"), Set.of()), null, List.of()));
        UserEntity user = new UserEntity();
        user.setId(42L);
        when(users.lockById(42L)).thenReturn(user);
        task = new LearningTaskEntity();
        task.setId(100L);
        task.setPublicId("task-100");
        task.setUserId(42L);
        task.setLifecycleStatus("NOT_STARTED");
        task.setScheduledStart(Instant.now());
        when(tasks.lockOwnedByPublicId("task-100", 42L)).thenReturn(task);
        when(tasks.lockById(100L)).thenReturn(task);
        when(tasks.updateById(any(LearningTaskEntity.class))).thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(outbox.insert(any(OutboxEventEntity.class))).thenReturn(1);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userCancellationWritesExactlyOneStatusChangeAndSource() throws Exception {
        service.cancelUser("task-100", "用户改变学习安排");
        service.cancelUser("task-100", "重复请求");

        assertThat(task.getLifecycleStatus()).isEqualTo("CANCELED");
        verify(sessions, times(1)).stopOpenForTaskLocked(100L, 42L);
        verify(tasks, times(1)).updateById(task);
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
        ArgumentCaptor<OutboxEventEntity> event = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outbox, times(1)).insert(event.capture());
        assertThat(new ObjectMapper().readTree(event.getValue().getPayloadJson()).path("source").asText())
                .isEqualTo("USER");
    }

    @Test
    void planPublicationUsesExplicitNonRecursiveSource() throws Exception {
        service.cancelForPlanPublication(100L, 42L, "新计划移除旧任务");

        ArgumentCaptor<OutboxEventEntity> event = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outbox).insert(event.capture());
        assertThat(new ObjectMapper().readTree(event.getValue().getPayloadJson()).path("source").asText())
                .isEqualTo("PLAN_PUBLICATION");
    }

    @Test
    void cancellationMethodsAreMandatoryAndNeverRequiresNew() throws Exception {
        for (Method method : List.of(
                TaskCancellationService.class.getMethod("cancelUser", String.class, String.class),
                TaskCancellationService.class.getMethod("cancelForPlanPublication", long.class, long.class, String.class))) {
            Transactional annotation = method.getAnnotation(Transactional.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
            assertThat(annotation.propagation()).isNotEqualTo(Propagation.REQUIRES_NEW);
        }
    }
}
