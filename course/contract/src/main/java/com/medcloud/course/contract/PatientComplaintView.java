package com.medcloud.course.contract;

public final class PatientComplaintView {
    private final String appointmentId;
    private final String description;
    private final String updatedAt;
    private final long revision;
    private final boolean writable;

    public PatientComplaintView(String appointmentId, String description, String updatedAt, long revision, boolean writable) {
        this.appointmentId = appointmentId;
        this.description = description;
        this.updatedAt = updatedAt;
        this.revision = revision;
        this.writable = writable;
    }
    public String getAppointmentId() { return appointmentId; }
    public String getDescription() { return description; }
    public String getUpdatedAt() { return updatedAt; }
    public long getRevision() { return revision; }
    public boolean isWritable() { return writable; }
}
