package com.medcloud.platform.identity.api;

import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.security.CurrentUser;

import java.util.UUID;

public record CurrentUserResponse(UUID userId, String username, UserRole role, UUID tenantId) {
    public static CurrentUserResponse from(CurrentUser user) {
        return new CurrentUserResponse(user.userId(), user.username(), user.role(), user.tenantId());
    }
}
