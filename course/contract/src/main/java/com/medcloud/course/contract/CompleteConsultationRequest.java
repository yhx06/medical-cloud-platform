package com.medcloud.course.contract;

public final class CompleteConsultationRequest {
    private String diagnosis;
    private String advice;
    private String complaint;
    private String notes;
    private Long expectedDraftRevision;

    public CompleteConsultationRequest() { }
    public CompleteConsultationRequest(String diagnosis, String advice) {
        this.diagnosis = diagnosis;
        this.advice = advice;
    }
    public String getDiagnosis() { return diagnosis; }
    public String getAdvice() { return advice; }
    public String getComplaint() { return complaint; }
    public String getNotes() { return notes; }
    public Long getExpectedDraftRevision() { return expectedDraftRevision; }
    public void setExpectedDraftRevision(Long expectedDraftRevision) { this.expectedDraftRevision = expectedDraftRevision; }
    public void setDiagnosis(String diagnosis) { this.diagnosis = diagnosis; }
    public void setAdvice(String advice) { this.advice = advice; }
    public void setComplaint(String complaint) { this.complaint = complaint; }
    public void setNotes(String notes) { this.notes = notes; }
}
