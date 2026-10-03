package com.medcloud.course.contract;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public class TenantStatusRequest {
    private String status;
    private String expectedStatus;
    public TenantStatusRequest() { }
    public TenantStatusRequest(String status, String expectedStatus) {
        this.status = status; this.expectedStatus = expectedStatus;
    }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getExpectedStatus() { return expectedStatus; }
    public void setExpectedStatus(String expectedStatus) { this.expectedStatus = expectedStatus; }
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("unknown hospital status field");
    }
}
