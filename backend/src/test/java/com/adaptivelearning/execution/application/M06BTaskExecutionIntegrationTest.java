package com.adaptivelearning.execution.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@ActiveProfiles("test")
class M06BTaskExecutionIntegrationTest {
    private static final long USER_ID = 60_600L;
    private static final long GOAL_ID = 60_610L;
    private static final long PROJECT_ID = 60_620L;
    private static final long MILESTONE_ID = 60_630L;

    @Autowired TaskService tasks;
    @Autowired StudySessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @MockBean AuditService audit;

    @BeforeEach
    void setUp() {
        createTables();
        clearData();
        reset(audit);
        Instant now = Instant.now();
        jdbc.update("""
                MERGE INTO sys_user(id,public_id,username,email,email_verified_at,password_hash,status,timezone,
                  login_failed_count,created_at,created_by,updated_at,updated_by,version)
                KEY(id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, USER_ID, "m06b-user", "m06b-user", "m06b@example.com", now, "test", "ACTIVE",
                "Asia/Shanghai", 0, now, USER_ID, now, USER_ID, 0);
        jdbc.update("""
                INSERT INTO learning_goal(id,public_id,user_id,name,status,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, GOAL_ID, "m06b-goal", USER_ID, "M06-B 目标", "ACTIVE", now, USER_ID, now, USER_ID, 0);
        jdbc.update("""
                INSERT INTO learning_project(id,public_id,user_id,name,start_date,due_date,priority,status,
                  deliverable_json,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, PROJECT_ID, "m06b-project", USER_ID, "M06-B 项目", LocalDate.now(),
                LocalDate.now().plusDays(10), "MEDIUM", "ACTIVE", "[]", now, USER_ID, now, USER_ID, 0);
        jdbc.update("""
                INSERT INTO milestone(id,public_id,project_id,name,sequence_no,due_date,weight,status,
                  acceptance_json,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, MILESTONE_ID, "m06b-milestone", PROJECT_ID, "M06-B 里程碑", 1,
                LocalDate.now().plusDays(5), BigDecimal.ONE, "NOT_STARTED", "[]",
                now, USER_ID, now, USER_ID, 0);
        authenticate();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void completionRequiresCurrentAcceptanceAndFailureHasNoSideEffects() {
        insertTask(60_601L, "acceptance-task", "IN_PROGRESS", 70_001L, "[\"完成示例\",\"通过练习\"]");
        insertGeneratedBlock(60_901L, 60_601L, "acceptance-block");
        var snapshot = tasks.get("acceptance-task").acceptance();
        var summary = new TaskService.CompletionInput("  掌握了核心步骤  ", null, 4, 3, null);

        assertThatThrownBy(() -> tasks.transition("acceptance-task", "COMPLETED", "完成",
                summary, true, new TaskAcceptancePolicy.Confirmation(snapshot.snapshotHash(), List.of(0))))
                .isInstanceOf(BusinessException.class);
        assertNoCompletionSideEffects(60_601L, "acceptance-task");
        assertThat(jdbc.queryForObject("SELECT status FROM learning_block WHERE task_id=?",
                String.class, 60_601L)).isEqualTo("LEARNING");

        assertThatThrownBy(() -> tasks.transition("acceptance-task", "COMPLETED", "完成",
                summary, true, new TaskAcceptancePolicy.Confirmation("forged", List.of(0, 1))))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(ErrorCode.TASK_ACCEPTANCE_STALE));
        assertNoCompletionSideEffects(60_601L, "acceptance-task");
        verifyNoInteractions(audit);

        TaskService.TaskView completed = tasks.transition("acceptance-task", "COMPLETED", "完成",
                summary, true, new TaskAcceptancePolicy.Confirmation(snapshot.snapshotHash(), List.of(0, 1)));

        assertThat(completed.task().getLifecycleStatus()).isEqualTo("COMPLETED");
        assertThat(completed.completionSummary().learnedText()).isEqualTo("掌握了核心步骤");
        assertThat(completed.completionSummary().revisionNo()).isEqualTo(1);
        TaskService.TaskView detail = tasks.get("acceptance-task");
        assertThat(detail.project()).isEqualTo(new TaskService.ParentContextView(
                "m06b-project", "M06-B 项目", "ACTIVE"));
        assertThat(detail.milestone()).isEqualTo(new TaskService.ParentContextView(
                "m06b-milestone", "M06-B 里程碑", "NOT_STARTED"));
        assertThat(detail.completionSummary().learnedText()).isEqualTo("掌握了核心步骤");
        assertThat(detail.acceptance().criteria()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT status FROM learning_block WHERE task_id=?",
                String.class, 60_601L)).isEqualTo("ASSESSMENT_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM milestone WHERE id=?", String.class, MILESTONE_ID))
                .isEqualTo("NOT_STARTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_status_history WHERE task_id=?",
                Integer.class, 60_601L)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",
                Integer.class, "acceptance-task")).isEqualTo(1);
    }

    @Test
    void acceptanceChangeInvalidatesPreviouslyLoadedSnapshot() {
        insertTask(60_612L, "changed-acceptance", "IN_PROGRESS", 70_012L, "[\"旧条件\"]");
        var oldSnapshot = tasks.get("changed-acceptance").acceptance();
        jdbc.update("UPDATE learning_task SET acceptance_json=? WHERE id=?", "[\"新条件\",\"补充条件\"]", 60_612L);

        assertThatThrownBy(() -> tasks.transition("changed-acceptance", "COMPLETED", "完成",
                new TaskService.CompletionInput("总结", null, null, null, null), true,
                new TaskAcceptancePolicy.Confirmation(oldSnapshot.snapshotHash(), List.of(0))))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(ErrorCode.TASK_ACCEPTANCE_STALE));
        assertNoCompletionSideEffects(60_612L, "changed-acceptance");
    }

    @Test
    void legacyAcceptanceStillRequiresSummaryAndDoesNotRequireSession() {
        insertTask(60_602L, "legacy-task", "IN_PROGRESS", null, "[]");

        assertThatThrownBy(() -> tasks.transition("legacy-task", "COMPLETED", "完成",
                new TaskService.CompletionInput("   ", null, null, null, null), true, null))
                .isInstanceOf(BusinessException.class);
        assertNoCompletionSideEffects(60_602L, "legacy-task");

        TaskService.TaskView completed = tasks.transition("legacy-task", "COMPLETED", "完成",
                new TaskService.CompletionInput("无计时也可以提交真实总结", null, null, null, null), true, null);

        assertThat(completed.task().getLifecycleStatus()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM study_session WHERE task_id=?",
                Integer.class, 60_602L)).isZero();
    }

    @Test
    void controlledReversalThenCompletionIncrementsSummaryRevision() {
        insertTask(60_603L, "revision-task", "IN_PROGRESS", null, "[]");
        tasks.transition("revision-task", "COMPLETED", "首次完成",
                new TaskService.CompletionInput("第一版", null, null, null, null), true, null);
        tasks.transition("revision-task", "PAUSED", "需要补充", null, true, null);

        TaskService.TaskView second = tasks.transition("revision-task", "COMPLETED", "再次完成",
                new TaskService.CompletionInput("第二版", null, 5, 4, null), true, null);

        assertThat(second.completionSummary().learnedText()).isEqualTo("第二版");
        assertThat(second.completionSummary().revisionNo()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_completion_summary WHERE task_id=?",
                Integer.class, 60_603L)).isEqualTo(1);
    }

    @Test
    void formalAndTerminalTasksRejectPatchWhileLegacyTaskAllowsLimitedFields() {
        insertTask(60_604L, "formal-task", "IN_PROGRESS", 70_004L, "[]");
        insertTask(60_605L, "terminal-task", "COMPLETED", null, "[]");
        insertTask(60_606L, "legacy-editable", "IN_PROGRESS", null, "[]");

        assertThatThrownBy(() -> tasks.update("formal-task", new TaskService.UpdateInput(
                "绕过计划修改", null, null, null, null, null, 0)))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(ErrorCode.PLAN_CONFIRMATION_REQUIRED));
        assertThatThrownBy(() -> tasks.update("terminal-task", new TaskService.UpdateInput(
                "终态修改", null, null, null, null, null, 0)))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(ErrorCode.STATE_TRANSITION_INVALID));

        TaskService.TaskView updated = tasks.update("legacy-editable", new TaskService.UpdateInput(
                "兼容任务新标题", null, "HIGH", 45, null, null, 0));
        assertThat(updated.task().getTitle()).isEqualTo("兼容任务新标题");
        assertThat(updated.task().getPriority()).isEqualTo("HIGH");
    }

    @Test
    void activeSessionsAreOwnedAndEffectiveSecondsComeFromServer() {
        insertTask(60_607L, "running-task", "IN_PROGRESS", 70_007L, "[]");
        insertTask(60_608L, "paused-task", "IN_PROGRESS", 70_008L, "[]");
        Instant now = Instant.now();
        insertSession(60_701L, "running-session", 60_607L, "RUNNING", now.minusSeconds(120), 20);
        insertSession(60_702L, "paused-session", 60_608L, "PAUSED", now.minusSeconds(300), 40);
        jdbc.update("INSERT INTO study_session_pause(id,session_id,paused_at,seconds) VALUES(?,?,?,?)",
                60_801L, 60_702L, now.minusSeconds(60), 0);

        List<StudySessionService.ActiveSessionView> active = sessions.active();

        assertThat(active).extracting(StudySessionService.ActiveSessionView::taskId)
                .containsExactly("running-task", "paused-task");
        assertThat(active.get(0).effectiveSeconds()).isBetween(99L, 105L);
        assertThat(active.get(1).effectiveSeconds()).isBetween(198L, 202L);
        assertThat(active.get(1).pausedAt()).isNotNull();
    }

    @Test
    void multipleRunningSessionsFailClosedAndAreAudited() {
        insertTask(60_609L, "running-one", "IN_PROGRESS", 70_009L, "[]");
        insertTask(60_611L, "running-two", "IN_PROGRESS", 70_011L, "[]");
        Instant now = Instant.now();
        insertSession(60_703L, "running-one-session", 60_609L, "RUNNING", now.minusSeconds(60), 0);
        insertSession(60_704L, "running-two-session", 60_611L, "RUNNING", now.minusSeconds(30), 0);

        assertThatThrownBy(() -> sessions.active())
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(ErrorCode.DEPENDENCY_DATA_INVALID));
        verify(audit).record("STUDY_SESSION_DATA_INTEGRITY", "USER", String.valueOf(USER_ID),
                null, "ACTIVE_SESSION_DATA_INVALID", "REJECTED");
    }

    private void assertNoCompletionSideEffects(long taskId, String publicId) {
        assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM learning_task WHERE id=?",
                String.class, taskId)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_completion_summary WHERE task_id=?",
                Integer.class, taskId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_status_history WHERE task_id=?",
                Integer.class, taskId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",
                Integer.class, publicId)).isZero();
    }

    private void insertTask(long id, String publicId, String status, Long originVersion, String acceptance) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO learning_task(id,public_id,user_id,goal_id,project_id,milestone_id,
                  origin_plan_version_id,title,description,task_type,priority,estimated_minutes,
                  scheduled_start,due_at,locked_schedule,lifecycle_status,progress_percent,completed_at,
                  reschedule_count,acceptance_json,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, publicId, USER_ID, GOAL_ID, PROJECT_ID, MILESTONE_ID, originVersion,
                publicId, "测试任务", "LEARNING", "MEDIUM", 30, now, now.plusSeconds(3600), false,
                status, "COMPLETED".equals(status) ? new BigDecimal("100") : BigDecimal.ZERO,
                "COMPLETED".equals(status) ? now : null, 0, acceptance, now, USER_ID, now, USER_ID, 0);
    }

    private void insertSession(long id, String publicId, long taskId, String status,
                               Instant startedAt, long pauseSeconds) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO study_session(id,public_id,session_group_id,user_id,task_id,source,started_at,
                  pause_seconds,effective_seconds,status,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, publicId, "group-" + id, USER_ID, taskId, "AUTO", startedAt,
                pauseSeconds, 0, status, now, USER_ID, now, USER_ID, 0);
    }

    private void insertGeneratedBlock(long id, long taskId, String publicId) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO learning_block(id,public_id,user_id,goal_id,task_id,sequence_no,title,objective,
                  exploration_required,source_status,generation_status,status,pass_score,attempt_count,
                  updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, publicId, USER_ID, GOAL_ID, taskId, 1, "知识块", "完成知识块",
                false, "READY", "GENERATED", "LEARNING", 60, 0, now, USER_ID, 0);
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(USER_ID, "m06b-user", "m06b-user", "", Set.of("STUDENT"), Set.of()),
                null, List.of()));
    }

    private void clearData() {
        for (String sql : List.of(
                "DELETE FROM study_session_pause WHERE session_id BETWEEN 60700 AND 60799",
                "DELETE FROM study_session WHERE user_id=" + USER_ID,
                "DELETE FROM task_status_history WHERE task_id BETWEEN 60600 AND 60699",
                "DELETE FROM task_completion_summary WHERE user_id=" + USER_ID,
                "DELETE FROM task_dependency WHERE predecessor_task_id BETWEEN 60600 AND 60699 OR successor_task_id BETWEEN 60600 AND 60699",
                "DELETE FROM learning_block WHERE user_id=" + USER_ID,
                "DELETE FROM learning_task WHERE user_id=" + USER_ID,
                "DELETE FROM outbox_event WHERE aggregate_id LIKE 'acceptance-%' OR aggregate_id LIKE '%-task' OR aggregate_id LIKE 'legacy-%' OR aggregate_id LIKE 'revision-%'",
                "DELETE FROM milestone WHERE id=" + MILESTONE_ID,
                "DELETE FROM learning_project WHERE id=" + PROJECT_ID,
                "DELETE FROM learning_goal WHERE id=" + GOAL_ID,
                "DELETE FROM sys_user WHERE id=" + USER_ID)) {
            jdbc.update(sql);
        }
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS learning_goal(
                  id BIGINT PRIMARY KEY,public_id VARCHAR(64),user_id BIGINT,direction_id BIGINT,
                  custom_direction VARCHAR(200),source_goal_id BIGINT,source_type VARCHAR(40),profile_version_id BIGINT,
                  recommendation_snapshot_json VARCHAR(8000),name VARCHAR(200),type VARCHAR(40),description VARCHAR(2000),
                  priority VARCHAR(20),start_date DATE,due_date DATE,weekly_budget_minutes INT,status VARCHAR(24),
                  success_criteria_json VARCHAR(4000),acceptance_snapshot_json VARCHAR(4000),created_at TIMESTAMP,
                  created_by BIGINT,updated_at TIMESTAMP,updated_by BIGINT,version INT,deleted_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS learning_task(
                  id BIGINT PRIMARY KEY,public_id VARCHAR(64),user_id BIGINT,goal_id BIGINT,project_id BIGINT,
                  milestone_id BIGINT,origin_plan_version_id BIGINT,learning_block_id BIGINT,title VARCHAR(200),
                  description VARCHAR(2000),task_type VARCHAR(40),priority VARCHAR(20),estimated_minutes INT,
                  scheduled_start TIMESTAMP,due_at TIMESTAMP,locked_schedule BOOLEAN,lifecycle_status VARCHAR(24),
                  progress_percent DECIMAL(5,2),completed_at TIMESTAMP,reschedule_count INT,acceptance_json VARCHAR(4000),
                  created_at TIMESTAMP,created_by BIGINT,updated_at TIMESTAMP,updated_by BIGINT,version INT,deleted_at TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_dependency(predecessor_task_id BIGINT,successor_task_id BIGINT,PRIMARY KEY(predecessor_task_id,successor_task_id))");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS study_session(
                  id BIGINT PRIMARY KEY,public_id VARCHAR(64),session_group_id VARCHAR(64),user_id BIGINT,task_id BIGINT,
                  source VARCHAR(24),started_at TIMESTAMP,ended_at TIMESTAMP,pause_seconds BIGINT,effective_seconds BIGINT,
                  status VARCHAR(24),manual_reason VARCHAR(1000),created_at TIMESTAMP,created_by BIGINT,
                  updated_at TIMESTAMP,updated_by BIGINT,version INT,deleted_at TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS study_session_pause(id BIGINT PRIMARY KEY,session_id BIGINT,paused_at TIMESTAMP,resumed_at TIMESTAMP,seconds BIGINT)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS task_completion_summary(
                  id BIGINT PRIMARY KEY,task_id BIGINT,user_id BIGINT,learned_text VARCHAR(3000),difficulty_text VARCHAR(3000),
                  quality_level INT,confidence_level INT,remaining_questions VARCHAR(3000),revision_no INT,created_at TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_status_history(id BIGINT PRIMARY KEY,task_id BIGINT,from_status VARCHAR(24),to_status VARCHAR(24),reason VARCHAR(1000),event_at TIMESTAMP,operator_type VARCHAR(24),correlation_id VARCHAR(100))");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS learning_block(
                  id BIGINT PRIMARY KEY,public_id VARCHAR(64),user_id BIGINT,goal_id BIGINT,task_id BIGINT,
                  sequence_no INT,title VARCHAR(200),objective VARCHAR(1000),exploration_required BOOLEAN,
                  source_status VARCHAR(24),generation_status VARCHAR(24),status VARCHAR(24),pass_score INT,
                  latest_score INT,attempt_count INT,updated_at TIMESTAMP,updated_by BIGINT,version INT,deleted_at TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_knowledge_source(task_id BIGINT,chunk_id BIGINT,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS knowledge_chunk(id BIGINT PRIMARY KEY,document_version_id BIGINT,chunk_no INT,text VARCHAR(4000),page_from INT,page_to INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS document_version(id BIGINT PRIMARY KEY,document_id BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS knowledge_document(id BIGINT PRIMARY KEY,public_id VARCHAR(64),display_name VARCHAR(200),deleted_at TIMESTAMP)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS study_note(
                  id BIGINT PRIMARY KEY,public_id VARCHAR(64),user_id BIGINT,task_id BIGINT,current_version_no INT,
                  title VARCHAR(200),sync_document_id BIGINT,created_at TIMESTAMP,created_by BIGINT,
                  updated_at TIMESTAMP,updated_by BIGINT,version INT,deleted_at TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS study_note_version(id BIGINT PRIMARY KEY,note_id BIGINT,version_no INT,content_markdown VARCHAR(100000),content_hash VARCHAR(64),created_at TIMESTAMP,created_by BIGINT)");
    }
}
