package com.medcloud.course.contract;

public final class AttachmentDeleteView {
    private final String fileId;
    private final AttachmentState state;
    public AttachmentDeleteView(String fileId, AttachmentState state) {
        this.fileId = fileId;
        this.state = state;
    }
    public String getFileId() { return fileId; }
    public AttachmentState getState() { return state; }
}
