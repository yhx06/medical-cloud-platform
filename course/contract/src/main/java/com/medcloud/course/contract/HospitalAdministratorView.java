package com.medcloud.course.contract;

public final class HospitalAdministratorView {
    private final String id;
    private final String tenantId;
    private final String username;
    private final String status;

    public HospitalAdministratorView(String id, String tenantId, String username, String status) {
        this.id = id;
        this.tenantId = tenantId;
        this.username = username;
        this.status = status;
    }
    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getUsername() { return username; }
    public String getStatus() { return status; }
}
