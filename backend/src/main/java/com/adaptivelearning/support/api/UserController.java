package com.adaptivelearning.support.api;

import com.adaptivelearning.shared.api.ApiResponse;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.support.application.AuthService;
import com.adaptivelearning.support.domain.UserEntity;
import com.adaptivelearning.support.infrastructure.UserMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {
    private final UserMapper userMapper;
    private final AuthService authService;

    public record UpdateMeRequest(@Email @Size(max = 160) String email, @Size(max = 80) String timezone, Integer version) {}

    @GetMapping
    public ApiResponse<AuthService.UserView> me() {
        return ApiResponse.ok(authService.view(userMapper.selectById(SecurityUtils.currentUserId())));
    }

    @PatchMapping
    public ApiResponse<AuthService.UserView> update(@Valid @RequestBody UpdateMeRequest request) {
        UserEntity user = userMapper.selectById(SecurityUtils.currentUserId());
        if (request.version() == null || !request.version().equals(user.getVersion())) {
            throw new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "用户信息已更新，请刷新后重试");
        }
        if (request.email() != null) user.setEmail(request.email().trim().toLowerCase());
        if (request.timezone() != null) {
            try { ZoneId.of(request.timezone()); } catch (Exception ex) {
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
