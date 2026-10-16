package com.medcloud.course.contract;

public final class ClinicalContentView {
    private final String complaint;
    private final String diagnosis;
    private final String advice;
    private final String notes;

    public ClinicalContentView(String complaint, String diagnosis, String advice, String notes) {
        this.complaint = complaint;
        this.diagnosis = diagnosis;
        this.advice = advice;
        this.notes = notes;
    }
    public String getComplaint() { return complaint; }
    public String getDiagnosis() { return diagnosis; }
    public String getAdvice() { return advice; }
    public String getNotes() { return notes; }
}
