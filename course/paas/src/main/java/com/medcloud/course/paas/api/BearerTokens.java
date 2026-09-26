package com.medcloud.course.paas.api;

final class BearerTokens {
    private BearerTokens() { }

    static String require(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= "Bearer ".length()) {
            throw new SecurityException("bearer token required");
        }
        return authorization.substring("Bearer ".length());
    }
}
