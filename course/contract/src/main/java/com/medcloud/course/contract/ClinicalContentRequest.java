package com.medcloud.course.contract;

public final class ClinicalContentRequest {
    private String complaint;
    private String diagnosis;
    private String advice;
    private String notes;

    public ClinicalContentRequest() {
    }
    public String getComplaint() { return complaint; }
    public void setComplaint(String complaint) { this.complaint = complaint; }
    public String getDiagnosis() { return diagnosis; }
    public void setDiagnosis(String diagnosis) { this.diagnosis = diagnosis; }
    public String getAdvice() { return advice; }
    public void setAdvice(String advice) { this.advice = advice; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
