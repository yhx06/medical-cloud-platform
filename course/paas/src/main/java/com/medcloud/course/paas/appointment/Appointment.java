package com.medcloud.course.paas.appointment;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Index;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;

@Entity
@Table(name = "appointment", indexes = @Index(name = "idx_course_patient_slot", columnList = "patient_id,slot_id"))
public class Appointment {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "patient_id", nullable = false, length = 36)
    private String patientId;

    @Column(name = "slot_id", nullable = false, length = 36)
    private String slotId;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "doctor_id", nullable = false, length = 36)
    private String doctorId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fee;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "booked_on", nullable = false)
    private Date bookedOn;

    @Column(name = "started_at")
    private Timestamp startedAt;

    protected Appointment() { }

    public Appointment(String id, String patientId, String slotId, String tenantId,
                       String doctorId, BigDecimal fee, Date bookedOn) {
        this.id = id;
        this.patientId = patientId;
        this.slotId = slotId;
        this.tenantId = tenantId;
        this.doctorId = doctorId;
        this.fee = fee;
        this.bookedOn = bookedOn;
        this.status = "BOOKED";
    }

    public String getId() { return id; }
    public String getPatientId() { return patientId; }
    public String getSlotId() { return slotId; }
    public String getTenantId() { return tenantId; }
    public String getDoctorId() { return doctorId; }
    public BigDecimal getFee() { return fee; }
    public String getStatus() { return status; }
    public Date getBookedOn() { return bookedOn; }
    public Timestamp getStartedAt() { return startedAt; }

    public void start() {
        if (!"BOOKED".equals(status)) {
            throw new IllegalStateException("appointment cannot start");
        }
        status = "IN_PROGRESS";
    }

    public void complete() {
        if (!"IN_PROGRESS".equals(status)) {
            throw new IllegalStateException("appointment cannot complete");
        }
        status = "COMPLETED";
    }
}
