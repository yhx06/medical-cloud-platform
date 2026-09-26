package com.medcloud.course.contract;

public final class LoginResponse {
    private final String accessToken;
    private final String username;
    private final Role role;

    public LoginResponse(String accessToken, String username, Role role) {
        this.accessToken = accessToken;
        this.username = username;
        this.role = role;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getUsername() {
        return username;
    }

    public Role getRole() {
        return role;
    }
}
