package com.medcloud.course.contract;

public final class AttachmentView {
    private final String fileId;
    private final AttachmentSourceType sourceType;
    private final String fileName;
    private final String contentType;
    private final long sizeBytes;
    private final String createdAt;
    public AttachmentView(String fileId, AttachmentSourceType sourceType, String fileName, String contentType, long sizeBytes, String createdAt) {
        this.fileId = fileId;
        this.sourceType = sourceType;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.createdAt = createdAt;
    }
    public String getFileId() { return fileId; }
    public AttachmentSourceType getSourceType() { return sourceType; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getCreatedAt() { return createdAt; }
}
