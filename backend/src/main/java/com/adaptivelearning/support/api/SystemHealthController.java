package com.adaptivelearning.support.api;

import com.adaptivelearning.shared.api.ApiResponse;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemHealthController {
    private static final Duration AI_HEALTH_CACHE_TTL = Duration.ofSeconds(60);

    private final PythonAiServiceClient pythonAi;
    private final Object aiHealthLock = new Object();
    private volatile Map<String, Object> cachedAiHealth = Map.of();
    private volatile Instant cachedAiHealthAt = Instant.EPOCH;

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "status", "UP",
                "checkedAt", Instant.now().toString()));
    }

    @GetMapping("/ai-health")
    public ApiResponse<Map<String, Object>> aiHealth() {
        Instant now = Instant.now();
        if (!cachedAiHealth.isEmpty() && now.isBefore(cachedAiHealthAt.plus(AI_HEALTH_CACHE_TTL))) {
            return ApiResponse.ok(cachedAiHealth);
        }
        synchronized (aiHealthLock) {
            now = Instant.now();
            if (!cachedAiHealth.isEmpty() && now.isBefore(cachedAiHealthAt.plus(AI_HEALTH_CACHE_TTL))) {
                return ApiResponse.ok(cachedAiHealth);
            }
            cachedAiHealth = checkAiHealth(now);
            cachedAiHealthAt = now;
            return ApiResponse.ok(cachedAiHealth);
        }
    }

    private Map<String, Object> checkAiHealth(Instant checkedAt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", "PYTHON_AI");
        result.put("checkedAt", checkedAt.toString());
        if (!pythonAi.isConfigured()) {
            result.put("status", "DOWN");
            result.put("reason", "NOT_CONFIGURED");
            return Map.copyOf(result);
        }
        try {
            Map<String, Object> health = pythonAi.modelHealth();
            result.put("status", "UP".equals(String.valueOf(health.get("status"))) ? "UP" : "DOWN");
            if (health.get("model") != null) result.put("model", health.get("model"));
            if (health.get("latencyMs") != null) result.put("latencyMs", health.get("latencyMs"));
        } catch (RuntimeException error) {
            result.put("status", "DOWN");
            result.put("reason", "MODEL_PROBE_FAILED");
        }
        return Map.copyOf(result);
    }
}
