package com.adaptivelearning.support.api;

import com.adaptivelearning.shared.api.ApiResponse;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.ratelimit.RedisRateLimiter;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.support.application.AuthService;
import com.adaptivelearning.support.application.EmailVerificationPurpose;
import com.adaptivelearning.support.application.EmailVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final RedisRateLimiter rateLimiter;

    public record RegisterRequest(
        @NotBlank(message = "请输入用户名")
        @Size(min = 3, max = 50, message = "用户名长度需为 3～50 个字符")
        @Pattern(regexp = "[A-Za-z0-9_]+", message = "用户名只能包含英文字母、数字和下划线") String username,
        @NotBlank(message = "请输入邮箱")
        @Email(message = "邮箱格式不正确")
        @Size(max = 160, message = "邮箱长度不能超过 160 个字符") String email,
        @NotBlank(message = "请输入密码")
        @Size(min = 8, max = 128, message = "密码长度需为 8～128 个字符") String password,
        @NotBlank(message = "请输入邮箱验证码")
        @Pattern(regexp = "\\d{6}", message = "邮箱验证码必须是 6 位数字") String verificationCode,
        @Size(max = 120) String deviceId) {
    }

    public record LoginRequest(
        @NotBlank(message = "请输入用户名或邮箱") String login,
        @NotBlank(message = "请输入密码") String password,
        @Size(max = 120) String deviceId) {
    }

    public record RefreshRequest(@NotBlank String refreshToken, @Size(max = 120) String deviceId) {
    }

    public record LogoutRequest(String refreshToken) {
    }

    public record SendVerificationCodeRequest(
        @NotBlank(message = "请输入邮箱")
        @Email(message = "邮箱格式不正确")
        @Size(max = 160, message = "邮箱长度不能超过 160 个字符") String email,
        @NotNull EmailVerificationPurpose purpose) {
    }

    public record ResetPasswordRequest(
        @NotBlank(message = "请输入邮箱")
        @Email(message = "邮箱格式不正确")
        @Size(max = 160, message = "邮箱长度不能超过 160 个字符") String email,
        @NotBlank(message = "请输入邮箱验证码")
        @Pattern(regexp = "\\d{6}", message = "邮箱验证码必须是 6 位数字") String verificationCode,
        @NotBlank(message = "请输入新密码")
        @Size(min = 8, max = 128, message = "密码长度需为 8～128 个字符") String newPassword) {
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AuthService.TokenPair> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request.username(), request.email(), request.password(),
            request.verificationCode(), request.deviceId()));
    }

    @PostMapping("/email-verification-codes")
    public ApiResponse<EmailVerificationService.DeliveryPolicy> sendVerificationCode(
        @Valid @RequestBody SendVerificationCodeRequest request,
        HttpServletRequest servletRequest) {
        String normalizedEmail = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        rateLimiter.requireVerificationAllowed("ip:" + servletRequest.getRemoteAddr());
        rateLimiter.requireVerificationAllowed("email:" + normalizedEmail);
        if (request.purpose() == EmailVerificationPurpose.CHANGE_EMAIL) {
            throw new BusinessException(
                ErrorCode.COMMON_VALIDATION_ERROR,
                "更换邮箱验证码需要登录后获取");
        }
        return ApiResponse.ok(authService.requestVerificationCode(request.email(), request.purpose()));
    }

    @PostMapping("/password-reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.email(), request.verificationCode(), request.newPassword());
        return ApiResponse.ok(null);
    }

    @PostMapping("/login")
    public ApiResponse<AuthService.TokenPair> login(@Valid @RequestBody LoginRequest request,
                                                    HttpServletRequest servletRequest) {
        rateLimiter.requireLoginAllowed(servletRequest.getRemoteAddr() + "|" + request.login().toLowerCase());
        return ApiResponse.ok(authService.login(request.login(), request.password(), request.deviceId()));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthService.TokenPair> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken(), request.deviceId()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody(required = false) LogoutRequest request) {
        authService.logout(request == null ? null : request.refreshToken());
        return ApiResponse.ok(null);
    }

    @PostMapping("/logout-all")
    public ApiResponse<Void> logoutAll() {
        authService.logoutAll(SecurityUtils.currentUserId());
        return ApiResponse.ok(null);
    }
}
