package com.adaptivelearning.execution.application;

import com.adaptivelearning.shared.security.CurrentUser;
import com.adaptivelearning.support.application.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class M06CancellationTransactionIntegrationTest {
    @Autowired TaskCancellationService cancellations;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean AuditService audit;

    @BeforeEach
    void setUp() {
        createTables();
        jdbc.update("DELETE FROM task_status_history");
        jdbc.update("DELETE FROM study_session_pause");
        jdbc.update("DELETE FROM study_session");
        jdbc.update("DELETE FROM learning_task");
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id='task-600'");
        Instant now = Instant.now();
        jdbc.update("""
                MERGE INTO sys_user(id,public_id,username,email,email_verified_at,password_hash,status,timezone,
                  login_failed_count,created_at,created_by,updated_at,updated_by,version)
                KEY(id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, 42L, "m06-user", "m06-user", "m06-user@example.com", now, "test", "ACTIVE",
                "Asia/Shanghai", 0, now, 42L, now, 42L, 0);
        jdbc.update("""
                INSERT INTO learning_task(id,public_id,user_id,goal_id,title,task_type,priority,estimated_minutes,
                  scheduled_start,due_at,locked_schedule,lifecycle_status,progress_percent,reschedule_count,
                  acceptance_json,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, 600L, "task-600", 42L, 700L, "待取消任务", "LEARNING", "MEDIUM", 30,
                now, now.plusSeconds(3600), false, "NOT_STARTED", BigDecimal.ZERO, 0, "[]",
                now, 42L, now, 42L, 0);
        authenticate();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void m05PublicationRollbackAlsoRollsBackTaskCancellation() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            cancellations.cancelForPlanPublication(600L, 42L, "测试发布取消");
            throw new IllegalStateException("simulate publication failure after cancellation");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM learning_task WHERE id=600",
                String.class)).isEqualTo("NOT_STARTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_status_history WHERE task_id=600",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id='task-600'",
                Integer.class)).isZero();
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(42L, "m06-user", "m06-user", "", Set.of("STUDENT"), Set.of()),
                null, List.of()));
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS learning_task(
                  id BIGINT PRIMARY KEY,public_id VARCHAR(64),user_id BIGINT,goal_id BIGINT,project_id BIGINT,
                  milestone_id BIGINT,origin_plan_version_id BIGINT,learning_block_id BIGINT,title VARCHAR(200),
                  description VARCHAR(2000),task_type VARCHAR(40),priority VARCHAR(20),estimated_minutes INT,
                  scheduled_start TIMESTAMP,due_at TIMESTAMP,locked_schedule BOOLEAN,lifecycle_status VARCHAR(24),
                  progress_percent DECIMAL(5,2),completed_at TIMESTAMP,reschedule_count INT,acceptance_json VARCHAR(4000),
                  created_at TIMESTAMP,created_by BIGINT,updated_at TIMESTAMP,updated_by BIGINT,version INT,deleted_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS task_status_history(
                  id BIGINT PRIMARY KEY,task_id BIGINT,from_status VARCHAR(24),to_status VARCHAR(24),
                  reason VARCHAR(1000),event_at TIMESTAMP,operator_type VARCHAR(24),correlation_id VARCHAR(100))
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS study_session(
                  id BIGINT PRIMARY KEY,public_id VARCHAR(64),session_group_id VARCHAR(64),user_id BIGINT,task_id BIGINT,
                  source VARCHAR(24),started_at TIMESTAMP,ended_at TIMESTAMP,pause_seconds BIGINT,effective_seconds BIGINT,
                  status VARCHAR(24),manual_reason VARCHAR(1000),created_at TIMESTAMP,created_by BIGINT,
                  updated_at TIMESTAMP,updated_by BIGINT,version INT,deleted_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS study_session_pause(
                  id BIGINT PRIMARY KEY,session_id BIGINT,paused_at TIMESTAMP,resumed_at TIMESTAMP,seconds BIGINT)
                """);
    }
}
