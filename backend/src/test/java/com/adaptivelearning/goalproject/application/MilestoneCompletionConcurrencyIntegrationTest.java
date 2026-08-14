package com.adaptivelearning.goalproject.application;

import com.adaptivelearning.evaluation.application.AssessmentService;
import com.adaptivelearning.goalproject.domain.MilestoneEntity;
import com.adaptivelearning.shared.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class MilestoneCompletionConcurrencyIntegrationTest {
    @Autowired GoalProjectService service;
    @Autowired JdbcTemplate jdbc;
    @MockBean AssessmentService assessments;

    @Test
    void concurrentCompletionCreatesOnlyOneEvidenceAndOutboxEvent() throws Exception {
        Instant now = Instant.now();
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS learning_task (
                  id BIGINT PRIMARY KEY,
                  milestone_id BIGINT,
                  user_id BIGINT NOT NULL,
                  deleted_at TIMESTAMP NULL
                )
                """);
        jdbc.update("""
                MERGE INTO sys_user(id,public_id,username,email,email_verified_at,password_hash,status,timezone,
                  login_failed_count,created_at,created_by,updated_at,updated_by,version)
                KEY(id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, 42L, "u-42", "student", "student@example.com", now, "test", "ACTIVE",
                "Asia/Shanghai", 0, now, 42L, now, 42L, 0);
        jdbc.update("""
                INSERT INTO learning_project(id,public_id,user_id,name,start_date,due_date,priority,status,
                  deliverable_json,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, 900L, "project-concurrent", 42L, "并发验收项目", LocalDate.now(),
                LocalDate.now().plusDays(10), "MEDIUM", "ACTIVE", "[]", now, 42L, now, 42L, 0);
        jdbc.update("""
                INSERT INTO milestone(id,public_id,project_id,name,sequence_no,due_date,weight,status,
                  acceptance_json,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, 901L, "milestone-concurrent", 900L, "提交成果", 1,
                LocalDate.now().plusDays(5), 1, "NOT_STARTED",
                "[{\"description\":\"提交可检查成果\"}]", now, 42L, now, 42L, 0);

        var input = new GoalProjectService.MilestoneCompletionInput(0, "成果链接",
                List.of(Map.of("index", 0, "confirmed", true, "evidence", "https://example.com/result")));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> completeAfterBarrier(input, ready, start));
            var second = executor.submit(() -> completeAfterBarrier(input, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo("COMPLETED");
            assertThat(second.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo("COMPLETED");
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_event
                WHERE aggregate_id='milestone-concurrent' AND event_type='MilestoneCompleted'
                """, Integer.class)).isEqualTo(1);
        verify(assessments, times(1)).recordProjectMilestoneEvidence(
                eq(42L), eq(900L), eq(901L), any(), any());
    }

    private MilestoneEntity completeAfterBarrier(GoalProjectService.MilestoneCompletionInput input,
                                                   CountDownLatch ready, CountDownLatch start) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(42L, "u-42", "student", "", Set.of("STUDENT"), Set.of()), null, List.of()));
        try {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return service.completeMilestone("milestone-concurrent", input);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
