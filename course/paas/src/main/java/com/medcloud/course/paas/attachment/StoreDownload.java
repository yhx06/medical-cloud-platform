package com.medcloud.course.paas.attachment;

public final class StoreDownload {
    private final String url;
    private final java.time.Instant expiresAt;
    public StoreDownload(String url,java.time.Instant expiresAt) {
        this.url = url;
        this.expiresAt = expiresAt;
    }
    public String getUrl() { return url; }
    public java.time.Instant getExpiresAt() { return expiresAt; }
}
