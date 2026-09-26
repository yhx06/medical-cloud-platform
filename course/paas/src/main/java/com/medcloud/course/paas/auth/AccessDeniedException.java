package com.medcloud.course.paas.auth;

public final class AccessDeniedException extends SecurityException {
    public AccessDeniedException() {
        super("access denied");
    }
}
