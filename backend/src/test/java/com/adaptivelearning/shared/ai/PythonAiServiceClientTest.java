package com.adaptivelearning.shared.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PythonAiServiceClientTest {
    private static final String TOKEN = "test-python-internal-token-longer-than-32";
    private HttpServer server;
    private PythonAiServiceClient client;
    private final AtomicReference<String> receivedToken = new AtomicReference<>();
    private final AtomicReference<String> receivedPlanBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/model/completions", exchange -> json(exchange, """
                {"success":true,"data":{"content":"完整回答","model":"fake","provider":"test",
                "prompt_tokens":11,"completion_tokens":7,"latency_ms":15},"requestId":"r1"}
                """));
        server.createContext("/internal/v1/model/completions:stream", exchange -> sse(exchange, """
                event: message.started
                data: {"requestId":"r2"}

                event: message.delta
                data: {"delta":"流式"}

                event: message.delta
                data: {"delta":"回答"}

                event: message.completed
                data: {"content":"流式回答","model":"fake","provider":"test","prompt_tokens":9,"completion_tokens":4,"latency_ms":20}

                """));
        server.createContext("/internal/v1/model/configuration:test", exchange -> respond(exchange, 503,
                "application/json", """
                {"success":false,"error":{"code":"AI_PROVIDER_AUTH_FAILED",
                "message":"模型 API 密钥无效或无权访问当前模型","retryable":false,
                "details":{"providerStatus":401,"providerCode":"invalid_api_key",
                "providerException":"AuthenticationError"}}}
                """));
        server.createContext("/internal/v1/profile/interview-turns:stream", exchange -> sse(exchange, """
                event: message.started
                data: {"requestId":"profile-1"}

                event: message.failed
                data: {"code":"AI_OUTPUT_INVALID","message":"画像模型输出不符合结构要求","retryable":true,"details":{"validationErrors":1}}

                """));
        server.createContext("/internal/v1/ocr/pdf", exchange -> {
            exchange.getRequestBody().readAllBytes();
            json(exchange, """
                    {"success":true,"data":{"pages":[
                    {"pageNo":1,"text":"第一页识别文字","confidence":0.96}],
                    "pageCount":1,"recognizedPages":1,"characterCount":8,
                    "averageConfidence":0.96,"engine":"rapidocr-onnxruntime"}}
                    """);
        });
        server.createContext("/internal/v1/plans/recommendations", exchange -> {
            receivedPlanBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, """
                    {"success":true,"data":{"tasks":[{
                    "title":"Learn A before B","taskType":"LEARNING","priority":"HIGH",
                    "estimatedMinutes":30,"knowledgePointIds":[1,2],"sourceChunkIds":[],
                    "learningObjective":"Learn A before B","sourceQueries":[],
                    "acceptanceCriteria":["Explain A before B"],"reason":"Preserve prerequisites"
                    }],"promptVersion":"plan-recommendation-v4-prerequisites"}}
                    """);
        });
        server.start();
        client = new PythonAiServiceClient(new ObjectMapper().findAndRegisterModules(), true,
                "http://127.0.0.1:" + server.getAddress().getPort(), TOKEN,
                Duration.ofSeconds(5), "fake");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void parsesEnvelopeAndSendsInternalToken() {
        AiModelClient.Completion completion = client.complete("system", "user");

        assertThat(completion.content()).isEqualTo("完整回答");
        assertThat(completion.inputTokens()).isEqualTo(11);
        assertThat(receivedToken.get()).isEqualTo(TOKEN);
    }

    @Test
    void relaysSseDeltasAndReturnsValidatedCompletion() {
        List<String> deltas = new ArrayList<>();

        AiModelClient.Completion completion = client.completeStreaming("system", "user", deltas::add);

        assertThat(deltas).containsExactly("流式", "回答");
        assertThat(completion.content()).isEqualTo("流式回答");
        assertThat(completion.outputTokens()).isEqualTo(4);
    }

    @Test
    void preservesSafeProviderErrorForAdminModelTest() {
        PythonAiServiceClient.RuntimeModelConfiguration configuration =
                new PythonAiServiceClient.RuntimeModelConfiguration(
                        "openai-compatible", "https://example.invalid/v1", "secret",
                        "test-model", 30, 512, "disabled", false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.testRuntimeModel(configuration))
                .isInstanceOfSatisfying(AiModelException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(
                            com.adaptivelearning.shared.exception.ErrorCode.MODEL_PROVIDER_ERROR);
                    assertThat(error.getUserMessage()).isEqualTo("模型 API 密钥无效或无权访问当前模型");
                    assertThat(error.getDetails()).containsEntry("providerStatus", 401L)
                            .containsEntry("providerCode", "invalid_api_key")
                            .containsEntry("pythonCode", "AI_PROVIDER_AUTH_FAILED")
                            .doesNotContainKey("providerException");
                });
    }

    @Test
    void mapsInvalidProfileOutputSeparatelyFromProviderFailure() {
        assertThatThrownBy(() -> client.profileTurnStreaming(
                1L, "session-1", Map.of(), List.of(), List.of(), "我是初学者", ignored -> { }))
                .isInstanceOfSatisfying(AiModelException.class, error ->
                        {
                            assertThat(error.getCode()).isEqualTo(
                                    com.adaptivelearning.shared.exception.ErrorCode.MODEL_OUTPUT_INVALID);
                            assertThat(error.getDetails()).containsEntry("pythonCode", "AI_OUTPUT_INVALID")
                                    .doesNotContainKey("validationErrors");
                        });
    }

    @Test
    void uploadsPdfForOcrAndParsesPageMetadata(@TempDir Path tempDir) throws IOException {
        Path pdf = tempDir.resolve("scan.pdf");
        Files.writeString(pdf, "%PDF-1.7 test", StandardCharsets.UTF_8);

        PythonAiServiceClient.OcrResult result = client.ocrPdf(pdf);

        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.recognizedPages()).isEqualTo(1);
        assertThat(result.pages()).singleElement().satisfies(page -> {
            assertThat(page.pageNo()).isEqualTo(1);
            assertThat(page.text()).isEqualTo("第一页识别文字");
        });
        assertThat(receivedToken.get()).isEqualTo(TOKEN);
    }

    @Test
    void serializesKnowledgeDependenciesAndSatisfiedPrerequisites() throws Exception {
        PythonAiServiceClient.PlanRecommendationRequest request =
                new PythonAiServiceClient.PlanRecommendationRequest(
                        1L, "Learn graphs", "Computer science", "BEGINNER",
                        LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-30"), null,
                        List.of(new PythonAiServiceClient.PlanKnowledgePoint(1L, "A"),
                                new PythonAiServiceClient.PlanKnowledgePoint(2L, "B")),
                        List.of(new PythonAiServiceClient.KnowledgeDependency(1L, 2L)),
                        List.of(1L), List.of(), List.of(), 12,
                        null, 600, false, 2);

        PythonAiServiceClient.PlanRecommendationResult result = client.planRecommendations(request);

        var body = new ObjectMapper().readTree(receivedPlanBody.get());
        assertThat(body.path("knowledgeDependencies").path(0).path("predecessorId").asLong()).isEqualTo(1L);
        assertThat(body.path("knowledgeDependencies").path(0).path("successorId").asLong()).isEqualTo(2L);
        assertThat(body.path("satisfiedPrerequisiteIds").path(0).asLong()).isEqualTo(1L);
        assertThat(result.tasks()).singleElement().satisfies(task ->
                assertThat(task.knowledgePointIds()).containsExactly(1L, 2L));
    }

    private void json(HttpExchange exchange, String body) throws IOException {
        receivedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        respond(exchange, "application/json", body);
    }

    private void sse(HttpExchange exchange, String body) throws IOException {
        receivedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        exchange.getRequestBody().readAllBytes();
        respond(exchange, "text/event-stream", body);
    }

    private void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        respond(exchange, 200, contentType, body);
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
