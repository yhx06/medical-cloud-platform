package com.medcloud.course.paas.appointment;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.sql.Date;

@Entity
@Table(name = "appointment", uniqueConstraints = @UniqueConstraint(columnNames = {"patient_id", "slot_id"}))
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
}
