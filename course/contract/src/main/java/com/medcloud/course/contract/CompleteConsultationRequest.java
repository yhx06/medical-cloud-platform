package com.medcloud.course.contract;

public final class CompleteConsultationRequest {
    private String diagnosis;
    private String advice;

    public CompleteConsultationRequest() { }
    public CompleteConsultationRequest(String diagnosis, String advice) {
        this.diagnosis = diagnosis;
        this.advice = advice;
    }
    public String getDiagnosis() { return diagnosis; }
    public String getAdvice() { return advice; }
    public void setDiagnosis(String diagnosis) { this.diagnosis = diagnosis; }
    public void setAdvice(String advice) { this.advice = advice; }
}
