package com.medcloud.course.contract;

public final class DownloadAuthorizationView {
    private final String fileId;
    private final String url;
    private final String expiresAt;
    public DownloadAuthorizationView(String fileId, String url, String expiresAt) {
        this.fileId = fileId;
        this.url = url;
        this.expiresAt = expiresAt;
    }
    public String getFileId() { return fileId; }
    public String getUrl() { return url; }
    public String getExpiresAt() { return expiresAt; }
}
