package com.medcloud.course.contract;

public final class RecordCorrectionView {
    private final String id;
    private final long sequence;
    private final ClinicalContentView before;
    private final ClinicalContentView after;
    private final String reason;
    private final String doctorId;
    private final String createdAt;

    public RecordCorrectionView(String id, long sequence, ClinicalContentView before, ClinicalContentView after, String reason, String doctorId, String createdAt) {
        this.id = id;
        this.sequence = sequence;
        this.before = before;
        this.after = after;
        this.reason = reason;
        this.doctorId = doctorId;
        this.createdAt = createdAt;
    }
    public String getId() { return id; }
    public long getSequence() { return sequence; }
    public ClinicalContentView getBefore() { return before; }
    public ClinicalContentView getAfter() { return after; }
    public String getReason() { return reason; }
    public String getDoctorId() { return doctorId; }
    public String getCreatedAt() { return createdAt; }
}
