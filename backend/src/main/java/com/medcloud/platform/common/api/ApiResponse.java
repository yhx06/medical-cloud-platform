package com.medcloud.platform.common.api;

public record ApiResponse<T>(T data, String requestId) {
    public static <T> ApiResponse<T> success(T data, String requestId) {
        return new ApiResponse<>(data, requestId);
    }
}
