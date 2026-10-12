package com.medcloud.course.paas.attachment;

public final class UploadAttempt {
    private final String uploadId;
    private final com.medcloud.course.contract.AttachmentState state;
    private final String attemptToken;
    private final String objectKey;
    private final String fileName;
    private final String contentType;
    private final long sizeBytes;
    private final String sha256;
    private final java.time.Instant leaseUntil;
    private final com.medcloud.course.contract.AttachmentView availableFile;
    public UploadAttempt(String uploadId,com.medcloud.course.contract.AttachmentState state,String attemptToken,String objectKey,String fileName,String contentType,long sizeBytes,String sha256,java.time.Instant leaseUntil,com.medcloud.course.contract.AttachmentView availableFile) {
        this.uploadId = uploadId;
        this.state = state;
        this.attemptToken = attemptToken;
        this.objectKey = objectKey;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.leaseUntil = leaseUntil;
        this.availableFile = availableFile;
    }
    public String getUploadId() { return uploadId; }
    public com.medcloud.course.contract.AttachmentState getState() { return state; }
    public String getAttemptToken() { return attemptToken; }
    public String getObjectKey() { return objectKey; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public java.time.Instant getLeaseUntil() { return leaseUntil; }
    public com.medcloud.course.contract.AttachmentView getAvailableFile() { return availableFile; }
}
