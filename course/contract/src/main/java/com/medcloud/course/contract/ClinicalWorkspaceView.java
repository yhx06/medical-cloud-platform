package com.medcloud.course.contract;

public final class ClinicalWorkspaceView {
    private final String appointmentId;
    private final String status;
    private final PatientComplaintView patientComplaint;
    private final ClinicalDraftView draft;
    private final String recordId;
    private final long currentRevision;

    public ClinicalWorkspaceView(String appointmentId, String status, PatientComplaintView patientComplaint, ClinicalDraftView draft, String recordId, long currentRevision) {
        this.appointmentId = appointmentId;
        this.status = status;
        this.patientComplaint = patientComplaint;
        this.draft = draft;
        this.recordId = recordId;
        this.currentRevision = currentRevision;
    }
    public String getAppointmentId() { return appointmentId; }
    public String getStatus() { return status; }
    public PatientComplaintView getPatientComplaint() { return patientComplaint; }
    public ClinicalDraftView getDraft() { return draft; }
    public String getRecordId() { return recordId; }
    public long getCurrentRevision() { return currentRevision; }
}
