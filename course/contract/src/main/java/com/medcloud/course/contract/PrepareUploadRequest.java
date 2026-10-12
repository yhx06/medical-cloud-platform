package com.medcloud.course.contract;

public final class PrepareUploadRequest {
    private String uploadKey;
    private String fileName;
    private String contentType;
    private long sizeBytes;
    private String sha256;
    public PrepareUploadRequest() { }
    public PrepareUploadRequest(String uploadKey, String fileName, String contentType, long sizeBytes, String sha256) {
        this.uploadKey = uploadKey;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
    }
    public String getUploadKey() { return uploadKey; }
    public void setUploadKey(String uploadKey) { this.uploadKey = uploadKey; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
}
