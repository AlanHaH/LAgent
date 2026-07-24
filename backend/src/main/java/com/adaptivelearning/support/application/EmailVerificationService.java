package com.adaptivelearning.support.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class EmailVerificationService {
    private static final DefaultRedisScript<Long> VERIFY_SCRIPT = new DefaultRedisScript<>("""
            local stored = redis.call('GET', KEYS[1])
            if not stored then return -1 end
            if stored == ARGV[1] then
              redis.call('DEL', KEYS[1], KEYS[2])
              return 1
            end
            local failures = redis.call('INCR', KEYS[2])
            if failures == 1 then
              local ttl = redis.call('PTTL', KEYS[1])
              if ttl > 0 then redis.call('PEXPIRE', KEYS[2], ttl) end
            end
            if failures >= tonumber(ARGV[2]) then
              redis.call('DEL', KEYS[1], KEYS[2])
              return -2
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final VerificationMailService mailService;
    private final HashingService hashingService;
    private final String store;
    private final Duration codeTtl;
    private final Duration resendCooldown;
    private final int maxAttempts;
    private final String pepper;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, LocalCode> localCodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> localCooldowns = new ConcurrentHashMap<>();

    public EmailVerificationService(
            StringRedisTemplate redis,
            VerificationMailService mailService,
            HashingService hashingService,
            @Value("${app.security.verification-code-store:redis}") String store,
            @Value("${app.security.verification-code-minutes:10}") long codeMinutes,
            @Value("${app.security.verification-code-resend-seconds:60}") long resendSeconds,
            @Value("${app.security.verification-code-max-attempts:5}") int maxAttempts,
            @Value("${app.security.verification-code-pepper}") String pepper) {
        this.redis = redis;
        this.mailService = mailService;
        this.hashingService = hashingService;
        this.store = store.toLowerCase(Locale.ROOT);
        this.codeTtl = Duration.ofMinutes(codeMinutes);
        this.resendCooldown = Duration.ofSeconds(resendSeconds);
        this.maxAttempts = maxAttempts;
        this.pepper = pepper;
        if (!this.store.equals("redis") && !this.store.equals("memory")) {
            throw new IllegalArgumentException("verification-code-store must be redis or memory");
        }
    }

    public DeliveryPolicy deliveryPolicy() {
        return new DeliveryPolicy(codeTtl.toSeconds(), resendCooldown.toSeconds());
    }

    public DeliveryPolicy sendCode(String rawEmail, EmailVerificationPurpose purpose) {
        String email = normalize(rawEmail);
        String subject = subject(email, purpose);
        reserveCooldown(subject);
        String code = String.format("%06d", random.nextInt(1_000_000));
        String hash = codeHash(email, purpose, code);
        try {
            storeCode(subject, hash);
            mailService.sendVerificationCode(email, purpose, code, codeTtl);
            return deliveryPolicy();
        } catch (RuntimeException exception) {
            remove(subject, true);
            throw exception;
        }
    }

    public void verifyAndConsume(String rawEmail, EmailVerificationPurpose purpose, String code) {
        String email = normalize(rawEmail);
        String subject = subject(email, purpose);
        String expected = codeHash(email, purpose, code == null ? "" : code);
        long result = store.equals("memory") ? verifyMemory(subject, expected) : verifyRedis(subject, expected);
        if (result == 1) return;
        if (result == -2) {
            throw invalid("验证码错误次数过多，请重新获取");
        }
        throw invalid("验证码无效或已过期，请重新获取");
    }

    private void reserveCooldown(String subject) {
        if (store.equals("memory")) {
            reserveMemoryCooldown(subject);
            return;
        }
        try {
            Boolean reserved = redis.opsForValue().setIfAbsent(cooldownKey(subject), "1", resendCooldown);
            if (!Boolean.TRUE.equals(reserved)) {
                Long ttl = redis.getExpire(cooldownKey(subject));
                throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED,
                        "验证码发送过于频繁，请稍后重试",
                        Map.of("retryAfterSeconds", Math.max(1, ttl == null ? resendCooldown.toSeconds() : ttl)));
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private void reserveMemoryCooldown(String subject) {
        long now = System.currentTimeMillis();
        AtomicInteger retryAfter = new AtomicInteger();
        localCooldowns.compute(subject, (ignored, current) -> {
            if (current != null && current > now) {
                retryAfter.set((int) Math.max(1, (current - now + 999) / 1000));
                return current;
            }
            return now + resendCooldown.toMillis();
        });
        if (retryAfter.get() > 0) {
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED,
                    "验证码发送过于频繁，请稍后重试",
                    Map.of("retryAfterSeconds", retryAfter.get()));
        }
    }

    private void storeCode(String subject, String hash) {
        if (store.equals("memory")) {
            localCodes.put(subject, new LocalCode(hash, System.currentTimeMillis() + codeTtl.toMillis(), 0));
            return;
        }
        try {
            redis.opsForValue().set(codeKey(subject), hash, codeTtl);
            redis.delete(attemptKey(subject));
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private long verifyRedis(String subject, String expected) {
        try {
            Long result = redis.execute(VERIFY_SCRIPT,
                    List.of(codeKey(subject), attemptKey(subject)), expected, Integer.toString(maxAttempts));
            return result == null ? -1 : result;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private long verifyMemory(String subject, String expected) {
        AtomicInteger result = new AtomicInteger(-1);
        long now = System.currentTimeMillis();
        localCodes.compute(subject, (ignored, current) -> {
            if (current == null || current.expiresAt() <= now) return null;
            if (constantTimeEquals(current.hash(), expected)) {
                result.set(1);
                return null;
            }
            int failures = current.failures() + 1;
            if (failures >= maxAttempts) {
                result.set(-2);
                return null;
            }
            result.set(0);
            return new LocalCode(current.hash(), current.expiresAt(), failures);
        });
        return result.get();
    }

    private void remove(String subject, boolean includeCooldown) {
        if (store.equals("memory")) {
            localCodes.remove(subject);
            if (includeCooldown) localCooldowns.remove(subject);
            return;
        }
        try {
            redis.delete(List.of(codeKey(subject), attemptKey(subject)));
            if (includeCooldown) redis.delete(cooldownKey(subject));
        } catch (RuntimeException ignored) {
            // The original delivery/storage error remains the useful response.
        }
    }

    private String codeHash(String email, EmailVerificationPurpose purpose, String code) {
        return hashingService.sha256(pepper + ":" + purpose + ":" + email + ":" + code);
    }

    private String subject(String email, EmailVerificationPurpose purpose) {
        return hashingService.sha256(purpose + ":" + email);
    }

    private String codeKey(String subject) {
        return "adaptive-learning:email-code:" + subject;
    }

    private String attemptKey(String subject) {
        return codeKey(subject) + ":attempts";
    }

    private String cooldownKey(String subject) {
        return codeKey(subject) + ":cooldown";
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private boolean constantTimeEquals(String first, String second) {
        return java.security.MessageDigest.isEqual(
                first.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                second.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.AUTH_VERIFICATION_CODE_INVALID, message);
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE,
                "验证码服务暂时不可用，请稍后重试");
    }

    public record DeliveryPolicy(long expiresInSeconds, long resendAfterSeconds) {}
    private record LocalCode(String hash, long expiresAt, int failures) {}
}
