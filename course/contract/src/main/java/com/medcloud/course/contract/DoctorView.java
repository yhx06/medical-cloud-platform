package com.medcloud.course.contract;

public class DoctorView {
    private final String userId, username, displayName, departmentId, status, title, bio;
    public DoctorView(String userId, String username, String displayName, String departmentId, String status) {
        this(userId, username, displayName, departmentId, status, null, null);
    }
    public DoctorView(String userId, String username, String displayName, String departmentId, String status,
            String title, String bio) {
        this.userId = userId; this.username = username; this.displayName = displayName;
        this.departmentId = departmentId; this.status = status; this.title = title; this.bio = bio;
    }
    public String getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getDepartmentId() { return departmentId; }
    public String getStatus() { return status; }
    public String getTitle() { return title; }
    public String getBio() { return bio; }
}
