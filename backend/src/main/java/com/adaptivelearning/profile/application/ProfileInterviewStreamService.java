package com.adaptivelearning.profile.application;

import com.adaptivelearning.shared.ai.AiStreamCancelledException;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class ProfileInterviewStreamService {
    private static final long STREAM_TIMEOUT_MS = 90_000L;

    private final ProfileInterviewService interviews;
    private final Executor executor;

    public ProfileInterviewStreamService(ProfileInterviewService interviews,
                                         @Qualifier("profileInterviewExecutor") Executor executor) {
        this.interviews = interviews;
        this.executor = executor;
    }

    public SseEmitter stream(long userId, String requestId, String clientIp, String sessionId,
                             String content, int version) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean disconnected = new AtomicBoolean(false);
        emitter.onCompletion(() -> disconnected.set(true));
        emitter.onError(error -> disconnected.set(true));
        emitter.onTimeout(() -> {
            disconnected.set(true);
            emitter.complete();
        });

        try {
            executor.execute(() -> generate(emitter, disconnected, userId, requestId, clientIp,
                    sessionId, content, version));
        } catch (RejectedExecutionException e) {
            throw new BusinessException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE,
                    "画像访谈请求较多，请稍后重试");
        }
        return emitter;
    }

    private void generate(SseEmitter emitter, AtomicBoolean disconnected, long userId, String requestId,
                          String clientIp, String sessionId, String content, int version) {
        try {
            send(emitter, disconnected, "message.started", Map.of("status", "GENERATING"));
            ProfileInterviewService.SessionView completed = interviews.addMessageStreaming(userId,
                    requestId, clientIp, sessionId, content, version,
                    delta -> send(emitter, disconnected, "message.delta", Map.of("delta", delta)),
                    replacement -> send(emitter, disconnected, "message.replace",
                            Map.of("content", replacement)));
            send(emitter, disconnected, "message.completed", completed);
            emitter.complete();
        } catch (AiStreamCancelledException ignored) {
            log.debug("Profile interview stream was cancelled for session {}", sessionId);
        } catch (BusinessException e) {
            fail(emitter, disconnected, e.getCode().name(), e.getMessage());
        } catch (Exception e) {
            log.warn("Profile interview stream failed for session {}: {}", sessionId,
                    e.getClass().getSimpleName());
            fail(emitter, disconnected, ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE.name(),
                    "画像访谈生成失败，请稍后重试");
        }
    }

    private void fail(SseEmitter emitter, AtomicBoolean disconnected, String code, String message) {
        if (disconnected.get()) return;
        try {
            emitter.send(SseEmitter.event().name("message.failed")
                    .data(Map.of("code", code, "message", message)));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            disconnected.set(true);
        }
    }

    private void send(SseEmitter emitter, AtomicBoolean disconnected, String event, Object data) {
        if (disconnected.get()) throw new AiStreamCancelledException();
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            disconnected.set(true);
            throw new AiStreamCancelledException(e);
        }
    }
}
