package com.medcloud.course.contract;

public class DepartmentView {
    private final String id, name, status;
    public DepartmentView(String id, String name, String status) { this.id = id; this.name = name; this.status = status; }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getStatus() { return status; }
}
