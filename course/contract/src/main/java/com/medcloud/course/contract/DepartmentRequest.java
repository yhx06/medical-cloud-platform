package com.medcloud.course.contract;

public class DepartmentRequest {
    private String name;
    private String status;
    public DepartmentRequest() { }
    public DepartmentRequest(String name, String status) { this.name = name; this.status = status; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public void setName(String name) { this.name = name; }
    public void setStatus(String status) { this.status = status; }
}
