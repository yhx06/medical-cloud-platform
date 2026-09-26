package com.medcloud.course.contract;

public final class ClinicalRecordView {
    private final String id;
    private final String appointmentId;
    private final String doctorId;
    private final String patientId;
    private final String diagnosis;
    private final String advice;

    public ClinicalRecordView(String id, String appointmentId, String doctorId, String patientId,
                              String diagnosis, String advice) {
        this.id = id;
        this.appointmentId = appointmentId;
        this.doctorId = doctorId;
        this.patientId = patientId;
        this.diagnosis = diagnosis;
        this.advice = advice;
    }
    public String getId() { return id; }
    public String getAppointmentId() { return appointmentId; }
    public String getDoctorId() { return doctorId; }
    public String getPatientId() { return patientId; }
    public String getDiagnosis() { return diagnosis; }
    public String getAdvice() { return advice; }
}
