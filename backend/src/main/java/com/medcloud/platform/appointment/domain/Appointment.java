package com.medcloud.platform.appointment.domain;

import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.schedule.domain.ScheduleSlot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "appointment")
public class Appointment {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "doctor_id", nullable = false, updatable = false)
    private UUID doctorId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "department_id", nullable = false, updatable = false)
    private UUID departmentId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "slot_id", nullable = false, updatable = false)
    private UUID slotId;

    @Column(name = "appointment_date", nullable = false, updatable = false)
    private LocalDate appointmentDate;

    @Column(name = "start_time", nullable = false, updatable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false, updatable = false)
    private LocalTime endTime;

    @Column(nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal fee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AppointmentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Appointment() {
    }

    private Appointment(UUID patientId, ScheduleSlot slot) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.tenantId = slot.getTenantId();
        this.patientId = patientId;
        this.doctorId = slot.getDoctorId();
        this.departmentId = slot.getDepartmentId();
        this.slotId = slot.getId();
        this.appointmentDate = slot.getScheduleDate();
        this.startTime = slot.getStartTime();
        this.endTime = slot.getEndTime();
        this.fee = slot.getFee();
        this.status = AppointmentStatus.BOOKED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Appointment book(UUID patientId, ScheduleSlot slot) {
        return new Appointment(patientId, slot);
    }

    public void cancel() {
        if (!status.canBeCancelled()) {
            throw new BusinessException(ErrorCode.APPOINTMENT_STATE_INVALID);
        }
        status = AppointmentStatus.CANCELLED;
        updatedAt = Instant.now();
    }

    public void startConsultation() {
        transitionTo(AppointmentStatus.IN_PROGRESS);
    }

    public void completeConsultation() {
        transitionTo(AppointmentStatus.COMPLETED);
    }

    private void transitionTo(AppointmentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.APPOINTMENT_STATE_INVALID);
        }
        status = target;
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public UUID getDoctorId() {
        return doctorId;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public UUID getSlotId() {
        return slotId;
    }

    public LocalDate getAppointmentDate() {
        return appointmentDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
