package com.adaptivelearning.profile.application;

import com.adaptivelearning.profile.application.ProfileInterviewModels.*;
import com.adaptivelearning.shared.ai.AiModelClient;
import com.adaptivelearning.shared.ai.ModelRunService;
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
    void invalidStreamKeepsVisibleAiTextAndFallbackOnlyUpdatesDraft() {
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
        assertThat(replacements).isEmpty();
        assertThat(turn.assistantMessage()).contains("周期是30天");
        assertThat(turn.mode()).isEqualTo("AI");
        assertThat(turn.draft().customDirection()).isEqualTo("Java 后端");
        assertThat(turn.draft().planStartDate()).hasToString("2026-08-01");
        assertThat(turn.draft().planEndDate()).hasToString("2026-09-30");
    }
}
