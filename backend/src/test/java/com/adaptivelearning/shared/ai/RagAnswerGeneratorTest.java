package com.adaptivelearning.shared.ai;

import com.adaptivelearning.shared.ratelimit.RedisRateLimiter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagAnswerGeneratorTest {
    private final AiModelClient model = mock(AiModelClient.class);
    private final ModelRunService runs = mock(ModelRunService.class);
    private final RedisRateLimiter limiter = mock(RedisRateLimiter.class);
    private final PythonAiServiceClient python = mock(PythonAiServiceClient.class);
    private final RagAnswerGenerator generator = new RagAnswerGenerator(model, runs, limiter, python);
    private final List<RagAnswerGenerator.Evidence> evidence = List.of(
            new RagAnswerGenerator.Evidence("S1", 101L, 201L, 301L,
                    "notes.txt", "证据内容", List.of(), null, null));

    @Test
    void returnsModelAnswerWhenCitationsAreValid() {
        when(model.isConfigured()).thenReturn(true);
        when(model.modelName()).thenReturn("deepseek-v4-flash");
        when(model.complete(anyString(), anyString()))
                .thenReturn(new AiModelClient.Completion("基于资料的答案 [S1]", 12, 8, 30));
        when(runs.recordSuccess(anyLong(), anyString(), anyString(), anyList(), any())).thenReturn(9L);

        RagAnswerGenerator.GeneratedAnswer answer = generator.generate(7L, "问题", evidence);

        assertThat(answer.answerMode()).isEqualTo("RAG_AI");
        assertThat(answer.modelRunId()).isEqualTo(9L);
        assertThat(answer.citationIds()).containsExactly("S1");
        verify(limiter).requireModelAllowed(7L);
    }

    @Test
    void rejectsInventedCitationAndFallsBackToEvidence() {
        when(model.isConfigured()).thenReturn(true);
        when(model.modelName()).thenReturn("deepseek-v4-flash");
        when(model.complete(anyString(), anyString()))
                .thenReturn(new AiModelClient.Completion("伪造答案 [S9]", 12, 8, 30));
        when(runs.recordFailure(anyLong(), anyString(), anyString(), anyList(), anyLong(), anyString()))
                .thenReturn(10L);

        RagAnswerGenerator.GeneratedAnswer answer = generator.generate(7L, "问题", evidence);

        assertThat(answer.answerMode()).isEqualTo("RAG_FALLBACK");
        assertThat(answer.content()).contains("证据内容", "[S1]");
        assertThat(answer.modelRunId()).isEqualTo(10L);
    }
}
