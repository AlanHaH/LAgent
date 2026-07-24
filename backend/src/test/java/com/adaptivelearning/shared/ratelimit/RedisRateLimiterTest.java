package com.adaptivelearning.shared.ratelimit;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisRateLimiterTest {
    @Test
    void fallsBackToLocalCounterAndRejectsRequestsOverTheLimit() {
        RedisRateLimiter limiter = new RedisRateLimiter(
                null, false,
                2, Duration.ofMinutes(1),
                2, Duration.ofMinutes(1),
                2, Duration.ofMinutes(1));

        limiter.requireLoginAllowed("127.0.0.1|student");
        limiter.requireLoginAllowed("127.0.0.1|student");

        assertThatThrownBy(() -> limiter.requireLoginAllowed("127.0.0.1|student"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED));
    }
}
