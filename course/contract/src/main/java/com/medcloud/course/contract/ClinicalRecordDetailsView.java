package com.medcloud.course.contract;

public final class ClinicalRecordDetailsView {
    private final String recordId;
    private final String appointmentId;
    private final ClinicalContentView original;
    private final ClinicalContentView effective;
    private final long currentRevision;
    private final String completedAt;

    public ClinicalRecordDetailsView(String recordId, String appointmentId, ClinicalContentView original, ClinicalContentView effective, long currentRevision, String completedAt) {
        this.recordId = recordId;
        this.appointmentId = appointmentId;
        this.original = original;
        this.effective = effective;
        this.currentRevision = currentRevision;
        this.completedAt = completedAt;
    }
    public String getRecordId() { return recordId; }
    public String getAppointmentId() { return appointmentId; }
    public ClinicalContentView getOriginal() { return original; }
    public ClinicalContentView getEffective() { return effective; }
    public long getCurrentRevision() { return currentRevision; }
    public String getCompletedAt() { return completedAt; }
}
