package com.adaptivelearning.profile.api;

import com.adaptivelearning.shared.ai.AiModelClient;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.support.application.EmailVerificationPurpose;
import com.adaptivelearning.support.application.VerificationMailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileGenerationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockBean VerificationMailService mailService;
    @MockBean AiModelClient modelClient;
    @MockBean PythonAiServiceClient pythonAi;
    @MockBean(name = "aiBackgroundExecutor") Executor generationExecutor;

    private final Queue<Runnable> queued = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void captureGenerationWork() {
        queued.clear();
        doAnswer(invocation -> {
            queued.add(invocation.getArgument(0));
            return null;
        }).when(generationExecutor).execute(any(Runnable.class));
    }

    @Test
    void formalVersionUsesOnlyDeterministicRulesAndBrowserSafeIds() throws Exception {
        ProfileFixture fixture = createProfile("profile_rules", "profile-rules@example.com");

        runNextGeneration();

        JsonNode versions = versions(fixture.token());
        JsonNode latest = versions.path(0);
        JsonNode snapshot = snapshot(latest);
        assertThat(latest.path("id").isTextual()).isTrue();
        assertThat(latest.path("profileId").isTextual()).isTrue();
        assertThat(latest.path("createdBy").isTextual()).isTrue();
        assertThat(latest.path("confidence").decimalValue()).isEqualByComparingTo("0.10");
        assertThat(snapshot.path("recommendedDifficulty").asInt()).isEqualTo(1);
        assertThat(snapshot.path("dailyRecommendedTasks").asInt()).isEqualTo(2);
        assertThat(snapshot.path("riskNotices").path(0).asText()).isEqualTo("尚无诊断或自评证据");
        assertThat(snapshot.path("source").path("selfAssessmentCount").asInt()).isZero();
        verifyNoInteractions(pythonAi);
        verifyNoInteractions(modelClient);
    }

    @Test
    void staleContextCannotCreateVersionOrRestoreGeneratedStatus() throws Exception {
        ProfileFixture fixture = createProfile("profile_stale", "profile-stale@example.com");
        runNextGeneration();
        assertThat(versionCount(fixture.userId())).isEqualTo(1);

        JsonNode job = startGeneration(fixture.token());
        long evidenceId = 9_007_199_254_740_991L;
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO self_assessment(id,user_id,knowledge_point_id,level,assessed_at,last_studied_at,note,
                  created_at,created_by,updated_at,updated_by,version,deleted_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,NULL)
                """, evidenceId, fixture.userId(), 9001L, 2, now, null, "direct concurrent evidence",
                now, fixture.userId(), now, fixture.userId(), 0);
        jdbc.update("UPDATE user_profile SET profile_status='DRAFT' WHERE user_id=?", fixture.userId());

        runNextGeneration();

        JsonNode completed = job(fixture.token(), job.path("publicId").asText());
        assertThat(completed.path("status").asText()).isEqualTo("FAILED");
        assertThat(completed.path("errorCode").asText()).isEqualTo("PROFILE_CONTEXT_STALE");
        assertThat(versionCount(fixture.userId())).isEqualTo(1);
        assertThat(profileStatus(fixture.userId())).isEqualTo("DRAFT");
    }

    @Test
    void selfAssessmentAtomicallyInvalidatesJobAndExplicitRegenerationUsesLatestEvidence() throws Exception {
        ProfileFixture fixture = createProfile("profile_evidence", "profile-evidence@example.com");
        runNextGeneration();
        insertKnowledgePoint();
        JsonNode oldJob = startGeneration(fixture.token());

        addSelfAssessment(fixture.token(), 9001L, 2);
        addSelfAssessment(fixture.token(), 9001L, 4);

        assertThat(profileStatus(fixture.userId())).isEqualTo("DRAFT");
        assertThat(job(fixture.token(), oldJob.path("publicId").asText()).path("errorCode").asText())
                .isEqualTo("PROFILE_CONTEXT_STALE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM self_assessment WHERE user_id=?",
                Integer.class, fixture.userId())).isEqualTo(2);
        runNextGeneration();

        JsonNode newJob = startGeneration(fixture.token());
        assertThat(newJob.path("id").isTextual()).isTrue();
        runNextGeneration();

        JsonNode latest = versions(fixture.token()).path(0);
        JsonNode snapshot = snapshot(latest);
        assertThat(latest.path("confidence").decimalValue()).isEqualByComparingTo("0.20");
        assertThat(snapshot.path("recommendedDifficulty").asInt()).isEqualTo(2);
        assertThat(snapshot.path("dailyRecommendedTasks").asInt()).isEqualTo(2);
        assertThat(snapshot.path("riskNotices").path(0).asText())
                .isEqualTo("当前仅含自评证据，建议完成诊断");
        assertThat(snapshot.path("source").path("selfAssessmentCount").asInt()).isEqualTo(2);
        assertThat(snapshot.path("source").path("latestSelfAssessmentAt").asText()).isNotBlank();
        assertThat(profileStatus(fixture.userId())).isEqualTo("GENERATED");
    }

    @Test
    void repeatedGenerationReturnsTheSameActiveJob() throws Exception {
        ProfileFixture fixture = createProfile("profile_duplicate", "profile-duplicate@example.com");
        runNextGeneration();

        JsonNode first = startGeneration(fixture.token());
        JsonNode second = startGeneration(fixture.token());

        assertThat(second.path("publicId").asText()).isEqualTo(first.path("publicId").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profile_generation_job WHERE user_id=? AND status IN ('QUEUED','RUNNING')",
                Integer.class, fixture.userId())).isEqualTo(1);
        assertThat(queued).hasSize(1);
    }

    @Test
    void availabilityExceptionAndDraftInvalidationCommitTogether() throws Exception {
        ProfileFixture fixture = createProfile("profile_exception", "profile-exception@example.com");
        runNextGeneration();
        JsonNode oldJob = startGeneration(fixture.token());

        mvc.perform(put("/api/v1/profiles/me/availability-exceptions/2026-08-20")
                        .header("Authorization", bearer(fixture.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableMinutes\":0,\"reason\":\"考试\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM availability_exception WHERE user_id=? AND local_date='2026-08-20'",
                Integer.class, fixture.userId())).isEqualTo(1);
        assertThat(profileStatus(fixture.userId())).isEqualTo("DRAFT");
        assertThat(job(fixture.token(), oldJob.path("publicId").asText()).path("errorCode").asText())
                .isEqualTo("PROFILE_CONTEXT_STALE");
    }

    private ProfileFixture createProfile(String username, String email) throws Exception {
        String token = register(username, email);
        long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE email=?", Long.class, email);
        String started = mvc.perform(post("/api/v1/profiles/me/interview-sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode session = json.readTree(started).path("data");
        String request = """
                {"interviewSessionId":"%s","interviewVersion":%d,
                 "profile":{"timezone":"Asia/Shanghai","weekStart":1,"planPeriodDays":31,
                   "planStartDate":"2026-08-01","planEndDate":"2026-08-31","backgroundText":"test",
                   "directions":[{"directionId":"10","customDirection":null,"currentStage":"BEGINNER","primary":true}],"version":null},
                 "preference":{"contentModes":["TEXT","PRACTICE"],"guidanceStyle":"SOCRATIC",
                   "taskGranularity":"MEDIUM","focusMinutes":45,"capacityRatio":0.85,
                   "difficultyMin":1,"difficultyMax":4,"reminders":{},"version":null},
                 "availability":{"slots":[{"weekday":1,"start":"19:00","end":"21:00","energyLevel":"HIGH"}]}}
                """.formatted(session.path("id").asText(), session.path("version").asInt());
        String saved = mvc.perform(post("/api/v1/profiles/me/manual-save")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.generationJob.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode job = json.readTree(saved).path("data").path("generationJob");
        assertThat(job.path("id").isTextual()).isTrue();
        return new ProfileFixture(token, userId);
    }

    private void insertKnowledgePoint() {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO knowledge_point(id,direction_id,parent_id,code,name,level,default_weight,status,
                  created_at,created_by,updated_at,updated_by,version,deleted_at)
                VALUES(9001,10,NULL,'PROFILE_TEST','画像自评测试点',1,1.0,'ACTIVE',?,1,?,1,0,NULL)
                """, now, now);
    }

    private void addSelfAssessment(String token, long knowledgePointId, int level) throws Exception {
        mvc.perform(post("/api/v1/profiles/me/self-assessments")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgePointId\":\"" + knowledgePointId + "\",\"level\":" + level + "}"))
                .andExpect(status().isCreated());
    }

    private JsonNode startGeneration(String token) throws Exception {
        String body = mvc.perform(post("/api/v1/profiles/me/generation-jobs")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json.readTree(body).path("data");
    }

    private JsonNode job(String token, String publicId) throws Exception {
        String body = mvc.perform(get("/api/v1/profiles/me/generation-jobs/{id}", publicId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json.readTree(body).path("data");
    }

    private JsonNode versions(String token) throws Exception {
        String body = mvc.perform(get("/api/v1/profiles/me/versions").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json.readTree(body).path("data");
    }

    private JsonNode snapshot(JsonNode version) throws Exception {
        JsonNode parsed = json.readTree(version.path("snapshotJson").asText());
        return parsed.isTextual() ? json.readTree(parsed.asText()) : parsed;
    }

    private void runNextGeneration() {
        Runnable work = queued.poll();
        assertThat(work).as("profile generation work").isNotNull();
        work.run();
    }

    private int versionCount(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM profile_version version JOIN user_profile profile ON profile.id=version.profile_id WHERE profile.user_id=?",
                Integer.class, userId);
    }

    private String profileStatus(long userId) {
        return jdbc.queryForObject("SELECT profile_status FROM user_profile WHERE user_id=?", String.class, userId);
    }

    private String register(String username, String email) throws Exception {
        mvc.perform(post("/api/v1/auth/email-verification-codes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendVerificationCode(eq(email), eq(EmailVerificationPurpose.REGISTER), code.capture(), any());
        String response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + email
                                + "\",\"password\":\"StrongPass123!\",\"verificationCode\":\""
                                + code.getValue() + "\",\"deviceId\":\"profile-generation-test\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("accessToken").asText();
    }

    private String bearer(String token) { return "Bearer " + token; }

    private record ProfileFixture(String token, long userId) { }
}
