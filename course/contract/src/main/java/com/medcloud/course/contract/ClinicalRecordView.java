package com.medcloud.course.contract;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class ClinicalRecordView {
    private final String id;
    private final String appointmentId;
    private final String doctorId;
    private final String patientId;
    private final String diagnosis;
    private final String advice;
    private final String complaint;
    private final String notes;
    private final String completedAt;

    public ClinicalRecordView(String id, String appointmentId, String doctorId, String patientId,
                              String diagnosis, String advice) {
        this(id, appointmentId, doctorId, patientId, null, diagnosis, advice, null, null);
    }

    public ClinicalRecordView(String id, String appointmentId, String doctorId, String patientId,
                              String complaint, String diagnosis, String advice, String notes, Timestamp completedAt) {
        this.id = id;
        this.appointmentId = appointmentId;
        this.doctorId = doctorId;
        this.patientId = patientId;
        this.diagnosis = diagnosis;
        this.advice = advice;
        this.complaint = complaint;
        this.notes = notes;
        this.completedAt = completedAt == null ? null : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("Asia/Shanghai")).format(completedAt.toInstant());
    }
    public String getId() { return id; }
    public String getAppointmentId() { return appointmentId; }
    public String getDoctorId() { return doctorId; }
    public String getPatientId() { return patientId; }
    public String getDiagnosis() { return diagnosis; }
    public String getAdvice() { return advice; }
    public String getComplaint() { return complaint; }
    public String getNotes() { return notes; }
    public String getCompletedAt() { return completedAt; }
}
