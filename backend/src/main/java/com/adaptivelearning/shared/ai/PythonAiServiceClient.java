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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Internal-only client for the Python model, embedding and RAG service. */
@Slf4j
@Service
public class PythonAiServiceClient {
    private final boolean configured;
    private final String configuredModel;
    private final ObjectMapper json;
    private final RestClient client;

    public PythonAiServiceClient(
            ObjectMapper json,
            @Value("${app.ai-service.enabled:false}") boolean enabled,
            @Value("${app.ai-service.base-url:http://127.0.0.1:8090}") String baseUrl,
            @Value("${app.ai-service.internal-token:}") String internalToken,
            @Value("${app.ai-service.timeout:PT65S}") Duration timeout,
            @Value("${app.ai-service.model-name:}") String modelName) {
        this.json = json;
        this.configuredModel = modelName == null ? "" : modelName.trim();
        this.configured = enabled && baseUrl != null && !baseUrl.isBlank()
                && internalToken != null && internalToken.length() >= 32;
        if (!configured) {
            this.client = null;
            return;
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(Math.min(10, timeout.toSeconds())));
        requestFactory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(stripTrailingSlash(baseUrl))
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    public boolean isConfigured() {
        return configured;
    }

    public String modelName() {
        return configuredModel.isBlank() ? "python-ai-service" : configuredModel;
    }

    public AiModelClient.Completion complete(String systemPrompt, String userPrompt) {
        JsonNode data = post("/internal/v1/model/completions", Map.of(
                "systemPrompt", systemPrompt,
                "userPrompt", userPrompt));
        return completion(data);
    }

    public AiModelClient.Completion completeStreaming(String systemPrompt, String userPrompt,
                                                       Consumer<String> onDelta) {
        JsonNode completed = stream("/internal/v1/model/completions:stream", Map.of(
                "systemPrompt", systemPrompt,
                "userPrompt", userPrompt), (event, data) -> {
            if ("message.delta".equals(event) && data.path("delta").isTextual()) {
                onDelta.accept(data.path("delta").asText());
            }
        });
        return completion(completed);
    }

    public AiModelClient.Completion profileTurnStreaming(long userId, String sessionId, Object currentDraft,
                                                          List<?> directionCatalog, List<?> recentConversation,
                                                          String latestUserMessage, Consumer<String> onDelta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("sessionId", sessionId);
        body.put("locale", "zh-CN");
        body.put("today", LocalDate.now().toString());
        body.put("currentDraft", currentDraft);
        body.put("directionCatalog", directionCatalog);
        body.put("recentConversation", recentConversation);
        body.put("latestUserMessage", latestUserMessage);
        JsonNode completed = stream("/internal/v1/profile/interview-turns:stream", body, (event, data) -> {
            if ("message.delta".equals(event) && data.path("delta").isTextual()) {
                onDelta.accept(data.path("delta").asText());
            }
        });
        try {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("assistantMessage", completed.path("assistantMessage"));
            output.put("updates", completed.path("updates"));
            String content = json.writeValueAsString(output);
            JsonNode run = completed.path("modelRun");
            return new AiModelClient.Completion(content,
                    integer(run, "prompt_tokens", "promptTokens"),
                    integer(run, "completion_tokens", "completionTokens"),
                    longValue(run, "latency_ms", "latencyMs"));
        } catch (Exception error) {
            throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR, error);
        }
    }

