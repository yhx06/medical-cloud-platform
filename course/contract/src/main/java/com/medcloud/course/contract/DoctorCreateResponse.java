package com.medcloud.course.contract;

public final class DoctorCreateResponse {
    private final String userId, username, displayName, departmentId, status, initialPassword;
    public DoctorCreateResponse(String userId, String username, String displayName, String departmentId,
                                String status, String initialPassword) {
        this.userId = userId; this.username = username; this.displayName = displayName;
        this.departmentId = departmentId; this.status = status; this.initialPassword = initialPassword;
    }
    public String getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getDepartmentId() { return departmentId; }
    public String getStatus() { return status; }
    public String getInitialPassword() { return initialPassword; }
}
