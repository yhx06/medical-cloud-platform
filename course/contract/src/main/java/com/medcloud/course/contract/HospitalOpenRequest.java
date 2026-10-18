package com.medcloud.course.contract;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public class HospitalOpenRequest {
    private String hospitalName;
    private String username;
    private String initialPassword;
    private String operationKey;

    public HospitalOpenRequest() { }
    public HospitalOpenRequest(String hospitalName, String username, String initialPassword, String operationKey) {
        this.hospitalName = hospitalName;
        this.username = username;
        this.initialPassword = initialPassword;
        this.operationKey = operationKey;
    }
    public String getHospitalName() { return hospitalName; }
    public void setHospitalName(String hospitalName) { this.hospitalName = hospitalName; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getInitialPassword() { return initialPassword; }
    public void setInitialPassword(String initialPassword) { this.initialPassword = initialPassword; }
    public String getOperationKey() { return operationKey; }
    public void setOperationKey(String operationKey) { this.operationKey = operationKey; }
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("unknown hospital opening field");
    }
}