    public GoalRecommendationResult goalRecommendations(GoalRecommendationRequest request) {
        JsonNode data = post("/internal/v1/goals/recommendations", request);
        List<GoalRecommendationItem> recommendations = new java.util.ArrayList<>();
        for (JsonNode item : data.path("recommendations")) {
            List<String> criteria = new java.util.ArrayList<>();
            item.path("successCriteria").forEach(value -> criteria.add(value.asText()));
            List<String> milestones = new java.util.ArrayList<>();
            item.path("milestones").forEach(value -> milestones.add(value.asText()));
            recommendations.add(new GoalRecommendationItem(
                    item.path("directionId").asLong(), item.path("name").asText(),
                    item.path("type").asText(), item.path("description").asText(),
                    item.path("priority").asText(), item.path("durationDays").asInt(),
                    item.path("weeklyBudgetMinutes").asInt(), List.copyOf(criteria),
                    item.path("reason").asText(), List.copyOf(milestones)));
        }
        if (recommendations.isEmpty()) throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR);
        return new GoalRecommendationResult(List.copyOf(recommendations),
                data.path("promptVersion").asText("goal-recommendation-v1"));
    }

    public IndexResult index(IndexRequest request) {
        JsonNode data = post("/internal/v1/rag/indexes", request);
        return new IndexResult(
                data.path("indexedChunks").asInt(),
                data.path("embeddingModel").asText(),
                data.path("embeddingDimension").asInt(),
                data.path("collection").asText(),
                data.path("degraded").asBoolean());
    }

    public int deleteIndex(long ownerUserId, long documentVersionId) {
        requireConfigured();
        try {
            JsonNode response = client.delete()
                    .uri(uri -> uri.path("/internal/v1/rag/indexes/{versionId}")
                            .queryParam("ownerUserId", ownerUserId).build(documentVersionId))
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .retrieve().body(JsonNode.class);
            return requiredData(response).path("deletedPoints").asInt();
        } catch (RuntimeException error) {
            throw translate(error, "Python index delete failed");
        }
    }

    public SearchResult search(long userId, String query, List<Long> allowedSpaceIds,
                               List<Long> allowedVersionIds, int topK, int candidateK) {
        JsonNode data = post("/internal/v1/rag/searches", Map.of(
                "userId", userId,
                "query", query,
                "allowedSpaceIds", allowedSpaceIds,
                "allowedDocumentVersionIds", allowedVersionIds,
                "topK", topK,
                "candidateK", candidateK));
        List<SearchHit> hits = new java.util.ArrayList<>();
        for (JsonNode item : data.path("hits")) {
            hits.add(new SearchHit(
                    item.path("citationId").asText(),
                    item.path("chunkId").asLong(),
                    item.path("documentId").asLong(),
                    item.path("documentVersionId").asLong(),
                    item.path("score").asDouble()));
        }
        return new SearchResult(data.path("evidenceSufficient").asBoolean(), List.copyOf(hits),
                data.path("latencyMs").asLong(), data.path("degraded").asBoolean());
    }

    public RagAnswer answer(long userId, String question, boolean sufficient, List<RagEvidence> evidence) {
        return answerStreaming(userId, question, sufficient, evidence, ignored -> { });
    }

    public RagAnswer answerStreaming(long userId, String question, boolean sufficient,
                                     List<RagEvidence> evidence, Consumer<String> onDelta) {
        JsonNode completed = stream("/internal/v1/rag/answers:stream", Map.of(
                "userId", userId,
                "question", question,
                "evidenceSufficient", sufficient,
                "evidence", evidence), (event, data) -> {
            if ("message.delta".equals(event) && data.path("delta").isTextual()) {
                onDelta.accept(data.path("delta").asText());
            }
        });
        List<String> citations = new java.util.ArrayList<>();
        completed.path("citationIds").forEach(item -> citations.add(item.asText()));
        JsonNode run = completed.path("modelRun");
        return new RagAnswer(
                completed.path("content").asText(),
                completed.path("answerMode").asText(),
                List.copyOf(citations),
                run.path("promptTokens").isIntegralNumber() ? run.path("promptTokens").intValue() : null,
                run.path("completionTokens").isIntegralNumber() ? run.path("completionTokens").intValue() : null,
                run.path("latencyMs").asLong(0),
                completed.path("replacementRequired").asBoolean(false));
    }

    private JsonNode post(String path, Object body) {
        requireConfigured();
        try {
            JsonNode response = client.post().uri(path)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
            return requiredData(response);
        } catch (RuntimeException error) {
            throw translate(error, "Python AI request failed");
        }
    }

    private JsonNode stream(String path, Object body, BiConsumer<String, JsonNode> events) {
        requireConfigured();
        try {
            return client.post().uri(path)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(body)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new AiModelException(mapStatus(response.getStatusCode().value()));
                        }
                        JsonNode completed = null;
                        String event = null;
                        StringBuilder data = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                                response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.startsWith("event:")) event = line.substring(6).trim();
                                else if (line.startsWith("data:")) data.append(line.substring(5).trim());
                                else if (line.isBlank() && event != null) {
                                    JsonNode payload = data.isEmpty() ? json.createObjectNode() : json.readTree(data.toString());
                                    if ("message.failed".equals(event)) {
                                        throw new AiModelException(mapPythonCode(payload.path("code").asText()));
                                    }
                                    events.accept(event, payload);
                                    if ("message.completed".equals(event)) completed = payload;
                                    event = null;
                                    data.setLength(0);
                                }
                            }
                        }
                        if (completed == null) throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR);
                        return completed;
                    });
        } catch (RuntimeException error) {
            throw translate(error, "Python AI stream failed");
        }
    }

    private AiModelClient.Completion completion(JsonNode data) {
        String content = data.path("content").asText().trim();
        if (content.isBlank() || content.length() > 10_000) {
            throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR);
        }
        return new AiModelClient.Completion(content,
                integer(data, "prompt_tokens", "promptTokens"),
                integer(data, "completion_tokens", "completionTokens"),
                longValue(data, "latency_ms", "latencyMs"));
    }

    private JsonNode requiredData(JsonNode response) {
        if (response == null || !response.path("success").asBoolean() || !response.path("data").isObject()) {
            throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR);
        }
        return response.path("data");
    }

    private AiModelException translate(RuntimeException error, String logMessage) {
        if (error instanceof AiModelException modelError) return modelError;
        ErrorCode code = error instanceof ResourceAccessException
                ? ErrorCode.MODEL_REQUEST_TIMEOUT : ErrorCode.MODEL_PROVIDER_ERROR;
        if (error instanceof RestClientResponseException response) code = mapStatus(response.getStatusCode().value());
        log.warn("{}: {}", logMessage, error.getClass().getSimpleName());
        return new AiModelException(code, error);
    }

    private ErrorCode mapStatus(int status) {
        return status == 429 ? ErrorCode.MODEL_QUOTA_EXCEEDED
                : status == 504 ? ErrorCode.MODEL_REQUEST_TIMEOUT : ErrorCode.MODEL_PROVIDER_ERROR;
    }

    private ErrorCode mapPythonCode(String code) {
        return switch (code) {
            case "AI_MODEL_TIMEOUT" -> ErrorCode.MODEL_REQUEST_TIMEOUT;
            case "AI_RATE_LIMITED" -> ErrorCode.MODEL_QUOTA_EXCEEDED;
            case "AI_DEPENDENCY_UNAVAILABLE" -> ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE;
            default -> ErrorCode.MODEL_PROVIDER_ERROR;
        };
    }

    private void requireConfigured() {
        if (!configured) throw new AiModelException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE);
    }

    private Integer integer(JsonNode node, String snake, String camel) {
        JsonNode value = node.has(snake) ? node.path(snake) : node.path(camel);
        return value.isIntegralNumber() ? value.intValue() : null;
    }

    private long longValue(JsonNode node, String snake, String camel) {
        JsonNode value = node.has(snake) ? node.path(snake) : node.path(camel);
        return value.asLong(0);
    }

    private static String stripTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    public record IndexChunk(long chunkId, int chunkNo, String text, String textHash,
                             List<String> titlePath, Integer pageFrom, Integer pageTo, String language) { }
    public record IndexRequest(String indexRequestId, long ownerUserId, long spaceId, long documentId,
                               long documentVersionId, String visibility, List<IndexChunk> chunks) { }
    public record IndexResult(int indexedChunks, String embeddingModel, int embeddingDimension,
                              String collection, boolean degraded) { }
    public record SearchHit(String citationId, long chunkId, long documentId,
                            long documentVersionId, double score) { }
    public record SearchResult(boolean evidenceSufficient, List<SearchHit> hits,
                               long latencyMs, boolean degraded) { }
    public record RagEvidence(String citationId, long chunkId, long documentId, long documentVersionId,
                              String fileName, String quotePreview, List<String> titlePath,
                              Integer pageFrom, Integer pageTo) { }
    public record RagAnswer(String content, String answerMode, List<String> citationIds,
                            Integer inputTokens, Integer outputTokens, long latencyMs,
                            boolean replacementRequired) { }
    public record GoalDirectionContext(Long id, String name, String currentStage, boolean primary) { }
    public record GoalRecommendationRequest(long userId, LocalDate today, long profileVersionId,
                                            int profileVersionNo, LocalDate planStartDate,
                                            LocalDate planEndDate, String backgroundText,
                                            List<GoalDirectionContext> directions, Object preference,
                                            int weeklyAvailableMinutes, List<String> existingGoalNames,
                                            int count) { }
    public record GoalRecommendationItem(long directionId, String name, String type, String description,
                                         String priority, int durationDays, int weeklyBudgetMinutes,
                                         List<String> successCriteria, String reason,
                                         List<String> milestones) { }
    public record GoalRecommendationResult(List<GoalRecommendationItem> recommendations,
                                           String promptVersion) { }
}
