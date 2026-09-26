package com.medcloud.course.paas.consultation;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.sql.Timestamp;

@Entity
@Table(name = "clinical_record", uniqueConstraints = @UniqueConstraint(columnNames = "appointment_id"))
public class ClinicalRecord {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "appointment_id", nullable = false, length = 36)
    private String appointmentId;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "doctor_id", nullable = false, length = 36)
    private String doctorId;

    @Column(name = "patient_id", nullable = false, length = 36)
    private String patientId;

    @Column(nullable = false, length = 500)
    private String diagnosis;

    @Column(nullable = false, length = 2000)
    private String advice;

    @Column(length = 500)
    private String complaint;

    @Column(length = 2000)
    private String notes;

    @Column(name = "completed_at")
    private Timestamp completedAt;

    protected ClinicalRecord() { }

    public ClinicalRecord(String id, String appointmentId, String tenantId, String doctorId,
                          String patientId, String diagnosis, String advice) {
        if (diagnosis == null || diagnosis.trim().isEmpty() || advice == null || advice.trim().isEmpty()) {
            throw new IllegalArgumentException("diagnosis and advice required");
        }
        this.id = id;
        this.appointmentId = appointmentId;
        this.tenantId = tenantId;
        this.doctorId = doctorId;
        this.patientId = patientId;
        this.diagnosis = diagnosis;
        this.advice = advice;
    }

    public String getId() { return id; }
    public String getAppointmentId() { return appointmentId; }
    public String getTenantId() { return tenantId; }
    public String getDoctorId() { return doctorId; }
    public String getPatientId() { return patientId; }
    public String getDiagnosis() { return diagnosis; }
    public String getAdvice() { return advice; }
    public String getComplaint() { return complaint; }
    public String getNotes() { return notes; }
    public Timestamp getCompletedAt() { return completedAt; }
}
