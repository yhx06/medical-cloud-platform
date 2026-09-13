package com.medcloud.platform.schedule.domain;

import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "schedule_slot")
public class ScheduleSlot {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "doctor_id", nullable = false)
    private UUID doctorId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "department_id", nullable = false)
    private UUID departmentId;

    @Column(name = "schedule_date", nullable = false)
    private LocalDate scheduleDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScheduleSlotStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ScheduleSlot() {
    }

    private ScheduleSlot(
            UUID tenantId,
            UUID doctorId,
            UUID departmentId,
            LocalDate scheduleDate,
            LocalTime startTime,
            LocalTime endTime,
            int capacity,
            BigDecimal fee,
            LocalDate today
    ) {
        requireValid(scheduleDate, startTime, endTime, capacity, fee, today);
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.doctorId = doctorId;
        this.departmentId = departmentId;
        this.scheduleDate = scheduleDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.capacity = capacity;
        this.fee = fee;
        this.status = ScheduleSlotStatus.PUBLISHED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ScheduleSlot publish(
            UUID tenantId,
            UUID doctorId,
            UUID departmentId,
            LocalDate scheduleDate,
            LocalTime startTime,
            LocalTime endTime,
            int capacity,
            BigDecimal fee,
            LocalDate today
    ) {
        return new ScheduleSlot(
                tenantId, doctorId, departmentId, scheduleDate, startTime, endTime, capacity, fee, today);
    }

    private static void requireValid(
            LocalDate scheduleDate,
            LocalTime startTime,
            LocalTime endTime,
            int capacity,
            BigDecimal fee,
            LocalDate today
    ) {
        if (scheduleDate == null || today == null || scheduleDate.isBefore(today)
                || startTime == null || endTime == null || !endTime.isAfter(startTime)
                || capacity <= 0 || fee == null || fee.signum() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getDoctorId() {
        return doctorId;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public LocalDate getScheduleDate() {
        return scheduleDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public int getCapacity() {
        return capacity;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public ScheduleSlotStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
