package com.medcloud.course.contract;

/** A doctor's own editable profile, returned to the authenticated doctor. */
public final class DoctorProfileView {
    private final String userId, displayName, departmentId, title, bio;
    public DoctorProfileView(String userId, String displayName, String departmentId, String title, String bio) {
        this.userId = userId; this.displayName = displayName; this.departmentId = departmentId;
        this.title = title; this.bio = bio;
    }
    public String getUserId() { return userId; }
    public String getDisplayName() { return displayName; }
    public String getDepartmentId() { return departmentId; }
    public String getTitle() { return title; }
    public String getBio() { return bio; }
}
