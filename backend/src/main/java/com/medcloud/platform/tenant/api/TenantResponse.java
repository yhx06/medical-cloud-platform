package com.medcloud.platform.tenant.api;

import com.medcloud.platform.tenant.domain.Tenant;

import java.util.UUID;

public record TenantResponse(UUID id, String name, String status) {
    static TenantResponse from(Tenant tenant) {
        return new TenantResponse(tenant.getId(), tenant.getName(), tenant.getStatus().name());
    }
}
