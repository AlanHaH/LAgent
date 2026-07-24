package com.adaptivelearning.support.api;

import com.adaptivelearning.shared.api.ApiResponse;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.ratelimit.RedisRateLimiter;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.support.application.AuthService;
import com.adaptivelearning.support.application.EmailVerificationPurpose;
import com.adaptivelearning.support.application.EmailVerificationService;
import com.adaptivelearning.support.domain.UserEntity;
import com.adaptivelearning.support.infrastructure.UserMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {
    private final UserMapper userMapper;
    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final RedisRateLimiter rateLimiter;

    public record UpdateMeRequest(
        @Email @Size(max = 160) String email,
        @Pattern(regexp = "\\d{6}") String emailVerificationCode,
        @Size(max = 80) String timezone,
        Integer version) {
    }

    public record SendEmailVerificationRequest(
        @NotBlank @Email @Size(max = 160) String email) {
    }

    @GetMapping
    public ApiResponse<AuthService.UserView> me() {
        return ApiResponse.ok(authService.view(userMapper.selectById(SecurityUtils.currentUserId())));
    }

    @PostMapping("/email-verification-code")
    public ApiResponse<EmailVerificationService.DeliveryPolicy> sendEmailVerificationCode(
        @Valid @RequestBody SendEmailVerificationRequest request) {
        UserEntity user = userMapper.selectById(SecurityUtils.currentUserId());
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        rateLimiter.requireVerificationAllowed("user:" + user.getId());
        rateLimiter.requireVerificationAllowed("email:" + email);
        if (email.equals(user.getEmail())) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "新邮箱不能与当前邮箱相同");
        }
        long existing = userMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserEntity>()
            .eq(UserEntity::getEmail, email));
        if (existing > 0) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "该邮箱已被使用");
        }
        return ApiResponse.ok(emailVerificationService.sendCode(email, EmailVerificationPurpose.CHANGE_EMAIL));
    }

    @PatchMapping
    public ApiResponse<AuthService.UserView> update(@Valid @RequestBody UpdateMeRequest request) {
        UserEntity user = userMapper.selectById(SecurityUtils.currentUserId());
        if (request.version() == null || !request.version().equals(user.getVersion())) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "用户信息已更新，请刷新后重试");
        }
        if (request.email() != null) {
            String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
            if (!email.equals(user.getEmail())) {
                long existing = userMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserEntity>()
                    .eq(UserEntity::getEmail, email));
                if (existing > 0) {
                    throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "该邮箱已被使用");
                }
                if (request.emailVerificationCode() == null || request.emailVerificationCode().isBlank()) {
                    throw new BusinessException(ErrorCode.AUTH_VERIFICATION_CODE_INVALID, "更换邮箱需要验证码");
                }
                emailVerificationService.verifyAndConsume(email, EmailVerificationPurpose.CHANGE_EMAIL,
                    request.emailVerificationCode());
                user.setEmail(email);
                user.setEmailVerifiedAt(java.time.Instant.now());
            }
        }
        if (request.timezone() != null) {
            try {
                ZoneId.of(request.timezone());
            } catch (Exception ex) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "无效的 IANA 时区");
            }
            user.setTimezone(request.timezone());
        }
        if (userMapper.updateById(user) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "用户信息已更新，请刷新后重试");
        }
        return ApiResponse.ok(authService.view(userMapper.selectById(user.getId())));
    }
}
