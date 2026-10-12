package com.medcloud.course.contract;

public final class UploadOperationView {
    private final String uploadId;
    private final AttachmentState state;
    private final AttachmentView file;
    public UploadOperationView(String uploadId, AttachmentState state, AttachmentView file) {
        this.uploadId = uploadId;
        this.state = state;
        this.file = file;
    }
    public String getUploadId() { return uploadId; }
    public AttachmentState getState() { return state; }
    public AttachmentView getFile() { return file; }
}
