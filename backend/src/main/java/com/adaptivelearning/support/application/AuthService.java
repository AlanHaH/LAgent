package com.adaptivelearning.support.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.CurrentUser;
import com.adaptivelearning.shared.security.JwtService;
import com.adaptivelearning.support.domain.RefreshTokenEntity;
import com.adaptivelearning.support.domain.UserEntity;
import com.adaptivelearning.support.infrastructure.RefreshTokenMapper;
import com.adaptivelearning.support.infrastructure.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserMapper userMapper;
    private final RefreshTokenMapper tokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final HashingService hashingService;
    private final EmailVerificationService emailVerificationService;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.security.refresh-token-days:30}")
    private long refreshDays;
    @Value("${app.security.login-max-failures:5}")
    private int maxFailures;
    @Value("${app.security.login-lock-minutes:15}")
    private long lockMinutes;

    public record TokenPair(String accessToken, String refreshToken, long expiresIn, UserView user) {}
    public record UserView(String publicId, String username, String email, String timezone,
                           boolean emailVerified, Set<String> roles, Set<String> permissions, int version) {}

    @Transactional
    public TokenPair register(String username, String email, String password,
                              String verificationCode, String deviceId) {
        email = normalizeEmail(email);
        long existing = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username).or().eq(UserEntity::getEmail, email));
        if (existing > 0) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "用户名或邮箱已被使用");
        }
        emailVerificationService.verifyAndConsume(email, EmailVerificationPurpose.REGISTER, verificationCode);
        UserEntity user = new UserEntity();
        user.setPublicId(UUID.randomUUID().toString());
        user.setUsername(username.trim());
        user.setEmail(email);
        user.setEmailVerifiedAt(Instant.now());
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStatus("ACTIVE");
        user.setTimezone("Asia/Shanghai");
        user.setLoginFailedCount(0);
        userMapper.insert(user);
        Long roleId = userMapper.findRoleId("STUDENT");
        if (roleId == null) {
            throw new IllegalStateException("STUDENT role is not initialized");
        }
        userMapper.addRole(user.getId(), roleId);
        return issuePair(user, deviceId);
    }

    public EmailVerificationService.DeliveryPolicy requestVerificationCode(
            String rawEmail, EmailVerificationPurpose purpose) {
        String email = normalizeEmail(rawEmail);
        UserEntity existing = findByEmail(email);
        if (purpose == EmailVerificationPurpose.REGISTER) {
            if (existing != null) {
                throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "该邮箱已被注册");
            }
            return emailVerificationService.sendCode(email, purpose);
        }
        if (purpose == EmailVerificationPurpose.PASSWORD_RESET) {
            // 对不存在的邮箱返回相同策略，避免泄露账户是否存在。
            return existing == null ? emailVerificationService.deliveryPolicy()
                    : emailVerificationService.sendCode(email, purpose);
        }
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "不支持的验证码用途");
    }

    @Transactional
    public void resetPassword(String rawEmail, String verificationCode, String newPassword) {
        String email = normalizeEmail(rawEmail);
        UserEntity user = findByEmail(email);
        if (user == null || user.getEmailVerifiedAt() == null) {
            throw new BusinessException(ErrorCode.AUTH_VERIFICATION_CODE_INVALID,
                    "验证码无效或已过期，请重新获取");
        }
        emailVerificationService.verifyAndConsume(email, EmailVerificationPurpose.PASSWORD_RESET, verificationCode);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setLoginFailedCount(0);
        user.setLockedUntil(null);
        if (userMapper.updateById(user) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "账户已发生变更，请重新操作");
        }
        logoutAll(user.getId());
    }

    @Transactional
    public TokenPair login(String login, String password, String deviceId) {
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .and(q -> q.eq(UserEntity::getUsername, login).or().eq(UserEntity::getEmail, login.toLowerCase())));
        if (user == null || !"ACTIVE".equals(user.getStatus()) || user.getEmailVerifiedAt() == null) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, "账号或密码错误");
        }
        Instant now = Instant.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED, "登录失败次数过多，请稍后再试");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            int failures = user.getLoginFailedCount() == null ? 1 : user.getLoginFailedCount() + 1;
            user.setLoginFailedCount(failures);
            if (failures >= maxFailures) {
                user.setLockedUntil(now.plus(Duration.ofMinutes(lockMinutes)));
                user.setLoginFailedCount(0);
            }
            userMapper.updateById(user);
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, "账号或密码错误");
        }
        user.setLoginFailedCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        userMapper.updateById(user);
        return issuePair(user, deviceId);
    }

    @Transactional
    public TokenPair refresh(String rawToken, String deviceId) {
        String hash = hashingService.sha256(rawToken);
        RefreshTokenEntity old = tokenMapper.selectOne(new LambdaQueryWrapper<RefreshTokenEntity>()
                .eq(RefreshTokenEntity::getTokenHash, hash));
        if (old == null || old.getRevokedAt() != null || old.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_EXPIRED, "刷新令牌无效或已过期");
        }
        UserEntity user = userMapper.selectById(old.getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus()) || user.getEmailVerifiedAt() == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED, "账号不可用");
        }
        Instant revokedAt = Instant.now();
        if (tokenMapper.revokeIfActive(old.getId(), old.getVersion(), revokedAt) != 1) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_EXPIRED, "刷新令牌已经被使用");
        }
        TokenPair pair = issuePair(user, deviceId == null ? old.getDeviceId() : deviceId);
        RefreshTokenEntity replacement = tokenMapper.selectOne(new LambdaQueryWrapper<RefreshTokenEntity>()
                .eq(RefreshTokenEntity::getTokenHash, hashingService.sha256(pair.refreshToken())));
        if (replacement == null || tokenMapper.linkReplacement(old.getId(), old.getVersion() + 1,
                replacement.getId()) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "刷新令牌轮换冲突，请重新登录");
        }
        return pair;
    }

    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        RefreshTokenEntity token = tokenMapper.selectOne(new LambdaQueryWrapper<RefreshTokenEntity>()
                .eq(RefreshTokenEntity::getTokenHash, hashingService.sha256(rawToken)));
        if (token != null && token.getRevokedAt() == null) {
            token.setRevokedAt(Instant.now());
            tokenMapper.updateById(token);
        }
    }

    public void logoutAll(long userId) {
        tokenMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RefreshTokenEntity>()
                .eq(RefreshTokenEntity::getUserId, userId)
                .isNull(RefreshTokenEntity::getRevokedAt)
                .set(RefreshTokenEntity::getRevokedAt, Instant.now()));
    }

    public UserView view(UserEntity user) {
        Set<String> roles = userMapper.findRoleCodes(user.getId());
        Set<String> permissions = userMapper.findPermissionCodes(user.getId());
        return new UserView(user.getPublicId(), user.getUsername(), user.getEmail(), user.getTimezone(),
                user.getEmailVerifiedAt() != null,
                roles, permissions, user.getVersion() == null ? 0 : user.getVersion());
    }

    private UserEntity findByEmail(String email) {
        return userMapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getEmail, email));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private TokenPair issuePair(UserEntity user, String deviceId) {
        Set<String> roles = userMapper.findRoleCodes(user.getId());
        Set<String> permissions = userMapper.findPermissionCodes(user.getId());
        CurrentUser principal = new CurrentUser(user.getId(), user.getPublicId(), user.getUsername(), "", roles, permissions);
        String access = jwtService.issue(principal);
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshTokenEntity token = new RefreshTokenEntity();
        token.setUserId(user.getId());
        token.setTokenHash(hashingService.sha256(refresh));
        token.setDeviceId(deviceId == null || deviceId.isBlank() ? "unknown" : deviceId.substring(0, Math.min(120, deviceId.length())));
        token.setExpiresAt(Instant.now().plus(Duration.ofDays(refreshDays)));
        tokenMapper.insert(token);
        return new TokenPair(access, refresh, jwtService.expiresInSeconds(), view(user));
    }
}
