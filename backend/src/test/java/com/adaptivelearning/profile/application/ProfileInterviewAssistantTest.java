package com.adaptivelearning.profile.application;

import com.adaptivelearning.profile.application.ProfileInterviewModels.*;
import com.adaptivelearning.shared.ai.AiModelClient;
import com.adaptivelearning.shared.ai.AiModelException;
import com.adaptivelearning.shared.ai.ModelRunService;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.ratelimit.RedisRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProfileInterviewAssistantTest {
    @Test
    void recoverableAiFailuresUseGuidedFallback() {
        for (ErrorCode code : List.of(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE,
                ErrorCode.MODEL_REQUEST_TIMEOUT, ErrorCode.MODEL_QUOTA_EXCEEDED,
                ErrorCode.MODEL_OUTPUT_INVALID)) {
            PythonAiServiceClient python = mock(PythonAiServiceClient.class);
            when(python.isConfigured()).thenReturn(true);
            when(python.profileTurnStreaming(anyLong(), anyString(), any(), anyList(), anyList(), anyString(), any()))
                    .thenThrow(new AiModelException(code));
            ProfileInterviewAssistant assistant = assistantWithPython(python);

            AssistantTurn turn = assistant.respond(1L, initialDraft(), List.of(),
                    "我想学习计算机科学，零基础", List.of(new DirectionOption(10L, "CS", "计算机科学")));

            assertThat(turn.mode()).isEqualTo("GUIDED");
            assertThat(turn.draft().directionId()).isEqualTo(10L);
        }
    }

    @Test
    void providerServerFailureUsesGuidedFallbackButConfigurationFailureDoesNot() {
        PythonAiServiceClient unavailable = failingPython(new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR,
                null, Map.of("pythonCode", "AI_PROVIDER_ERROR", "providerStatus", 503), null));
        assertThat(assistantWithPython(unavailable).respond(1L, initialDraft(), List.of(),
                "我想学习计算机科学，零基础", List.of(new DirectionOption(10L, "CS", "计算机科学"))).mode())
                .isEqualTo("GUIDED");

        PythonAiServiceClient configuration = failingPython(new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR,
                null, Map.of("pythonCode", "AI_PROVIDER_AUTH_FAILED", "providerStatus", 401), null));
        assertThatThrownBy(() -> assistantWithPython(configuration).respond(1L, initialDraft(), List.of(),
                "我想学习计算机科学，零基础", List.of(new DirectionOption(10L, "CS", "计算机科学"))))
                .isInstanceOf(AiModelException.class);
    }

    @Test
    void internalAuthenticationAndRequestContractFailuresDoNotFallback() {
        for (String pythonCode : List.of("AI_INTERNAL_UNAUTHORIZED", "AI_REQUEST_INVALID", "AI_MODEL_NOT_FOUND")) {
            PythonAiServiceClient python = failingPython(new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR,
                    null, Map.of("pythonCode", pythonCode), null));

            assertThatThrownBy(() -> assistantWithPython(python).respond(1L, initialDraft(), List.of(),
                    "我想学习计算机科学", List.of(new DirectionOption(10L, "CS", "计算机科学"))))
                    .isInstanceOf(AiModelException.class);
        }
    }

    @Test
    void businessRateLimitIsNotConvertedToFallback() {
        PythonAiServiceClient python = mock(PythonAiServiceClient.class);
        when(python.isConfigured()).thenReturn(true);
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        doThrow(new com.adaptivelearning.shared.exception.BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED,
                "too many requests")).when(limiter).requireModelAllowed(1L);
        ProfileInterviewAssistant assistant = new ProfileInterviewAssistant(mock(AiModelClient.class), limiter,
                new ObjectMapper().registerModule(new JavaTimeModule()), mock(ModelRunService.class));
        assistant.setPythonAi(python);

        assertThatThrownBy(() -> assistant.respond(1L, initialDraft(), List.of(), "学习 Java", List.of()))
                .isInstanceOf(com.adaptivelearning.shared.exception.BusinessException.class);
        verify(python, never()).profileTurnStreaming(anyLong(), anyString(), any(), anyList(), anyList(), anyString(), any());
    }

    @Test
    void recoverableStreamingFailureReplacesPartialOutputAndCompletesAsGuided() {
        PythonAiServiceClient python = mock(PythonAiServiceClient.class);
        when(python.isConfigured()).thenReturn(true);
        when(python.profileTurnStreaming(anyLong(), anyString(), any(), anyList(), anyList(), anyString(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked") Consumer<String> delta = invocation.getArgument(6);
                    delta.accept("未完成的 AI 文本");
                    throw new AiModelException(ErrorCode.MODEL_REQUEST_TIMEOUT);
                });
        List<String> replaced = new ArrayList<>();

        AssistantTurn turn = assistantWithPython(python).respondStreaming(1L, initialDraft(), List.of(),
                "我想学习计算机科学，零基础", List.of(new DirectionOption(10L, "CS", "计算机科学")),
                new ProfileInterviewAssistant.StreamOutput() {
                    @Override public void delta(String text) { }
                    @Override public void replace(String text) { replaced.add(text); }
                });

        assertThat(turn.mode()).isEqualTo("GUIDED");
        assertThat(replaced).containsExactly(turn.assistantMessage());
        assertThat(turn.draft().directionId()).isEqualTo(10L);
    }

    private ProfileInterviewAssistant assistantWithPython(PythonAiServiceClient python) {
        ProfileInterviewAssistant assistant = new ProfileInterviewAssistant(mock(AiModelClient.class),
                mock(RedisRateLimiter.class), new ObjectMapper().registerModule(new JavaTimeModule()),
                mock(ModelRunService.class));
        assistant.setPythonAi(python);
        return assistant;
    }

    private PythonAiServiceClient failingPython(AiModelException error) {
        PythonAiServiceClient python = mock(PythonAiServiceClient.class);
        when(python.isConfigured()).thenReturn(true);
        when(python.profileTurnStreaming(anyLong(), anyString(), any(), anyList(), anyList(), anyString(), any()))
                .thenThrow(error);
        return python;
    }

    private Draft initialDraft() {
        return new Draft("Asia/Shanghai", 1, null, null, null, null, null, null, null,
                new PreferenceDraft(List.of("TEXT"), "SOCRATIC", "MEDIUM", 45,
                        new BigDecimal("0.85"), 1, 4, Map.of()), List.of(), Map.of());
    }

    @Test
    void guidedFallbackExtractsCatalogStagePeriodAndTime() {
        AiModelClient model = mock(AiModelClient.class);
        when(model.isConfigured()).thenReturn(false);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        ProfileInterviewAssistant assistant = new ProfileInterviewAssistant(model, mock(RedisRateLimiter.class), json,
                mock(ModelRunService.class));
        Draft initial = new Draft("Asia/Shanghai", 1, null, null, null, null, null, null, null,
                new PreferenceDraft(List.of("TEXT"), "SOCRATIC", "MEDIUM", 45, new BigDecimal("0.85"),
                        1, 4, Map.of()), List.of(), Map.of());

        AssistantTurn turn = assistant.respond(1, initial, List.of(),
                "我想学计算机科学，零基础，从今天开始学 6 周，周一和周三 19:00-21:00 有空",
                List.of(new DirectionOption(10L, "COMPUTER_SCIENCE", "计算机科学")));

        assertThat(turn.mode()).isEqualTo("GUIDED");
        assertThat(turn.draft().directionId()).isEqualTo(10L);
        assertThat(turn.draft().currentStage()).isEqualTo("BEGINNER");
        assertThat(turn.draft().planStartDate()).isNotNull();
        assertThat(turn.draft().planEndDate()).isEqualTo(turn.draft().planStartDate().plusDays(41));
        assertThat(turn.draft().availability()).hasSize(2);
    }

    @Test
    void guidedFallbackMapsEconomicsToCatalogDirection() {
        AiModelClient model = mock(AiModelClient.class);
        when(model.isConfigured()).thenReturn(false);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        ProfileInterviewAssistant assistant = new ProfileInterviewAssistant(model, mock(RedisRateLimiter.class), json,
                mock(ModelRunService.class));
        Draft initial = new Draft("Asia/Shanghai", 1, null, null, null, null, null, null, null,
                new PreferenceDraft(List.of("TEXT"), "SOCRATIC", "MEDIUM", 45, new BigDecimal("0.85"),
                        1, 4, Map.of()), List.of(), Map.of());

        AssistantTurn turn = assistant.respond(1, initial, List.of(),
                "想学经济学，零基础，希望一周学完，周一到周五 20:00-22:00 有空",
                List.of(new DirectionOption(200L, "ECONOMICS", "经济学")));

        assertThat(turn.draft().directionId()).isEqualTo(200L);
        assertThat(turn.draft().directionName()).isEqualTo("经济学");
        assertThat(turn.draft().customDirection()).isNull();
        assertThat(turn.draft().currentStage()).isEqualTo("BEGINNER");
        assertThat(turn.draft().planEndDate()).isEqualTo(turn.draft().planStartDate().plusDays(6));
    }

    @Test
    void rejectsSecretsBeforeCallingModel() {
        AiModelClient model = mock(AiModelClient.class);
        when(model.isConfigured()).thenReturn(true);
        ProfileInterviewAssistant assistant = new ProfileInterviewAssistant(model, mock(RedisRateLimiter.class), new ObjectMapper(),
                mock(ModelRunService.class));
        Draft initial = new Draft("Asia/Shanghai", 1, null, null, null, null, null, null, null,
                new PreferenceDraft(List.of("TEXT"), "SOCRATIC", "MEDIUM", 45, new BigDecimal("0.85"), 1, 4, Map.of()), List.of(), Map.of());

        assertThatThrownBy(() -> assistant.respond(1, initial, List.of(), "API key: sk-secret-value", List.of()))
                .hasMessageContaining("敏感信息");
        verify(model, never()).complete(anyString(), anyString());
    }

    @Test
    void ignoresNonDocumentContentModesFromModelOutput() {
        AiModelClient model = mock(AiModelClient.class);
        when(model.isConfigured()).thenReturn(true);
        when(model.complete(anyString(), anyString())).thenReturn(new AiModelClient.Completion("""
                {"assistantMessage":"我会按文档资料和练习来整理。","updates":{
                  "directionQuery":"Java","currentStage":"BEGINNER",
                  "planStartDate":null,"planEndDate":null,"planPeriodDays":30,
                  "timezone":"Asia/Shanghai","weekStart":1,"backgroundText":null,
                  "preference":{"contentModes":["VIDEO","AUDIO","TEXT","PRACTICE"],
                    "guidanceStyle":"SOCRATIC","taskGranularity":"MEDIUM",
                    "focusMinutes":45,"capacityRatio":0.85,"difficultyMin":1,"difficultyMax":4},
                  "availability":null}}
                """, 10, 20, 30));
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        ProfileInterviewAssistant assistant = new ProfileInterviewAssistant(model, mock(RedisRateLimiter.class), json,
                mock(ModelRunService.class));
        Draft initial = new Draft("Asia/Shanghai", 1, null, null, null, null, null, null, null,
                new PreferenceDraft(List.of("VIDEO"), "SOCRATIC", "MEDIUM", 45, new BigDecimal("0.85"),
                        1, 4, Map.of()), List.of(), Map.of());

        AssistantTurn turn = assistant.respond(1, initial, List.of(), "我想学习 Java，先按资料学。", List.of());

        assertThat(turn.draft().preference().contentModes()).containsExactly("TEXT", "PRACTICE");
    }

    @Test
    void invalidModelDirectionFallsBackToUserEvidence() {
        AiModelClient model = mock(AiModelClient.class);
        when(model.isConfigured()).thenReturn(true);
        when(model.complete(anyString(), anyString())).thenReturn(new AiModelClient.Completion("""
                {"assistantMessage":"我来整理。","updates":{
                  "directionQuery":"พญผรัง","currentStage":"BEGINNER",
                  "planStartDate":null,"planEndDate":null,"planPeriodDays":7,
                  "availability":null}}
                """, 10, 20, 30));
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        ProfileInterviewAssistant assistant = new ProfileInterviewAssistant(model, mock(RedisRateLimiter.class), json,
                mock(ModelRunService.class));
        Draft initial = new Draft("Asia/Shanghai", 1, null, null, null, null, null, null, null,
                new PreferenceDraft(List.of("TEXT"), "SOCRATIC", "MEDIUM", 45, new BigDecimal("0.85"),
                        1, 4, Map.of()), List.of(), Map.of());

        AssistantTurn turn = assistant.respond(1, initial, List.of(),
                "我想学习经济学，零基础，从今天开始一周，周一 20:00-21:00 有空",
                List.of(new DirectionOption(200L, "ECONOMICS", "经济学")));

        assertThat(turn.mode()).isEqualTo("GUIDED");
        assertThat(turn.draft().directionId()).isEqualTo(200L);
        assertThat(turn.draft().customDirection()).isNull();
    }

    @Test
    void weeklyDayCountClearsInventedFullWeekAndAsksForSpecificDays() {
        AiModelClient model = mock(AiModelClient.class);
        when(model.isConfigured()).thenReturn(true);
        when(model.complete(anyString(), anyString())).thenReturn(new AiModelClient.Completion("""
                {"assistantMessage":"已按一周七天设置完成。","updates":{
                  "directionQuery":null,"currentStage":null,
                  "planStartDate":null,"planEndDate":null,"planPeriodDays":null,
                  "availability":[
                    {"weekday":1,"start":"19:00","end":"21:00","energyLevel":"MEDIUM"},
                    {"weekday":2,"start":"19:00","end":"21:00","energyLevel":"MEDIUM"},
                    {"weekday":3,"start":"19:00","end":"21:00","energyLevel":"MEDIUM"},
                    {"weekday":4,"start":"19:00","end":"21:00","energyLevel":"MEDIUM"},
                    {"weekday":5,"start":"19:00","end":"21:00","energyLevel":"MEDIUM"},
                    {"weekday":6,"start":"19:00","end":"21:00","energyLevel":"MEDIUM"},
                    {"weekday":7,"start":"19:00","end":"21:00","energyLevel":"MEDIUM"}]}}
                """, 10, 20, 30));
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        ProfileInterviewAssistant assistant = new ProfileInterviewAssistant(
                model, mock(RedisRateLimiter.class), json, mock(ModelRunService.class));
        List<SlotDraft> oldFullWeek = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(day -> new SlotDraft(day, java.time.LocalTime.of(19, 0),
                        java.time.LocalTime.of(21, 0), "MEDIUM"))
                .toList();
        Draft initial = new Draft("Asia/Shanghai", 1, java.time.LocalDate.of(2026, 8, 1),
                java.time.LocalDate.of(2026, 10, 31), 101L, "Java 后端开发", null, "BEGINNER", null,
                new PreferenceDraft(List.of("TEXT"), "SOCRATIC", "MEDIUM", 45,
                        new BigDecimal("0.85"), 1, 4, Map.of()), oldFullWeek, Map.of());

        AssistantTurn turn = assistant.respond(
                1, initial, List.of(), "我改成一周学习三天，每次两小时", List.of());

        assertThat(turn.draft().availability()).isEmpty();
        assertThat(turn.assistantMessage()).contains("请选择具体星期几");
        assertThat(turn.assistantMessage()).doesNotContain("一周七天");
    }

    @Test
    void incompleteDraftCannotClaimItIsReadyOrAskForChatConfirmation() {
        AiModelClient model = mock(AiModelClient.class);
        when(model.isConfigured()).thenReturn(true);
        when(model.complete(anyString(), anyString())).thenReturn(new AiModelClient.Completion("""
                {"assistantMessage":"画像草稿已经完整，请回复确认保存。","updates":{
                  "directionQuery":null,"currentStage":null,
                  "planStartDate":null,"planEndDate":null,"planPeriodDays":null,
                  "availability":null}}
                """, 10, 20, 30));
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        ProfileInterviewAssistant assistant = new ProfileInterviewAssistant(
                model, mock(RedisRateLimiter.class), json, mock(ModelRunService.class));
        Draft initial = new Draft("Asia/Shanghai", 1, java.time.LocalDate.of(2026, 7, 31),
                java.time.LocalDate.of(2026, 8, 6), null, "心理学", "心理学", "BEGINNER", null,
                new PreferenceDraft(List.of("TEXT", "PRACTICE"), "SOCRATIC", "MEDIUM", 45,
                        new BigDecimal("0.85"), 1, 4, Map.of()), List.of(), Map.of());

        AssistantTurn turn = assistant.respond(1, initial, List.of(), "保存", List.of());

        assertThat(turn.assistantMessage()).contains("每周", "时间");
        assertThat(turn.assistantMessage()).doesNotContain("草稿已经完整", "回复确认保存");
        assertThat(turn.draft().availability()).isEmpty();
    }

    @Test
    void projectsOnlyAssistantTextAcrossArbitraryJsonChunks() {
        List<String> deltas = new ArrayList<>();
        ProfileInterviewAssistant.AssistantMessageProjector projector =
                new ProfileInterviewAssistant.AssistantMessageProjector(deltas::add);

        projector.accept("{\"assi");
        projector.accept("stantMessage\":\"你好，\u4e16");
        assertThat(String.join("", deltas)).isEqualTo("你好，世");
        projector.accept("界\\n请告诉我时间。\",\"updates\":{");
        projector.accept("\"backgroundText\":\"内部字段不得输出\"}}");

        assertThat(projector.emittedText()).isEqualTo("你好，世界\n请告诉我时间。");
        assertThat(projector.emittedText()).doesNotContain("backgroundText", "内部字段");
    }

    @Test
    void invalidStreamReplacesVisibleAiTextWithGuidedFallback() {
        AiModelClient model = mock(AiModelClient.class);
        when(model.isConfigured()).thenReturn(true);
        when(model.modelName()).thenReturn("test-model");
        String raw = """
                {"assistantMessage":"周期是30天，请确认。","updates":{
                  "directionQuery":"Java 后端","currentStage":"BEGINNER",
                  "planStartDate":"2026-08-01","planEndDate":"2026-09-30","planPeriodDays":30,
                  "availability":[{"weekday":1,"start":"19:00","end":"21:00","energyLevel":"MEDIUM"}]}}
                """.trim();
        when(model.completeStreaming(anyString(), anyString(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") Consumer<String> delta = invocation.getArgument(2);
            delta.accept(raw.substring(0, 35));
            delta.accept(raw.substring(35));
            return new AiModelClient.Completion(raw, null, null, 10);
        });
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        ProfileInterviewAssistant assistant = new ProfileInterviewAssistant(model, mock(RedisRateLimiter.class), json,
                mock(ModelRunService.class));
        Draft initial = new Draft("Asia/Shanghai", 1, null, null, null, null, null, null, null,
                new PreferenceDraft(List.of("TEXT"), "SOCRATIC", "MEDIUM", 45, new BigDecimal("0.85"),
                        1, 4, Map.of()), List.of(), Map.of());
        List<String> streamed = new ArrayList<>();
        List<String> replacements = new ArrayList<>();

        AssistantTurn turn = assistant.respondStreaming(1, initial, List.of(),
                "我想学习 Java 后端，目前零基础，从 2026 年 8 月 1 日到 2026 年 9 月 30 日，周一 19:00-21:00 有空。",
                List.of(), new ProfileInterviewAssistant.StreamOutput() {
                    @Override public void delta(String text) { streamed.add(text); }
                    @Override public void replace(String text) { replacements.add(text); }
                });

        assertThat(String.join("", streamed)).contains("周期是30天");
        assertThat(replacements).containsExactly(turn.assistantMessage());
        assertThat(turn.assistantMessage()).doesNotContain("周期是30天");
        assertThat(turn.mode()).isEqualTo("GUIDED");
        assertThat(turn.draft().customDirection()).isEqualTo("Java 后端");
        assertThat(turn.draft().planStartDate()).hasToString("2026-08-01");
        assertThat(turn.draft().planEndDate()).hasToString("2026-09-30");
    }
}
