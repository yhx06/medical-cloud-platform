package com.medcloud.course.portal.web;

public final class PaasRejectedException extends RuntimeException {
    private final int status;

    public PaasRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() { return status; }
}
