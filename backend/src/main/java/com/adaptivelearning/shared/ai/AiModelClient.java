package com.adaptivelearning.shared.ai;

import java.util.function.Consumer;

public interface AiModelClient {
    boolean isConfigured();

    String modelName();

    Completion complete(String systemPrompt, String userPrompt);

    /**
     * Streams raw assistant-content deltas and returns the same complete content for validation/persistence.
     * Implementations without native streaming remain compatible through this default method.
     */
    default Completion completeStreaming(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        Completion completion = complete(systemPrompt, userPrompt);
        onDelta.accept(completion.content());
        return completion;
    }

    record Completion(String content, Integer inputTokens, Integer outputTokens, long latencyMs) {}
}
