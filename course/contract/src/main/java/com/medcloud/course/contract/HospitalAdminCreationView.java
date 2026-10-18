package com.medcloud.course.contract;

public final class HospitalAdminCreationView {
    private final String tenantId;
    private final String hospitalName;
    private final String administratorId;
    private final String username;

    public HospitalAdminCreationView(String tenantId, String hospitalName, String administratorId, String username) {
        this.tenantId = tenantId;
        this.hospitalName = hospitalName;
        this.administratorId = administratorId;
        this.username = username;
    }
    public String getTenantId() { return tenantId; }
    public String getHospitalName() { return hospitalName; }
    public String getAdministratorId() { return administratorId; }
    public String getUsername() { return username; }
}
