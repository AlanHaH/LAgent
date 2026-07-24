package com.adaptivelearning.shared.ratelimit;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RedisRateLimiter {
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redis;
    private final boolean redisEnabled;
    private final int loginLimit;
    private final Duration loginWindow;
    private final int modelLimit;
    private final Duration modelWindow;
    private final int verificationLimit;
    private final Duration verificationWindow;
    private final ConcurrentHashMap<String, LocalCounter> fallback = new ConcurrentHashMap<>();

    public RedisRateLimiter(
            StringRedisTemplate redis,
            @Value("${app.redis.enabled:true}") boolean redisEnabled,
            @Value("${app.rate-limit.login.max-requests:20}") int loginLimit,
            @Value("${app.rate-limit.login.window:PT1M}") Duration loginWindow,
            @Value("${app.rate-limit.model.max-requests:20}") int modelLimit,
            @Value("${app.rate-limit.model.window:PT1M}") Duration modelWindow,
            @Value("${app.rate-limit.verification.max-requests:5}") int verificationLimit,
            @Value("${app.rate-limit.verification.window:PT10M}") Duration verificationWindow) {
        this.redis = redis;
        this.redisEnabled = redisEnabled;
        this.loginLimit = loginLimit;
        this.loginWindow = loginWindow;
        this.modelLimit = modelLimit;
        this.modelWindow = modelWindow;
        this.verificationLimit = verificationLimit;
        this.verificationWindow = verificationWindow;
    }

    public void requireLoginAllowed(String subject) {
        requireAllowed("login", subject, loginLimit, loginWindow);
    }

    public void requireModelAllowed(long userId) {
        requireAllowed("model", Long.toString(userId), modelLimit, modelWindow);
    }

    public void requireVerificationAllowed(String subject) {
        requireAllowed("verification", subject, verificationLimit, verificationWindow);
    }

    private void requireAllowed(String scope, String subject, int limit, Duration window) {
        String key = "adaptive-learning:rate-limit:" + scope + ":" + sha256(subject);
        long count = increment(key, window);
        if (count > limit) {
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED,
                    "请求过于频繁，请稍后重试",
                    Map.of("scope", scope, "retryAfterSeconds", window.toSeconds()));
        }
    }

    private long increment(String key, Duration window) {
        if (redisEnabled) {
            try {
                Long count = redis.execute(INCREMENT_SCRIPT, List.of(key), Long.toString(window.toMillis()));
                if (count != null) return count;
            } catch (RuntimeException ignored) {
                // Redis is an optimization and protection layer, not a source of truth.
            }
        }
        return incrementFallback(key, window);
    }

    private long incrementFallback(String key, Duration window) {
        long now = System.currentTimeMillis();
        if (fallback.size() > 10_000) {
            fallback.entrySet().removeIf(entry -> entry.getValue().resetAt() <= now);
        }
        return fallback.compute(key, (ignored, current) -> {
            if (current == null || current.resetAt() <= now) {
                return new LocalCounter(1, now + window.toMillis());
            }
            return new LocalCounter(current.count() + 1, current.resetAt());
        }).count();
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record LocalCounter(long count, long resetAt) {}
}
