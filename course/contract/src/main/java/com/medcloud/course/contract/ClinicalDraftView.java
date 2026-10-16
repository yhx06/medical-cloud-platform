package com.medcloud.course.contract;

public final class ClinicalDraftView {
    private final ClinicalContentView content;
    private final long revision;
    private final String savedAt;

    public ClinicalDraftView(ClinicalContentView content, long revision, String savedAt) {
        this.content = content;
        this.revision = revision;
        this.savedAt = savedAt;
    }
    public ClinicalContentView getContent() { return content; }
    public long getRevision() { return revision; }
    public String getSavedAt() { return savedAt; }
}
