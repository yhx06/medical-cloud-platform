package com.medcloud.platform.common.api;

import java.util.Map;

public record ApiError(
        String code,
        String message,
        String requestId,
        Map<String, String> fieldErrors
) {
}
