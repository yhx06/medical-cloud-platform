package com.medcloud.course.paas.schedule;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;

@Entity
@Table(name = "schedule_slot")
public class ScheduleSlot {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "department_id", nullable = false, length = 36)
    private String departmentId;

    @Column(name = "doctor_id", nullable = false, length = 36)
    private String doctorId;

    @Column(name = "schedule_date", nullable = false)
    private Date date;

    @Column(name = "start_time", nullable = false)
    private Time startTime;

    @Column(name = "end_time", nullable = false)
    private Time endTime;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fee;

    @Column(nullable = false, length = 16)
    private String status;

    protected ScheduleSlot() {
    }

    public ScheduleSlot(String id, String tenantId, String departmentId, String doctorId,
                        Date date, Time startTime, Time endTime, int capacity, BigDecimal fee) {
        if (capacity <= 0 || fee == null || fee.signum() < 0 || !endTime.after(startTime)) {
            throw new IllegalArgumentException("invalid slot");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.departmentId = departmentId;
        this.doctorId = doctorId;
        this.date = date;
        this.startTime = startTime;
        this.endTime = endTime;
        this.capacity = capacity;
        this.fee = fee;
        this.status = "PUBLISHED";
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getDepartmentId() {
        return departmentId;
    }

    public String getDoctorId() {
        return doctorId;
    }

    public Date getDate() {
        return date;
    }

    public Time getStartTime() {
        return startTime;
    }

    public Time getEndTime() {
        return endTime;
    }

    public int getCapacity() {
        return capacity;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public String getStatus() {
        return status;
    }

    public void update(String departmentId, String doctorId, Date date, Time startTime, Time endTime,
                       int capacity, BigDecimal fee, String status) {
        if (departmentId == null || doctorId == null || date == null || startTime == null || endTime == null
                || capacity <= 0 || fee == null || fee.signum() < 0 || !endTime.after(startTime)
                || !("PUBLISHED".equals(status) || "WITHDRAWN".equals(status))) {
            throw new IllegalArgumentException("invalid slot");
        }
        this.departmentId = departmentId;
        this.doctorId = doctorId;
        this.date = date;
        this.startTime = startTime;
        this.endTime = endTime;
        this.capacity = capacity;
        this.fee = fee;
        this.status = status;
    }
}
