package com.medcloud.course.paas.attachment;

public final class ValidatedAttachment {
    private final java.nio.file.Path path;
    private final String fileName;
    private final String contentType;
    private final long sizeBytes;
    private final String sha256;
    ValidatedAttachment(java.nio.file.Path path,String fileName,String contentType,long sizeBytes,String sha256) {
        this.path = path;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
    }
    public java.nio.file.Path getPath() { return path; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
}
