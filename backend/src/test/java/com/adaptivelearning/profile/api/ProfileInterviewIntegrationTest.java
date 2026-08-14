package com.adaptivelearning.profile.api;

import com.adaptivelearning.shared.ai.AiModelClient;
import com.adaptivelearning.shared.ai.AiModelException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.support.application.EmailVerificationPurpose;
import com.adaptivelearning.support.application.VerificationMailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileInterviewIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean VerificationMailService mailService;
    @MockBean AiModelClient modelClient;

    @Test
    void timeoutFallbackDraftCanStillBeConfirmedAndVersioned() throws Exception {
        String token = register("profile_fallback", "profile-fallback@example.com");
        when(modelClient.isConfigured()).thenReturn(true);
        when(modelClient.complete(anyString(), anyString()))
                .thenThrow(new AiModelException(ErrorCode.MODEL_REQUEST_TIMEOUT));

        String started = mvc.perform(post("/api/v1/profiles/me/interview-sessions")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode initial = json.readTree(started).path("data");
        String sessionId = initial.path("id").asText();

        String turn = mvc.perform(post("/api/v1/profiles/me/interview-sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("content",
                                "我想学习计算机科学，零基础，从 2026-08-01 到 2026-08-31，周一 19:00-21:00 有空。",
                                "version", initial.path("version").asInt()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistantMode").value("GUIDED"))
                .andExpect(jsonPath("$.data.readyToConfirm").value(true))
                .andReturn().getResponse().getContentAsString();

        String confirmed = mvc.perform(post("/api/v1/profiles/me/interview-sessions/{id}/confirmation", sessionId)
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + json.readTree(turn).path("data").path("version").asInt() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generationJob.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        awaitGeneration(token, json.readTree(confirmed).path("data").path("generationJob").path("publicId").asText());

        mvc.perform(get("/api/v1/profiles/me/versions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].confidence").value(0.10));
    }

    @Test
    void aiDraftRequiresConfirmationThenPersistsCustomDatesAndVersion() throws Exception {
        String token = register("profile_ai", "profile-ai@example.com");
        when(modelClient.isConfigured()).thenReturn(true);
        when(modelClient.modelName()).thenReturn("test-model");
        when(modelClient.complete(anyString(), anyString())).thenReturn(new AiModelClient.Completion("""
                {
                  "assistantMessage":"信息已经整理好了，请检查草稿后确认。",
                  "updates":{
                    "directionQuery":"计算机科学",
                    "currentStage":"BEGINNER",
                    "planStartDate":"2026-08-01",
                    "planEndDate":"2026-09-30",
                    "timezone":"Asia/Shanghai",
                    "weekStart":1,
                    "backgroundText":"希望系统学习 Java 后端。",
                    "preference":{"contentModes":["TEXT","PRACTICE"],"guidanceStyle":"SOCRATIC","taskGranularity":"MEDIUM","focusMinutes":50,"capacityRatio":0.85,"difficultyMin":1,"difficultyMax":4},
                    "availability":[{"weekday":1,"start":"19:00","end":"21:00","energyLevel":"HIGH"}]
                  }
                }
                """, 120, 80, 25));

        String started = mvc.perform(post("/api/v1/profiles/me/interview-sessions")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.readyToConfirm").value(false))
                .andReturn().getResponse().getContentAsString();
        JsonNode initial = json.readTree(started).path("data");
        String sessionId = initial.path("id").asText();
        int initialVersion = initial.path("version").asInt();

        mvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());

        String turn = mvc.perform(post("/api/v1/profiles/me/interview-sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("content",
                                "我想学计算机科学，零基础，8 月到 9 月，每周一晚上学习。", "version", initialVersion))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistantMode").value("AI"))
                .andExpect(jsonPath("$.data.readyToConfirm").value(true))
                .andExpect(jsonPath("$.data.draft.planStartDate").value("2026-08-01"))
                .andExpect(jsonPath("$.data.draft.planEndDate").value("2026-09-30"))
                .andReturn().getResponse().getContentAsString();
        int turnVersion = json.readTree(turn).path("data").path("version").asInt();

        // AI 对话只更新可审阅草稿，不应在确认前写入正式画像。
        mvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());

        String confirmed = mvc.perform(post("/api/v1/profiles/me/interview-sessions/{id}/confirmation", sessionId)
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + turnVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.profile.planStartDate").value("2026-08-01"))
                .andExpect(jsonPath("$.data.profile.planEndDate").value("2026-09-30"))
                .andExpect(jsonPath("$.data.profile.planPeriodDays").value(61))
                .andExpect(jsonPath("$.data.profile.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.generationJob.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        awaitGeneration(token, json.readTree(confirmed).path("data").path("generationJob").path("publicId").asText());

        mvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("GENERATED"));

        mvc.perform(get("/api/v1/profiles/me/versions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].versionNo").value(1));
        verify(modelClient).complete(anyString(), contains("latestUserMessage"));
    }

    @Test
    void manualSaveIsAtomicAndSynchronizesTheInterviewDraft() throws Exception {
        String token = register("profile_manual", "profile-manual@example.com");
        String started = mvc.perform(post("/api/v1/profiles/me/interview-sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode session = json.readTree(started).path("data");

        String request = """
                {
                  "interviewSessionId":"%s",
                  "interviewVersion":%d,
                  "profile":{
                    "timezone":"Asia/Shanghai","weekStart":1,"planPeriodDays":7,
                    "planStartDate":"2026-08-03","planEndDate":"2026-08-09",
                    "backgroundText":"零基础，希望系统学习心理学。",
                    "directions":[{"directionId":null,"customDirection":"心理学","currentStage":"BEGINNER","primary":true}],
                    "version":null
                  },
                  "preference":{
                    "contentModes":["TEXT","PRACTICE"],"guidanceStyle":"SOCRATIC",
                    "taskGranularity":"MEDIUM","focusMinutes":45,"capacityRatio":0.85,
                    "difficultyMin":1,"difficultyMax":4,"reminders":{"TASK_DUE":true},"version":null
                  },
                  "availability":{"slots":[
                    {"weekday":1,"start":"19:00","end":"21:00","energyLevel":"HIGH"}
                  ]}
                }
                """.formatted(session.path("id").asText(), session.path("version").asInt());

        String saved = mvc.perform(post("/api/v1/profiles/me/manual-save")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.generationJob.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.profile.planPeriodDays").value(7))
                .andExpect(jsonPath("$.data.profile.directions[0].customDirection").value("心理学"))
                .andExpect(jsonPath("$.data.profile.directions[0].sourceType").value("CUSTOM"))
                .andExpect(jsonPath("$.data.profile.directions[0].knowledgeBaseDirection").value(false))
                .andExpect(jsonPath("$.data.interview.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.interview.assistantMode").value("MANUAL"))
                .andExpect(jsonPath("$.data.interview.completenessPercent").value(100))
                .andExpect(jsonPath("$.data.interview.draft.customDirection").value("心理学"))
                .andExpect(jsonPath("$.data.interview.draft.planStartDate").value("2026-08-03"))
                .andExpect(jsonPath("$.data.interview.draft.planEndDate").value("2026-08-09"))
                .andExpect(jsonPath("$.data.interview.draft.availability[0].weekday").value(1))
                .andReturn().getResponse().getContentAsString();
        awaitGeneration(token, json.readTree(saved).path("data").path("generationJob").path("publicId").asText());

        mvc.perform(get("/api/v1/profiles/me/versions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].versionNo").value(1))
                .andExpect(jsonPath("$.data[0].triggerType").value("MANUAL_SAVE"))
                .andExpect(jsonPath("$.data[0].snapshotJson").value(containsString("\\\"sourceType\\\":\\\"CUSTOM\\\"")))
                .andExpect(jsonPath("$.data[0].snapshotJson").value(containsString("\\\"knowledgeBaseDirection\\\":false")));
    }

    @Test
    void invalidManualAvailabilityRollsBackEveryProfileWrite() throws Exception {
        String token = register("profile_rollback", "profile-rollback@example.com");
        String started = mvc.perform(post("/api/v1/profiles/me/interview-sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode session = json.readTree(started).path("data");

        String request = """
                {
                  "interviewSessionId":"%s",
                  "interviewVersion":%d,
                  "profile":{
                    "timezone":"Asia/Shanghai","weekStart":1,"planPeriodDays":7,
                    "planStartDate":"2026-08-03","planEndDate":"2026-08-09",
                    "directions":[{"directionId":null,"customDirection":"心理学","currentStage":"BEGINNER","primary":true}],
                    "version":null
                  },
                  "preference":{
                    "contentModes":["TEXT"],"guidanceStyle":"DIRECT","taskGranularity":"MEDIUM",
                    "focusMinutes":45,"capacityRatio":0.85,"difficultyMin":1,"difficultyMax":4,
                    "reminders":{},"version":null
                  },
                  "availability":{"slots":[
                    {"weekday":1,"start":"19:00","end":"21:00","energyLevel":"HIGH"},
                    {"weekday":1,"start":"20:00","end":"22:00","energyLevel":"MEDIUM"}
                  ]}
                }
                """.formatted(session.path("id").asText(), session.path("version").asInt());

        mvc.perform(post("/api/v1/profiles/me/manual-save")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("重叠")));

        mvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/v1/profiles/me/interview-sessions/active")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(session.path("version").asInt()));
    }

    @Test
    void streamsVisibleAssistantTextThenCompletesWithValidatedDraft() throws Exception {
        String token = register("profile_stream", "profile-stream@example.com");
        when(modelClient.isConfigured()).thenReturn(true);
        when(modelClient.modelName()).thenReturn("stream-test-model");
        String raw = """
                {"assistantMessage":"已经收到，我正在整理你的学习画像。","updates":{
                  "directionQuery":"计算机科学","currentStage":"BEGINNER",
                  "planStartDate":"2026-10-01","planEndDate":"2026-10-31",
                  "availability":[{"weekday":2,"start":"19:00","end":"21:00","energyLevel":"HIGH"}]}}
                """.trim();
        when(modelClient.completeStreaming(anyString(), anyString(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") Consumer<String> delta = invocation.getArgument(2);
            delta.accept(raw.substring(0, 24));
            delta.accept(raw.substring(24, 39));
            delta.accept(raw.substring(39));
            return new AiModelClient.Completion(raw, 100, 60, 30);
        });

        String started = mvc.perform(post("/api/v1/profiles/me/interview-sessions")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode initial = json.readTree(started).path("data");

        MvcResult stream = mvc.perform(post("/api/v1/profiles/me/interview-sessions/{id}/messages",
                                initial.path("id").asText())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "content", "我想十月份学习计算机科学，每周二晚上有空。",
                                "version", initial.path("version").asInt()))))
                .andExpect(request().asyncStarted()).andReturn();
        stream.getAsyncResult(10_000);

        MvcResult completed = mvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn();
        // SSE is UTF-8 by specification; MockHttpServletResponse otherwise defaults to ISO-8859-1.
        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event:message.started", "event:message.delta", "已经收到",
                        "event:message.completed", "2026-10-01")
                .doesNotContain("directionQuery");
        verify(modelClient).completeStreaming(anyString(), contains("latestUserMessage"), any());
    }

    private void awaitGeneration(String token, String jobId) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            String body = mvc.perform(get("/api/v1/profiles/me/generation-jobs/{id}", jobId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String status = json.readTree(body).path("data").path("status").asText();
            if ("SUCCEEDED".equals(status)) return;
            assertThat(status).isNotEqualTo("FAILED");
            Thread.sleep(25);
        }
        throw new AssertionError("画像生成作业未在测试时限内完成: " + jobId);
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
                                + code.getValue() + "\",\"deviceId\":\"profile-test\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = json.readTree(response).path("data").path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }
}
