package com.medcloud.platform.security;

import com.medcloud.platform.identity.domain.UserRole;

import java.util.UUID;

public record CurrentUser(UUID userId, String username, UserRole role, UUID tenantId) {
}
