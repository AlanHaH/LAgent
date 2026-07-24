package com.adaptivelearning.shared.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PythonAiServiceClientTest {
    private static final String TOKEN = "test-python-internal-token-longer-than-32";
    private HttpServer server;
    private PythonAiServiceClient client;
    private final AtomicReference<String> receivedToken = new AtomicReference<>();

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

    private void json(HttpExchange exchange, String body) throws IOException {
        receivedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        respond(exchange, "application/json", body);
    }

    private void sse(HttpExchange exchange, String body) throws IOException {
        receivedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        respond(exchange, "text/event-stream", body);
    }

    private void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
