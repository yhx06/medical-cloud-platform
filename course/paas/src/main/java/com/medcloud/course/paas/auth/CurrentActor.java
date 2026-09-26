package com.medcloud.course.paas.auth;

import com.medcloud.course.contract.Role;

public final class CurrentActor {
    private final String userId;
    private final Role role;
    private final String tenantId;

    public CurrentActor(String userId, Role role, String tenantId) {
        this.userId = userId;
        this.role = role;
        this.tenantId = tenantId;
    }

    public String getUserId() { return userId; }
    public Role getRole() { return role; }
    public String getTenantId() { return tenantId; }
}
