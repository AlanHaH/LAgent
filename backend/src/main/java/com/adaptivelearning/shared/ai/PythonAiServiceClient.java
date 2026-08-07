package com.adaptivelearning.shared.ai;

import com.adaptivelearning.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
            @Value("${app.ai-service.timeout:PT330S}") Duration timeout,
            @Value("${app.ai-service.model-name:}") String modelName) {
        this.json = json;
        this.configuredModel = modelName == null ? "" : modelName.trim();
        this.configured = enabled && baseUrl != null && !baseUrl.isBlank()
                && internalToken != null && internalToken.length() >= 32;
        if (!configured) {
            this.client = null;
            return;
        }
        ObjectMapper dateMapper = json.copy()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(Math.min(10, timeout.toSeconds())));
        requestFactory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(stripTrailingSlash(baseUrl))
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Token", internalToken)
                .messageConverters(converters -> converters.add(0,
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(dateMapper)))
                .build();
    }

    public boolean isConfigured() {
        return configured;
    }

    public String modelName() {
        return configuredModel.isBlank() ? "python-ai-service" : configuredModel;
    }

    public Map<String, Object> modelHealth() {
        requireConfigured();
        try {
            JsonNode data = post("/internal/v1/model/probe", Map.of());
            return json.convertValue(data, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (RuntimeException error) {
            throw translate(error, "Python AI model probe failed");
        }
    }

    public Map<String, Object> testRuntimeModel(RuntimeModelConfiguration configuration) {
        JsonNode data = post("/internal/v1/model/configuration:test", configuration);
        return json.convertValue(data, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    public Map<String, Object> applyRuntimeModel(RuntimeModelConfiguration configuration) {
        JsonNode data = put("/internal/v1/model/configuration", configuration);
        return json.convertValue(data, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    public Map<String, Object> runtimeModelStatus() {
        requireConfigured();
        try {
            JsonNode response = client.get().uri("/internal/v1/model/configuration")
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .retrieve().body(JsonNode.class);
            JsonNode data = requiredData(response);
            return json.convertValue(data, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (RuntimeException error) {
            throw translate(error, "Python AI runtime model status failed");
        }
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

    public OcrResult ocrPdf(Path path) {
        requireConfigured();
        try {
            MultipartBodyBuilder multipart = new MultipartBodyBuilder();
            multipart.part("file", new FileSystemResource(path))
                    .filename(path.getFileName().toString())
                    .contentType(MediaType.APPLICATION_PDF);
            MultiValueMap<String, org.springframework.http.HttpEntity<?>> body = multipart.build();
            JsonNode response = client.post().uri("/internal/v1/ocr/pdf")
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode data = requiredData(response);
            List<OcrPage> pages = new java.util.ArrayList<>();
            for (JsonNode item : data.path("pages")) {
                pages.add(new OcrPage(
                        item.path("pageNo").asInt(),
                        item.path("text").asText(),
                        item.path("confidence").asDouble()));
            }
            return new OcrResult(
                    pages,
                    data.path("pageCount").asInt(),
                    data.path("recognizedPages").asInt(),
                    data.path("characterCount").asInt(),
                    data.path("averageConfidence").asDouble(),
                    data.path("engine").asText("rapidocr"));
        } catch (RuntimeException error) {
            throw translate(error, "Python PDF OCR failed");
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
                    item.path("directionId").isIntegralNumber() ? item.path("directionId").asLong() : null,
                    textOrNull(item, "customDirection"), item.path("name").asText(),
                    item.path("type").asText(), item.path("description").asText(),
                    item.path("priority").asText(), item.path("durationDays").asInt(),
                    item.path("weeklyBudgetMinutes").asInt(), List.copyOf(criteria),
                    item.path("reason").asText(), List.copyOf(milestones)));
        }
        if (recommendations.isEmpty()) throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR);
        return new GoalRecommendationResult(List.copyOf(recommendations),
                data.path("promptVersion").asText("goal-recommendation-v2"));
    }

    public PlanRecommendationResult planRecommendations(PlanRecommendationRequest request) {
        JsonNode data = post("/internal/v1/plans/recommendations", request);
        List<PlanTaskItem> tasks = new java.util.ArrayList<>();
        for (JsonNode item : data.path("tasks")) {
            List<Long> kpIds = new java.util.ArrayList<>();
            item.path("knowledgePointIds").forEach(value -> kpIds.add(value.asLong()));
            List<String> criteria = new java.util.ArrayList<>();
            item.path("acceptanceCriteria").forEach(value -> criteria.add(value.asText()));
            List<Long> sourceChunkIds = new java.util.ArrayList<>();
            item.path("sourceChunkIds").forEach(value -> sourceChunkIds.add(value.asLong()));
            List<String> sourceQueries = new java.util.ArrayList<>();
            item.path("sourceQueries").forEach(value -> sourceQueries.add(value.asText()));
            tasks.add(new PlanTaskItem(
                    item.path("title").asText(),
                    item.path("taskType").asText(),
                    item.path("priority").asText("MEDIUM"),
                    item.path("estimatedMinutes").asInt(),
                    List.copyOf(kpIds),
                    List.copyOf(sourceChunkIds),
                    item.path("learningObjective").asText(item.path("title").asText()),
                    List.copyOf(sourceQueries),
                    List.copyOf(criteria),
                    item.path("reason").asText()));
        }
        if (tasks.isEmpty()) throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR);
        return new PlanRecommendationResult(List.copyOf(tasks),
                data.path("promptVersion").asText("plan-recommendation-v2-knowledge-rag"));
    }

    public LearningBlockContentResult learningBlockContent(LearningBlockContentRequest request) {
        JsonNode data = post("/internal/v1/learning-blocks/generate", request);
        List<Map<String, Object>> exercises = json.convertValue(
                data.path("exercises"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        List<Map<String, Object>> testQuestions = json.convertValue(
                data.path("testQuestions"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        List<String> sourceNotes = new java.util.ArrayList<>();
        data.path("sourceNotes").forEach(value -> sourceNotes.add(value.asText()));
        String material = data.path("materialMarkdown").asText();
        if (material.isBlank() || testQuestions.isEmpty())
            throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR);
        return new LearningBlockContentResult(material, exercises, testQuestions,
                List.copyOf(sourceNotes), data.path("promptVersion").asText("learning-block-v1-grounded"));
    }

    public TaskChatResult taskChat(TaskChatRequest request) {
        JsonNode data = post("/internal/v1/task-chats", request);
        List<TaskChatCitation> citations = new java.util.ArrayList<>();
        for (JsonNode item : data.path("citations")) {
            citations.add(new TaskChatCitation(
                    item.path("citationId").asText(),
                    item.path("sourceType").asText(),
                    item.path("chunkId").isNull() ? null : item.path("chunkId").asLong(),
                    nullableText(item, "fileName"),
                    nullableText(item, "title"),
                    nullableText(item, "url"),
                    item.path("quotePreview").asText("")));
        }
        return new TaskChatResult(
                data.path("answer").asText(),
                data.path("mode").asText(),
                List.copyOf(citations));
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
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
                               List<Long> allowedDocumentIds, List<Long> allowedVersionIds,
                               int topK, int candidateK) {
        JsonNode data = post("/internal/v1/rag/searches", Map.of(
                "userId", userId,
                "query", query,
                "allowedSpaceIds", allowedSpaceIds,
                "allowedDocumentIds", allowedDocumentIds,
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

    private JsonNode put(String path, Object body) {
        requireConfigured();
        try {
            JsonNode response = client.put().uri(path)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
            return requiredData(response);
        } catch (RuntimeException error) {
            throw translate(error, "Python AI runtime model update failed");
        }
    }

    private JsonNode stream(String path, Object body, BiConsumer<String, JsonNode> events) {
        requireConfigured();
        try {
            StreamResult result = client.post().uri(path)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(body)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return new StreamResult(null, mapStatus(response.getStatusCode().value()));
                        }
                        JsonNode completed = null;
                        ErrorCode failedCode = null;
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
                                        failedCode = mapPythonCode(payload.path("code").asText());
                                    } else {
                                        events.accept(event, payload);
                                        if ("message.completed".equals(event)) completed = payload;
                                    }
                                    event = null;
                                    data.setLength(0);
                                }
                            }
                        }
                        return new StreamResult(completed, failedCode);
                    });
            if (result == null) throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR);
            if (result.errorCode() != null) throw new AiModelException(result.errorCode());
            if (result.completed() == null) throw new AiModelException(ErrorCode.MODEL_PROVIDER_ERROR);
            return result.completed();
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
        AiModelException nestedModelError = findModelError(error);
        if (nestedModelError != null) return nestedModelError;
        ErrorCode code = error instanceof ResourceAccessException
                ? ErrorCode.MODEL_REQUEST_TIMEOUT : ErrorCode.MODEL_PROVIDER_ERROR;
        if (error instanceof RestClientResponseException response) {
            code = mapStatus(response.getStatusCode().value());
            try {
                JsonNode payload = json.readTree(response.getResponseBodyAsString());
                JsonNode providerError = payload.path("error");
                if (providerError.isObject()) {
                    code = mapPythonCode(providerError.path("code").asText());
                    String message = safeMessage(providerError.path("message").asText());
                    Map<String, Object> details = safeProviderDetails(providerError.path("details"));
                    log.warn("{}: status={} code={}", logMessage,
                            response.getStatusCode().value(), providerError.path("code").asText());
                    return new AiModelException(code, message, details, error);
                }
            } catch (Exception parseError) {
                log.debug("Could not parse Python AI error response", parseError);
            }
        }
        log.warn("{}: {} message={}", logMessage, error.getClass().getSimpleName(),
                safeMessage(error.getMessage()));
        log.debug(logMessage, error);
        return new AiModelException(code, error);
    }

    private AiModelException findModelError(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof AiModelException modelError) return modelError;
            current = current.getCause();
        }
        return null;
    }

    private ErrorCode mapStatus(int status) {
        return status == 429 ? ErrorCode.MODEL_QUOTA_EXCEEDED
                : status == 504 ? ErrorCode.MODEL_REQUEST_TIMEOUT : ErrorCode.MODEL_PROVIDER_ERROR;
    }

    private ErrorCode mapPythonCode(String code) {
        return switch (code) {
            case "AI_MODEL_TIMEOUT" -> ErrorCode.MODEL_REQUEST_TIMEOUT;
            case "AI_RATE_LIMITED", "AI_QUOTA_EXCEEDED" -> ErrorCode.MODEL_QUOTA_EXCEEDED;
            case "AI_DEPENDENCY_UNAVAILABLE" -> ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE;
            case "AI_OUTPUT_INVALID" -> ErrorCode.MODEL_OUTPUT_INVALID;
            case "OCR_NO_TEXT" -> ErrorCode.DOCUMENT_NO_EXTRACTABLE_TEXT;
            case "OCR_FILE_TOO_LARGE", "OCR_PAGE_LIMIT_EXCEEDED" ->
                    ErrorCode.DOCUMENT_OCR_LIMIT_EXCEEDED;
            case "OCR_DEPENDENCY_UNAVAILABLE" -> ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE;
            case "OCR_TIMEOUT", "OCR_PDF_INVALID", "OCR_PAGE_TOO_LARGE" ->
                    ErrorCode.DOCUMENT_OCR_FAILED;
            default -> ErrorCode.MODEL_PROVIDER_ERROR;
        };
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) return null;
        String compact = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return compact.length() <= 200 ? compact : compact.substring(0, 200);
    }

    private Map<String, Object> safeProviderDetails(JsonNode details) {
        if (!details.isObject()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        copySafeDetail(details, safe, "providerStatus");
        copySafeDetail(details, safe, "providerCode");
        copySafeDetail(details, safe, "providerType");
        return safe;
    }

    private void copySafeDetail(JsonNode source, Map<String, Object> target, String field) {
        JsonNode value = source.path(field);
        if (value.isIntegralNumber()) {
            target.put(field, value.longValue());
        } else if (value.isTextual() && !value.asText().isBlank()) {
            String text = value.asText();
            target.put(field, text.length() <= 80 ? text : text.substring(0, 80));
        }
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

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String stripTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private record StreamResult(JsonNode completed, ErrorCode errorCode) { }

    public record OcrPage(int pageNo, String text, double confidence) { }
    public record OcrResult(List<OcrPage> pages, int pageCount, int recognizedPages,
                            int characterCount, double averageConfidence, String engine) { }

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
    public record RuntimeModelConfiguration(String provider, String baseUrl, String apiKey,
                                            String modelName, int timeoutSeconds,
                                            int maxOutputTokens, String thinking,
                                            boolean allowHttp) { }
    public record GoalDirectionContext(Long id, String name, String currentStage, boolean primary) { }
    public record GoalRecommendationRequest(long userId, LocalDate today, long profileVersionId,
                                            int profileVersionNo, LocalDate planStartDate,
                                            LocalDate planEndDate, String backgroundText,
                                            List<GoalDirectionContext> directions, Object preference,
                                            int weeklyAvailableMinutes, List<String> existingGoalNames,
                                            int count) { }
    public record GoalRecommendationItem(Long directionId, String customDirection, String name, String type, String description,
                                         String priority, int durationDays, int weeklyBudgetMinutes,
                                         List<String> successCriteria, String reason,
                                         List<String> milestones) { }
    public record GoalRecommendationResult(List<GoalRecommendationItem> recommendations,
                                           String promptVersion) { }
    public record PlanKnowledgePoint(long id, String name) { }
    public record PlanRecommendationRequest(long userId, String goalName, String directionName,
                                            String currentStage, LocalDate planStartDate,
                                            LocalDate planEndDate, String backgroundText,
                                            List<PlanKnowledgePoint> knowledgePoints,
                                            List<Long> allowedSpaceIds,
                                            List<Long> allowedDocumentVersionIds,
                                            int knowledgeTopK,
                                            String userRequirement, int weeklyAvailableMinutes,
                                            boolean explorationMode,
                                            int count) { }
    public record PlanTaskItem(String title, String taskType, String priority, int estimatedMinutes,
                               List<Long> knowledgePointIds, List<Long> sourceChunkIds,
                               String learningObjective, List<String> sourceQueries,
                               List<String> acceptanceCriteria,
                               String reason) { }
    public record PlanRecommendationResult(List<PlanTaskItem> tasks, String promptVersion) { }
    public record LearningBlockSource(String sourceType, String title, String url,
                                      String quotePreview) { }
    public record LearningBlockContentRequest(long userId, String title, String objective,
                                              String directionName, String currentStage,
                                              boolean explorationRequired,
                                              List<LearningBlockSource> sources,
                                              List<String> sourceQueries) { }
    public record LearningBlockContentResult(String materialMarkdown,
                                             List<Map<String, Object>> exercises,
                                             List<Map<String, Object>> testQuestions,
                                             List<String> sourceNotes,
                                             String promptVersion) { }
    public record TaskChatTurn(String role, String content) { }
    public record TaskChatRequest(long userId, String taskTitle, String taskType, String message,
                                  List<TaskChatTurn> history, List<Long> allowedSpaceIds,
                                  int topK) { }
    public record TaskChatCitation(String citationId, String sourceType, Long chunkId,
                                   String fileName, String title, String url,
                                   String quotePreview) { }
    public record TaskChatResult(String answer, String mode, List<TaskChatCitation> citations) { }
}
