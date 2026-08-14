package com.adaptivelearning.execution.api;

import com.adaptivelearning.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class M06ExecutionSecurityIntegrationTest {
    private static final long USER_A = 61_000L;
    private static final long USER_B = 62_000L;
    private static final long GOAL_A = 61_010L;
    private static final long GOAL_B = 62_010L;
    private static final long TASK_A = 61_020L;
    private static final long TASK_B = 62_020L;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        createTables();
        clearData();
        Instant now = Instant.now();
        insertUser(USER_A, "m06-security-a", now);
        insertUser(USER_B, "m06-security-b", now);
        insertGoal(GOAL_A, USER_A, "goal-a", now);
        insertGoal(GOAL_B, USER_B, "goal-b", now);
        insertTask(TASK_A, USER_A, GOAL_A, "task-a", "A 用户任务", now);
        insertTask(TASK_B, USER_B, GOAL_B, "task-b", "B 用户私有任务", now);
        jdbc.update("INSERT INTO task_dependency(predecessor_task_id,successor_task_id) VALUES(?,?)",
                TASK_B, TASK_A);
        jdbc.update("""
                INSERT INTO study_session(id,public_id,session_group_id,user_id,task_id,source,started_at,
                  pause_seconds,effective_seconds,status,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, 62_030L, "session-b", "group-b", USER_B, TASK_B, "AUTO", now.minusSeconds(60),
                0, 0, "RUNNING", now, USER_B, now, USER_B, 0);
        jdbc.update("""
                INSERT INTO study_note(id,public_id,user_id,task_id,current_version_no,title,created_at,created_by,
                  updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, 62_040L, "note-b", USER_B, TASK_B, 1, "B 私有笔记", now, USER_B, now, USER_B, 0);
        jdbc.update("INSERT INTO study_note_version(id,note_id,version_no,content_markdown,content_hash,created_at,created_by) VALUES(?,?,?,?,?,?,?)",
                62_041L, 62_040L, 1, "B 私有正文", "hash", now, USER_B);
        jdbc.update("""
                INSERT INTO learning_block(id,public_id,user_id,goal_id,task_id,sequence_no,title,objective,
                  direction_name,exploration_required,source_status,source_manifest_json,source_queries_json,
                  generation_status,exercises_json,test_json,pass_score,attempt_count,status,created_at,
                  created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, 62_050L, "block-b", USER_B, GOAL_B, TASK_B, 1, "B 私有知识块", "私有目标",
                "方向", false, "READY", "[]", "[]", "GENERATED", "[]", "[]", 80, 0,
                "READY", now, USER_B, now, USER_B, 0);
    }

    @Test
    void userCannotReadOrMutateAnotherUsersExecutionResources() throws Exception {
        mvc.perform(get("/api/v1/tasks/task-b").with(authentication(userA())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/tasks/task-b/start").with(authentication(userA()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"startTimer\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/tasks/task-b/completion").with(authentication(userA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":{\"learnedText\":\"伪造\"},\"acceptance\":null}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/tasks/task-b/cancellation").with(authentication(userA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmed\":true,\"reason\":\"伪造\"}"))
                .andExpect(status().isNotFound());

        for (String suffix : List.of("", "/pause", "/resume", "/stop")) {
            var request = suffix.isEmpty() ? get("/api/v1/study-sessions/session-b")
                    : post("/api/v1/study-sessions/session-b" + suffix);
            mvc.perform(request.with(authentication(userA()))).andExpect(status().isNotFound());
        }

        mvc.perform(get("/api/v1/tasks/task-b/note").with(authentication(userA())))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/tasks/task-b/note").with(authentication(userA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"伪造\",\"markdown\":\"伪造\",\"version\":1}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/tasks/task-b/learning-block").with(authentication(userA())))
                .andExpect(status().isNotFound());
    }

    @Test
    void activeSessionAndInvalidDependencyNeverLeakAnotherUser() throws Exception {
        mvc.perform(get("/api/v1/study-sessions/active").with(authentication(userA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        String body = mvc.perform(get("/api/v1/tasks/task-a").with(authentication(userA())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blockedReason").value("DEPENDENCY_DATA_INVALID"))
                .andExpect(jsonPath("$.data.prerequisites.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("task-b", "B 用户私有任务", "session-b", "note-b", "block-b");
    }

    private UsernamePasswordAuthenticationToken userA() {
        return new UsernamePasswordAuthenticationToken(
                new CurrentUser(USER_A, "m06-security-a", "m06-security-a", "",
                        Set.of("STUDENT"), Set.of()), null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
    }

    private void insertUser(long id, String name, Instant now) {
        jdbc.update("""
                MERGE INTO sys_user(id,public_id,username,email,email_verified_at,password_hash,status,timezone,
                  login_failed_count,created_at,created_by,updated_at,updated_by,version)
                KEY(id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, name, name, name + "@example.com", now, "test", "ACTIVE", "Asia/Shanghai",
                0, now, id, now, id, 0);
    }

    private void insertGoal(long id, long userId, String publicId, Instant now) {
        jdbc.update("""
                INSERT INTO learning_goal(id,public_id,user_id,name,status,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, id, publicId, userId, publicId, "ACTIVE", now, userId, now, userId, 0);
    }

    private void insertTask(long id, long userId, long goalId, String publicId, String title, Instant now) {
        jdbc.update("""
                INSERT INTO learning_task(id,public_id,user_id,goal_id,title,description,task_type,priority,
                  estimated_minutes,scheduled_start,due_at,locked_schedule,lifecycle_status,progress_percent,
                  reschedule_count,acceptance_json,created_at,created_by,updated_at,updated_by,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, publicId, userId, goalId, title, "私有描述", "LEARNING", "MEDIUM", 30,
                now, now.plusSeconds(3600), false, "IN_PROGRESS", BigDecimal.ZERO, 0, "[]",
                now, userId, now, userId, 0);
    }

    private void clearData() {
        for (String sql : List.of(
                "DELETE FROM study_session_pause WHERE session_id BETWEEN 61000 AND 62999",
                "DELETE FROM study_session WHERE user_id IN (" + USER_A + "," + USER_B + ")",
                "DELETE FROM study_note_version WHERE note_id BETWEEN 61000 AND 62999",
                "DELETE FROM study_note WHERE user_id IN (" + USER_A + "," + USER_B + ")",
                "DELETE FROM task_completion_summary WHERE user_id IN (" + USER_A + "," + USER_B + ")",
                "DELETE FROM learning_block WHERE user_id IN (" + USER_A + "," + USER_B + ")",
                "DELETE FROM task_dependency WHERE predecessor_task_id IN (" + TASK_A + "," + TASK_B + ") OR successor_task_id IN (" + TASK_A + "," + TASK_B + ")",
                "DELETE FROM learning_task WHERE user_id IN (" + USER_A + "," + USER_B + ")",
                "DELETE FROM learning_goal WHERE user_id IN (" + USER_A + "," + USER_B + ")",
                "DELETE FROM sys_user WHERE id IN (" + USER_A + "," + USER_B + ")")) {
            jdbc.update(sql);
        }
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS learning_goal(
                  id BIGINT PRIMARY KEY,public_id VARCHAR(64),user_id BIGINT,name VARCHAR(200),status VARCHAR(24),
                  created_at TIMESTAMP,created_by BIGINT,updated_at TIMESTAMP,updated_by BIGINT,version INT,
                  direction_id BIGINT,custom_direction VARCHAR(200),source_goal_id BIGINT,source_type VARCHAR(40),
                  profile_version_id BIGINT,recommendation_snapshot_json VARCHAR(8000),type VARCHAR(40),
                  description VARCHAR(2000),priority VARCHAR(20),start_date DATE,due_date DATE,
                  weekly_budget_minutes INT,success_criteria_json VARCHAR(4000),acceptance_snapshot_json VARCHAR(4000),
                  deleted_at TIMESTAMP)
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
                CREATE TABLE IF NOT EXISTS study_note(
                  id BIGINT PRIMARY KEY,public_id VARCHAR(64),user_id BIGINT,task_id BIGINT,current_version_no INT,
                  title VARCHAR(200),sync_document_id BIGINT,created_at TIMESTAMP,created_by BIGINT,
                  updated_at TIMESTAMP,updated_by BIGINT,version INT,deleted_at TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS study_note_version(id BIGINT PRIMARY KEY,note_id BIGINT,version_no INT,content_markdown VARCHAR(100000),content_hash VARCHAR(64),created_at TIMESTAMP,created_by BIGINT)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS task_completion_summary(
                  id BIGINT PRIMARY KEY,task_id BIGINT,user_id BIGINT,learned_text VARCHAR(3000),difficulty_text VARCHAR(3000),
                  quality_level INT,confidence_level INT,remaining_questions VARCHAR(3000),revision_no INT,created_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS learning_block(
                  id BIGINT PRIMARY KEY,public_id VARCHAR(64),user_id BIGINT,goal_id BIGINT,task_id BIGINT,
                  sequence_no INT,title VARCHAR(200),objective VARCHAR(1000),direction_name VARCHAR(200),
                  exploration_required BOOLEAN,source_status VARCHAR(24),source_manifest_json VARCHAR(8000),
                  source_queries_json VARCHAR(8000),generation_status VARCHAR(24),material_markdown VARCHAR(8000),
                  exercises_json VARCHAR(8000),test_json VARCHAR(8000),pass_score INT,latest_score INT,
                  attempt_count INT,status VARCHAR(24),created_at TIMESTAMP,created_by BIGINT,updated_at TIMESTAMP,
                  updated_by BIGINT,version INT,deleted_at TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_knowledge_source(task_id BIGINT,chunk_id BIGINT,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS knowledge_chunk(id BIGINT PRIMARY KEY,document_version_id BIGINT,chunk_no INT,text VARCHAR(4000),page_from INT,page_to INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS document_version(id BIGINT PRIMARY KEY,document_id BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS knowledge_document(id BIGINT PRIMARY KEY,public_id VARCHAR(64),display_name VARCHAR(200),deleted_at TIMESTAMP)");
    }
}
