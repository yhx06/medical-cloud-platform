package com.medcloud.course.contract;

public class DoctorRequest {
    private String username;
    private String displayName;
    private String departmentId;
    private String status;
    public DoctorRequest() { }
    public DoctorRequest(String username, String displayName, String departmentId, String status) {
        this.username = username; this.displayName = displayName; this.departmentId = departmentId; this.status = status;
    }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getDepartmentId() { return departmentId; }
    public String getStatus() { return status; }
    public void setUsername(String username) { this.username = username; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setDepartmentId(String departmentId) { this.departmentId = departmentId; }
    public void setStatus(String status) { this.status = status; }
}
