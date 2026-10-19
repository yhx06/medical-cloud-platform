package com.medcloud.course.portal.web;

public final class PaasRejectedException extends RuntimeException {
    private final int status;
    private final String code;

    public PaasRejectedException(int status, String message) {
        this(status, message, null);
    }

    public PaasRejectedException(int status, String message, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
}
