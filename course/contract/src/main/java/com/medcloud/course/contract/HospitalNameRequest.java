package com.medcloud.course.contract;

public class HospitalNameRequest {
    private String name;
    public HospitalNameRequest() { }
    public HospitalNameRequest(String name) { this.name = name; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
