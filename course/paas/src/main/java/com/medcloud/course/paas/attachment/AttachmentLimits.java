package com.medcloud.course.paas.attachment;

public final class AttachmentLimits {
    private final long maxBytes, maxPixels;
    private final int maxPdfPages, containerConcurrency, uploaderPendingLimit, connectTimeoutSeconds, storeDeadlineSeconds, receiveDeadlineSeconds;
    public AttachmentLimits() { this(10485760L, 20000000L, 200, 4, 2, 3, 90, 120); }
    public AttachmentLimits(long maxBytes, long maxPixels, int maxPdfPages, int containerConcurrency, int uploaderPendingLimit, int connectTimeoutSeconds, int storeDeadlineSeconds, int receiveDeadlineSeconds) {
        if (maxBytes < 1 || maxPixels < 1 || maxPdfPages < 1 || containerConcurrency < 1 || uploaderPendingLimit < 1 || connectTimeoutSeconds < 1 || storeDeadlineSeconds < 1 || receiveDeadlineSeconds < 1) throw new IllegalArgumentException("Invalid attachment limits");
        this.maxBytes=maxBytes; this.maxPixels=maxPixels; this.maxPdfPages=maxPdfPages; this.containerConcurrency=containerConcurrency; this.uploaderPendingLimit=uploaderPendingLimit; this.connectTimeoutSeconds=connectTimeoutSeconds; this.storeDeadlineSeconds=storeDeadlineSeconds; this.receiveDeadlineSeconds=receiveDeadlineSeconds;
    }
    public long getMaxBytes() { return maxBytes; }
    public long getMaxPixels() { return maxPixels; }
    public int getMaxPdfPages() { return maxPdfPages; }
    public int getContainerConcurrency() { return containerConcurrency; }
    public int getUploaderPendingLimit() { return uploaderPendingLimit; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public int getStoreDeadlineSeconds() { return storeDeadlineSeconds; }
    public int getReceiveDeadlineSeconds() { return receiveDeadlineSeconds; }
}
