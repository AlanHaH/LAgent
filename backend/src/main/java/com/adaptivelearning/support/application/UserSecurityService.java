package com.adaptivelearning.support.application;

import com.adaptivelearning.shared.security.CurrentUser;
import com.adaptivelearning.support.domain.UserEntity;
import com.adaptivelearning.support.infrastructure.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserSecurityService {
    private final UserMapper userMapper;

    public CurrentUser loadCurrentAuthorization(CurrentUser identity) {
        UserEntity user = userMapper.selectById(identity.id());
        if (user == null
                || !Objects.equals(user.getPublicId(), identity.publicId())
                || !"ACTIVE".equals(user.getStatus())
                || user.getEmailVerifiedAt() == null
                || user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            throw new BadCredentialsException("Account is not available");
        }
        Set<String> roles = userMapper.findRoleCodes(user.getId());
        Set<String> permissions = userMapper.findPermissionCodes(user.getId());
        return new CurrentUser(user.getId(), user.getPublicId(), user.getUsername(), "", roles, permissions);
    }
}
