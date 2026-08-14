package com.adaptivelearning.execution.application;

import com.adaptivelearning.goalproject.application.GoalProjectService;
import com.adaptivelearning.goalproject.domain.GoalStatus;
import com.adaptivelearning.goalproject.domain.ProjectStatus;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.security.CurrentUser;
import com.adaptivelearning.support.application.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.sql.init.mode=never",
        "app.redis.enabled=false",
        "app.scheduling.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=12"
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "m06.mysql.url", matches = ".+")
class M06SessionConcurrencyMySqlIntegrationTest {
    private static final long USER_ID = 9_060_001L;
    private static final long GOAL_ID = 9_060_100L;
    private static final long PROJECT_ID = 9_060_200L;
    private static final long MILESTONE_ID = 9_060_300L;

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("m06.mysql.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("m06.mysql.username", "learning"));
        registry.add("spring.datasource.password", () -> System.getProperty("m06.mysql.password", "change-me"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired StudySessionService sessions;
    @Autowired TaskService tasks;
    @Autowired GoalProjectService goals;
    @Autowired JdbcTemplate jdbc;
    @MockBean AuditService audit;

    @BeforeEach
    void setUp() {
        clearData();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO sys_user(id,public_id,username,email,email_verified_at,password_hash,status,timezone,
                  login_failed_count,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, USER_ID, "m06-user", "m06-user", "m06-user@example.com", now, "test", "ACTIVE",
                "Asia/Shanghai", 0, now, USER_ID, now, USER_ID, 0);
        jdbc.update("""
                INSERT INTO learning_goal(id,public_id,user_id,direction_id,source_type,name,type,description,priority,
                  start_date,due_date,weekly_budget_minutes,status,success_criteria_json,created_at,created_by,
                  updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, ?,?,?,?)
                """, GOAL_ID, "m06-goal", USER_ID, 10L, "CUSTOM", "M06 并发目标", "SKILL", "测试",
                "MEDIUM", LocalDate.now(), LocalDate.now().plusDays(30), 600, "ACTIVE", "[]",
                now, USER_ID, now, USER_ID, 0);
        jdbc.update("""
                INSERT INTO learning_project(id,public_id,user_id,name,description,start_date,due_date,priority,status,
                  deliverable_json,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, PROJECT_ID, "m06-project", USER_ID, "M06 项目", "测试",
                LocalDate.now(), LocalDate.now().plusDays(30), "MEDIUM", "ACTIVE", "[]",
                now, USER_ID, now, USER_ID, 0);
        jdbc.update("""
                INSERT INTO milestone(id,public_id,project_id,name,sequence_no,due_date,weight,status,acceptance_json,
                  created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, MILESTONE_ID, "m06-milestone", PROJECT_ID, "M06 里程碑", 1,
                LocalDate.now().plusDays(20), new BigDecimal("1.0"), "NOT_STARTED",
                "[{\"description\":\"完成并发验证\"}]", now, USER_ID, now, USER_ID, 0);
        authenticate();
    }

    @Test
    void projectPauseRacingSessionStartCannotLeaveIllegalRunningSession() throws Exception {
        insertTask(1L, "project-task", "IN_PROGRESS", PROJECT_ID, null);

        runConcurrent(
                () -> sessions.start("project-task"),
                () -> goals.transitionProject("m06-project", ProjectStatus.PAUSED, "并发暂停", false));

        assertThat(jdbc.queryForObject("SELECT status FROM learning_project WHERE id=?", String.class, PROJECT_ID))
                .isEqualTo("PAUSED");
        assertThat(runningCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM learning_task WHERE id=1", String.class))
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    void goalPauseRacingSessionStartCannotLeaveIllegalRunningSession() throws Exception {
        insertTask(12L, "goal-task", "IN_PROGRESS", null, null);

        runConcurrent(
                () -> sessions.start("goal-task"),
                () -> goals.transitionGoal("m06-goal", GoalStatus.PAUSED, "并发暂停", false));

        assertThat(jdbc.queryForObject("SELECT status FROM learning_goal WHERE id=?", String.class, GOAL_ID))
                .isEqualTo("PAUSED");
        assertThat(runningCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM learning_task WHERE id=12", String.class))
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    void milestoneCompletionRacingSessionStartCannotLeaveIllegalRunningSession() throws Exception {
        insertTask(2L, "milestone-task", "IN_PROGRESS", PROJECT_ID, MILESTONE_ID);
        GoalProjectService.MilestoneCompletionInput input = new GoalProjectService.MilestoneCompletionInput(
                0, "完成并发验证", List.of(java.util.Map.of("index", 0, "confirmed", true)));

        runConcurrent(
                () -> sessions.start("milestone-task"),
                () -> goals.completeMilestone("m06-milestone", input));

        assertThat(jdbc.queryForObject("SELECT status FROM milestone WHERE id=?", String.class, MILESTONE_ID))
                .isEqualTo("COMPLETED");
        assertThat(runningCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM learning_task WHERE id=2", String.class))
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    void completedParentStopsSessionButDoesNotInventTaskCancellation() {
        insertTask(13L, "completed-project-task", "IN_PROGRESS", PROJECT_ID, null);
        sessions.start("completed-project-task");

        goals.transitionProject("m06-project", ProjectStatus.COMPLETED, "项目验收完成", true);

        assertThat(runningCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM learning_task WHERE id=13", String.class))
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    void twoPausedTasksCannotBothResumeForOneUser() throws Exception {
        insertTask(3L, "resume-a", "IN_PROGRESS", null, null);
        insertTask(4L, "resume-b", "IN_PROGRESS", null, null);
        insertPausedSession(31L, "session-a", 3L);
        insertPausedSession(41L, "session-b", 4L);

        List<Object> outcomes = runConcurrent(() -> sessions.resume("session-a"),
                () -> sessions.resume("session-b"));

        assertThat(outcomes.stream().filter(value -> !(value instanceof Throwable)).count()).isEqualTo(1);
        assertThat(runningCount()).isEqualTo(1);
    }

    @Test
    void anotherTaskCannotStartWhileOneSessionIsRunning() {
        insertTask(5L, "running-a", "IN_PROGRESS", null, null);
        insertTask(6L, "running-b", "IN_PROGRESS", null, null);
        sessions.start("running-a");

        assertThatThrownBy(() -> sessions.start("running-b"))
                .isInstanceOf(BusinessException.class);
        assertThat(runningCount()).isEqualTo(1);
    }

    @Test
    void canceledPredecessorRaceNeverUnlocksSuccessor() throws Exception {
        insertTask(7L, "pred-cancel", "IN_PROGRESS", null, null);
        insertTask(8L, "succ-cancel", "NOT_STARTED", null, null);
        jdbc.update("INSERT INTO task_dependency(predecessor_task_id,successor_task_id) VALUES(?,?)", 7L, 8L);

        runConcurrent(
                () -> tasks.transition("pred-cancel", "CANCELED", "取消前置", null, true),
                () -> tasks.startTask("succ-cancel", true));

        assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM learning_task WHERE id=8", String.class))
                .isNotEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM study_session WHERE task_id=8 AND status='RUNNING'",
                Integer.class)).isZero();
    }

    @Test
    void controlledCompletionReversalRaceKeepsDependencyConsistent() throws Exception {
        insertTask(9L, "pred-reverse", "COMPLETED", null, null);
        jdbc.update("UPDATE learning_task SET completed_at=? WHERE id=9", Instant.now());
        insertTask(10L, "succ-reverse", "NOT_STARTED", null, null);
        jdbc.update("INSERT INTO task_dependency(predecessor_task_id,successor_task_id) VALUES(?,?)", 9L, 10L);

        runConcurrent(
                () -> tasks.transition("pred-reverse", "PAUSED", "纠正完成状态", null, true),
                () -> tasks.startTask("succ-reverse", true));

        String predecessor = jdbc.queryForObject("SELECT lifecycle_status FROM learning_task WHERE id=9", String.class);
        String successor = jdbc.queryForObject("SELECT lifecycle_status FROM learning_task WHERE id=10", String.class);
        assertThat(!"IN_PROGRESS".equals(successor) || "COMPLETED".equals(predecessor)).isTrue();
    }

    @Test
    void completeAndCancelRaceProducesOneFormalStatusChange() throws Exception {
        insertTask(11L, "terminal-race", "IN_PROGRESS", null, null);

        List<Object> outcomes = runConcurrent(
                () -> tasks.transition("terminal-race", "COMPLETED", "完成",
                        new TaskService.CompletionInput("并发完成总结", null, null, null, null), false),
                () -> tasks.transition("terminal-race", "CANCELED", "取消", null, true));

        assertThat(outcomes.stream().filter(value -> !(value instanceof Throwable)).count())
                .as("complete/cancel outcomes: %s", outcomes.stream().map(this::describeOutcome).toList())
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_status_history WHERE task_id=11", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id='terminal-race' AND event_type='TaskStatusChanged'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void twoAcceptanceCompletionsProduceExactlyOneFormalResult() throws Exception {
        insertTask(14L, "acceptance-complete-race", "IN_PROGRESS", null, null);
        jdbc.update("UPDATE learning_task SET acceptance_json=? WHERE id=?", "[\"确认并发验收\"]", 14L);
        var acceptance = tasks.get("acceptance-complete-race").acceptance();
        var confirmation = new TaskAcceptancePolicy.Confirmation(
                acceptance.snapshotHash(), List.of(0));

        List<Object> outcomes = runConcurrent(
                () -> tasks.transition("acceptance-complete-race", "COMPLETED", "完成",
                        new TaskService.CompletionInput("线程一总结", null, null, null, null),
                        true, confirmation),
                () -> tasks.transition("acceptance-complete-race", "COMPLETED", "完成",
                        new TaskService.CompletionInput("线程二总结", null, null, null, null),
                        true, confirmation));

        assertThat(outcomes.stream().filter(value -> !(value instanceof Throwable)).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_completion_summary WHERE task_id=14",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_status_history WHERE task_id=14",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id='acceptance-complete-race'",
                Integer.class)).isEqualTo(1);
    }

    private void insertTask(long id, String publicId, String status, Long projectId, Long milestoneId) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO learning_task(id,public_id,user_id,goal_id,project_id,milestone_id,title,description,
                  task_type,priority,estimated_minutes,scheduled_start,due_at,locked_schedule,lifecycle_status,
                  progress_percent,reschedule_count,acceptance_json,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, publicId, USER_ID, GOAL_ID, projectId, milestoneId, publicId, "测试", "LEARNING",
                "MEDIUM", 30, now, now.plusSeconds(3600), false, status,
                "COMPLETED".equals(status) ? new BigDecimal("100") : BigDecimal.ZERO,
                0, "[]", now, USER_ID, now, USER_ID, 0);
    }

    private void insertPausedSession(long id, String publicId, long taskId) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO study_session(id,public_id,session_group_id,user_id,task_id,source,started_at,
                  pause_seconds,effective_seconds,status,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, publicId, "group-" + id, USER_ID, taskId, "AUTO", now.minusSeconds(120),
                0, 0, "PAUSED", now, USER_ID, now, USER_ID, 0);
        jdbc.update("INSERT INTO study_session_pause(id,session_id,paused_at,seconds) VALUES(?,?,?,?)",
                id + 1000, id, now.minusSeconds(30), 0);
    }

    private List<Object> runConcurrent(ConcurrentCall first, ConcurrentCall second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> invoke(first, ready, start));
            var b = executor.submit(() -> invoke(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private Object invoke(ConcurrentCall call, CountDownLatch ready, CountDownLatch start) throws Exception {
        authenticate();
        try {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return call.run();
        } catch (Throwable failure) {
            return failure;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private int runningCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM study_session WHERE user_id=? AND status='RUNNING'",
                Integer.class, USER_ID);
    }

    private String describeOutcome(Object value) {
        return value instanceof Throwable failure
                ? failure.getClass().getSimpleName() + ":" + failure.getMessage()
                : value.getClass().getSimpleName();
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(USER_ID, "m06-user", "m06-user", "", Set.of("STUDENT"), Set.of()),
                null, List.of()));
    }

    private void clearData() {
        for (String sql : new ArrayList<>(List.of(
                "DELETE FROM study_session_pause WHERE session_id BETWEEN 1 AND 10000",
                "DELETE FROM study_session WHERE user_id=" + USER_ID,
                "DELETE FROM task_status_history WHERE task_id BETWEEN 1 AND 10000",
                "DELETE FROM task_dependency WHERE predecessor_task_id BETWEEN 1 AND 10000 OR successor_task_id BETWEEN 1 AND 10000",
                "DELETE FROM task_completion_summary WHERE user_id=" + USER_ID,
                "DELETE FROM learning_block WHERE user_id=" + USER_ID,
                "DELETE FROM learning_task WHERE user_id=" + USER_ID,
                "DELETE FROM outbox_event WHERE aggregate_id LIKE 'm06-%' OR aggregate_id LIKE '%-race' OR aggregate_id LIKE '%-cancel' OR aggregate_id LIKE '%-reverse'",
                "DELETE FROM milestone WHERE project_id=" + PROJECT_ID,
                "DELETE FROM learning_project WHERE user_id=" + USER_ID,
                "DELETE FROM learning_goal WHERE user_id=" + USER_ID,
                "DELETE FROM sys_user WHERE id=" + USER_ID))) {
            jdbc.update(sql);
        }
    }

    @FunctionalInterface
    private interface ConcurrentCall { Object run() throws Exception; }
}
