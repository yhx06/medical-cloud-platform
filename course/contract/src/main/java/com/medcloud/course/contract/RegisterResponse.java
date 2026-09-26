package com.medcloud.course.contract;

public final class RegisterResponse {
    private final String id;
    private final String username;

    public RegisterResponse(String id, String username) {
        this.id = id;
        this.username = username;
    }

    public String getId() { return id; }
    public String getUsername() { return username; }
}
