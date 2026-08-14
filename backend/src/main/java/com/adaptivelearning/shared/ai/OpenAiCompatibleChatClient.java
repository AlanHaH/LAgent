package com.adaptivelearning.shared.ai;

import com.adaptivelearning.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@Slf4j
@Service
public class OpenAiCompatibleChatClient implements AiModelClient {
    private final String model;
    private final boolean configured;
    private final RestClient client;
    private final String endpoint;
    private final int maxOutputTokens;
    private final String thinkingMode;
    private final ObjectMapper json;

    public OpenAiCompatibleChatClient(
            ObjectMapper json,
            @Value("${app.ai.enabled:true}") boolean enabled,
            @Value("${app.ai.base-url:}") String baseUrl,
            @Value("${app.ai.api-key:}") String apiKey,
            @Value("${app.ai.model:}") String model,
            @Value("${app.ai.timeout:PT45S}") Duration timeout,
            @Value("${app.ai.max-output-tokens:1200}") int maxOutputTokens,
            @Value("${app.ai.thinking:disabled}") String thinkingMode) {
        this.json = json;
        this.model = model == null ? "" : model.trim();
        this.maxOutputTokens = Math.max(1, Math.min(maxOutputTokens, 8_000));
        this.thinkingMode = thinkingMode == null ? "" : thinkingMode.trim();
        this.configured = enabled && notBlank(baseUrl) && notBlank(apiKey) && notBlank(model);

        if (!configured) {
            this.client = null;
            this.endpoint = null;
            return;
        }

        this.endpoint = chatCompletionsEndpoint(baseUrl);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey.trim())
                .build();
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public Completion complete(String systemPrompt, String userPrompt) {
        if (!configured) {
            throw new AiModelException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE);
        }

        Map<String, Object> request = request(systemPrompt, userPrompt, false);

        long begin = System.nanoTime();
        try {
            JsonNode response = client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            long latency = (System.nanoTime() - begin) / 1_000_000;
            JsonNode contentNode = response == null ? null : response.at("/choices/0/message/content");
            String content = contentNode == null || contentNode.isMissingNode() ? "" : contentNode.asText().trim();
            if (content.isBlank() || content.length() > 10_000) {
                throw new AiModelException(ErrorCode.MODEL_OUTPUT_INVALID);
            }
            return new Completion(content, integerAt(response, "/usage/prompt_tokens"),
                    integerAt(response, "/usage/completion_tokens"), latency);
        } catch (AiModelException e) {
            throw e;
        } catch (ResourceAccessException e) {
            log.warn("AI model request timed out or was unreachable: {}", e.getClass().getSimpleName());
            throw new AiModelException(ErrorCode.MODEL_REQUEST_TIMEOUT, e);
        } catch (RestClientResponseException e) {
            ErrorCode code = e.getStatusCode().value() == 429
                    ? ErrorCode.MODEL_QUOTA_EXCEEDED : ErrorCode.MODEL_PROVIDER_ERROR;
            log.warn("AI model provider returned HTTP {}", e.getStatusCode().value());
            throw new AiModelException(code, null, Map.of("providerStatus", e.getStatusCode().value()), e);
        } catch (RuntimeException e) {
            log.warn("AI model response could not be processed: {}", e.getClass().getSimpleName());
            throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR, e);
        }
    }

    @Override
    public Completion completeStreaming(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        if (!configured) {
            throw new AiModelException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE);
        }
        Map<String, Object> request = request(systemPrompt, userPrompt, true);
        long begin = System.nanoTime();
        try {
            String content = client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(request)
                    .exchange((httpRequest, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            ErrorCode code = response.getStatusCode().value() == 429
                                    ? ErrorCode.MODEL_QUOTA_EXCEEDED : ErrorCode.MODEL_PROVIDER_ERROR;
                            throw new AiModelException(code, null,
                                    Map.of("providerStatus", response.getStatusCode().value()), null);
                        }
                        StringBuilder result = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                                response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.startsWith("data:")) continue;
                                String payload = line.substring(5).trim();
                                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
                                JsonNode event = json.readTree(payload);
                                JsonNode delta = event.at("/choices/0/delta/content");
                                if (!delta.isTextual() || delta.asText().isEmpty()) continue;
                                String piece = delta.asText();
                                if (result.length() + piece.length() > 10_000) {
                                    throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR);
                                }
                                result.append(piece);
                                onDelta.accept(piece);
                            }
                        }
                        return result.toString();
                    });
            long latency = (System.nanoTime() - begin) / 1_000_000;
            if (content == null || content.isBlank()) {
                throw new AiModelException(ErrorCode.MODEL_OUTPUT_INVALID);
            }
            return new Completion(content.trim(), null, null, latency);
        } catch (AiStreamCancelledException | AiModelException e) {
            throw e;
        } catch (ResourceAccessException e) {
            log.warn("AI model stream timed out or was unreachable: {}", e.getClass().getSimpleName());
            throw new AiModelException(ErrorCode.MODEL_REQUEST_TIMEOUT, e);
        } catch (RestClientResponseException e) {
            ErrorCode code = e.getStatusCode().value() == 429
                    ? ErrorCode.MODEL_QUOTA_EXCEEDED : ErrorCode.MODEL_PROVIDER_ERROR;
            log.warn("AI model stream provider returned HTTP {}", e.getStatusCode().value());
            throw new AiModelException(code, null, Map.of("providerStatus", e.getStatusCode().value()), e);
        } catch (RuntimeException e) {
            log.warn("AI model stream could not be processed: {}", e.getClass().getSimpleName());
            throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR, e);
        }
    }

    private Map<String, Object> request(String systemPrompt, String userPrompt, boolean stream) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        request.put("stream", stream);
        request.put("max_tokens", maxOutputTokens);
        if (Set.of("enabled", "disabled").contains(thinkingMode)) {
            request.put("thinking", Map.of("type", thinkingMode));
        }
        return request;
    }

    private Integer integerAt(JsonNode root, String pointer) {
        JsonNode node = root.at(pointer);
        return node.isIntegralNumber() ? node.intValue() : null;
    }

    private String chatCompletionsEndpoint(String rawBaseUrl) {
        String base = rawBaseUrl.trim();
        URI uri;
        try {
            uri = URI.create(base);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("MODEL_BASE_URL is invalid", e);
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException(
                    "MODEL_BASE_URL must be an HTTPS origin without credentials or query parameters");
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
