package com.adaptivelearning.support.application;

import com.adaptivelearning.support.infrastructure.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class LoginFailureService {
    private final UserMapper userMapper;

    @Value("${app.security.login-max-failures:5}")
    private int maxFailures;
    @Value("${app.security.login-lock-minutes:15}")
    private long lockMinutes;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long userId) {
        Instant now = Instant.now();
        userMapper.recordLoginFailure(userId, maxFailures,
                now.plus(Duration.ofMinutes(lockMinutes)), now);
    }
}
