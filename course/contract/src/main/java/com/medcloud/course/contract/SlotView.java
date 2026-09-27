package com.medcloud.course.contract;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;

public final class SlotView {
    private final String id;
    private final String tenantId;
    private final String departmentId;
    private final String doctorId;
    private final Date date;
    private final Time startTime;
    private final Time endTime;
    private final int capacity;
    private final BigDecimal fee;
    private final int remaining;
    private final String status;

    public SlotView(String id, String tenantId, String departmentId, String doctorId, Date date,
                    Time startTime, Time endTime, int capacity, BigDecimal fee) {
        this(id, tenantId, departmentId, doctorId, date, startTime, endTime, capacity, fee, capacity);
    }

    public SlotView(String id, String tenantId, String departmentId, String doctorId, Date date,
                    Time startTime, Time endTime, int capacity, BigDecimal fee, int remaining) {
        this(id, tenantId, departmentId, doctorId, date, startTime, endTime, capacity, fee, remaining, "PUBLISHED");
    }

    public SlotView(String id, String tenantId, String departmentId, String doctorId, Date date,
                    Time startTime, Time endTime, int capacity, BigDecimal fee, int remaining, String status) {
        this.id = id;
        this.tenantId = tenantId;
        this.departmentId = departmentId;
        this.doctorId = doctorId;
        this.date = date;
        this.startTime = startTime;
        this.endTime = endTime;
        this.capacity = capacity;
        this.fee = fee;
        this.remaining = remaining;
        this.status = status;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getDepartmentId() { return departmentId; }
    public String getDoctorId() { return doctorId; }
    public Date getDate() { return date; }
    public Time getStartTime() { return startTime; }
    public Time getEndTime() { return endTime; }
    public int getCapacity() { return capacity; }
    public BigDecimal getFee() { return fee; }
    public int getRemaining() { return remaining; }
    public String getStatus() { return status; }
}
