package com.adaptivelearning.shared.security;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {
    private SecurityUtils() {}

    public static CurrentUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CurrentUser user)) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED, "请先登录");
        }
        return user;
    }

    public static long currentUserId() {
        return currentUser().id();
    }

    public static long currentUserIdOrSystem() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof CurrentUser user ? user.id() : 0L;
    }
}

