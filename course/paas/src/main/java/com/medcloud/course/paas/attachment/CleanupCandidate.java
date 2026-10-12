package com.medcloud.course.paas.attachment;

public final class CleanupCandidate {
    private final String fileId;
    private final String objectKey;
    private final com.medcloud.course.contract.AttachmentState state;
    private final long version;
    public CleanupCandidate(String fileId,String objectKey,com.medcloud.course.contract.AttachmentState state,long version) {
        this.fileId = fileId;
        this.objectKey = objectKey;
        this.state = state;
        this.version = version;
    }
    public String getFileId() { return fileId; }
    public String getObjectKey() { return objectKey; }
    public com.medcloud.course.contract.AttachmentState getState() { return state; }
    public long getVersion() { return version; }
}
