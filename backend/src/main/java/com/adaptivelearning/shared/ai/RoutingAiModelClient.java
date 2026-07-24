package com.adaptivelearning.shared.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/** Routes model work to Python while retaining the direct provider as a migration fallback. */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class RoutingAiModelClient implements AiModelClient {
    private final PythonAiServiceClient python;
    private final OpenAiCompatibleChatClient legacy;

    @Override
    public boolean isConfigured() {
        return python.isConfigured() || legacy.isConfigured();
    }

    @Override
    public String modelName() {
        return python.isConfigured() ? python.modelName() : legacy.modelName();
    }

    @Override
    public Completion complete(String systemPrompt, String userPrompt) {
        if (python.isConfigured()) {
            try {
                return python.complete(systemPrompt, userPrompt);
            } catch (AiModelException error) {
                if (!legacy.isConfigured()) throw error;
                log.warn("Python AI service unavailable; using direct model compatibility fallback");
            }
        }
        return legacy.complete(systemPrompt, userPrompt);
    }

    @Override
    public Completion completeStreaming(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        if (python.isConfigured()) {
            try {
                return python.completeStreaming(systemPrompt, userPrompt, onDelta);
            } catch (AiModelException error) {
                if (!legacy.isConfigured()) throw error;
                log.warn("Python AI stream unavailable; using direct model compatibility fallback");
            }
        }
        return legacy.completeStreaming(systemPrompt, userPrompt, onDelta);
    }
}
