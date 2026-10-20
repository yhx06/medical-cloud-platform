package com.medcloud.course.contract;

public class DoctorProfileRequest {
    private String title;
    private String bio;
    public DoctorProfileRequest() { }
    public DoctorProfileRequest(String title, String bio) { this.title = title; this.bio = bio; }
    public String getTitle() { return title; }
    public String getBio() { return bio; }
    public void setTitle(String title) { this.title = title; }
    public void setBio(String bio) { this.bio = bio; }
}
