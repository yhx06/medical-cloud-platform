package com.medcloud.platform.identity.api;

public record LoginResponse(String accessToken, CurrentUserResponse user) {
}
