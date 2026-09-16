package com.medcloud.platform.audit.application;

import com.medcloud.platform.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

public final class AuditRequestIds {
    private AuditRequestIds() {
    }

    public static UUID from(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        if (value instanceof UUID requestId) {
            return requestId;
        }
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
