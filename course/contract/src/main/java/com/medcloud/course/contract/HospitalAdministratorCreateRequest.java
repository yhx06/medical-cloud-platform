package com.medcloud.course.contract;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public class HospitalAdministratorCreateRequest {
    private String username;
    private String initialPassword;
    private String operationKey;

    public HospitalAdministratorCreateRequest() { }
    public HospitalAdministratorCreateRequest(String username, String initialPassword, String operationKey) {
        this.username = username;
        this.initialPassword = initialPassword;
        this.operationKey = operationKey;
    }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getInitialPassword() { return initialPassword; }
    public void setInitialPassword(String initialPassword) { this.initialPassword = initialPassword; }
    public String getOperationKey() { return operationKey; }
    public void setOperationKey(String operationKey) { this.operationKey = operationKey; }
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("unknown hospital administrator field");
    }
}
