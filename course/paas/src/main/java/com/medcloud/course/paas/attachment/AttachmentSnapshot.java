package com.medcloud.course.paas.attachment;

public final class AttachmentSnapshot {
    private final String fileId;
    private final String objectKey;
    private final String fileName;
    private final String contentType;
    private final long version;
    public AttachmentSnapshot(String fileId,String objectKey,String fileName,String contentType,long version) {
        this.fileId = fileId;
        this.objectKey = objectKey;
        this.fileName = fileName;
        this.contentType = contentType;
        this.version = version;
    }
    public String getFileId() { return fileId; }
    public String getObjectKey() { return objectKey; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getVersion() { return version; }
}
