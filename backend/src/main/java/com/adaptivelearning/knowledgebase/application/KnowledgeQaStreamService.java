package com.adaptivelearning.knowledgebase.application;

import com.adaptivelearning.shared.ai.AiModelException;
import com.adaptivelearning.shared.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class KnowledgeQaStreamService {
    private static final long TIMEOUT_MS = 70_000L;
    private final KnowledgeQaService qa;
    private final Executor executor;

    public KnowledgeQaStreamService(KnowledgeQaService qa,
                                    @Qualifier("knowledgeQaExecutor") Executor executor) {
        this.qa = qa;
        this.executor = executor;
    }

    public SseEmitter stream(String sessionId, String question) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        AtomicBoolean disconnected = new AtomicBoolean();
        emitter.onCompletion(() -> disconnected.set(true));
        emitter.onTimeout(() -> disconnected.set(true));
        emitter.onError(error -> disconnected.set(true));
        send(emitter, disconnected, "message.started", Map.of("status", "GENERATING"));

        SecurityContext captured = SecurityContextHolder.createEmptyContext();
        captured.setAuthentication(SecurityContextHolder.getContext().getAuthentication());
        Runnable work = () -> generate(emitter, disconnected, sessionId, question);
        executor.execute(new DelegatingSecurityContextRunnable(work, captured));
        return emitter;
    }

    private void generate(SseEmitter emitter, AtomicBoolean disconnected,
                          String sessionId, String question) {
        try {
            KnowledgeQaService.AnswerResult answer = qa.askStreaming(
                    sessionId,
                    question,
                    delta -> send(emitter, disconnected, "message.delta", Map.of("delta", delta)),
                    content -> send(emitter, disconnected, "message.replaced", Map.of("content", content)),
                    citation -> send(emitter, disconnected, "citation.ready", citation));
            send(emitter, disconnected, "message.completed", answer);
            if (!disconnected.get()) emitter.complete();
        } catch (AiModelException error) {
            log.warn("Knowledge QA stream failed: {}", error.getCode());
            send(emitter, disconnected, "message.failed", Map.of(
                    "code", error.getCode().name(), "message", aiMessage(error.getCode())));
            if (!disconnected.get()) emitter.complete();
        } catch (RuntimeException error) {
            log.warn("Knowledge QA stream failed: {}", error.getClass().getSimpleName());
            send(emitter, disconnected, "message.failed", Map.of(
                    "code", "KNOWLEDGE_QA_FAILED", "message", "回答生成失败"));
            if (!disconnected.get()) emitter.complete();
        }
    }

    private static String aiMessage(ErrorCode code) {
        return switch (code) {
            case SERVICE_TEMPORARILY_UNAVAILABLE -> "AI 服务未启动或暂不可用，请启动 AI 服务后重试";
            case MODEL_PROVIDER_ERROR -> "AI 模型服务返回错误，请稍后重试";
            case MODEL_REQUEST_TIMEOUT -> "AI 模型服务响应超时，请稍后重试";
            case MODEL_QUOTA_EXCEEDED -> "AI 模型调用额度已用尽";
            default -> "AI 服务暂时不可用";
        };
    }

    private void send(SseEmitter emitter, AtomicBoolean disconnected, String event, Object data) {
        if (disconnected.get()) return;
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException error) {
            disconnected.set(true);
        }
    }
}
