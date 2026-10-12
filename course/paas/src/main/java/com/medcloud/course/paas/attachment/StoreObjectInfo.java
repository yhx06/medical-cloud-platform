package com.medcloud.course.paas.attachment;

public final class StoreObjectInfo {
    private final long sizeBytes;
    private final String sha256;
    public StoreObjectInfo(long sizeBytes,String sha256) {
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
    }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
}
