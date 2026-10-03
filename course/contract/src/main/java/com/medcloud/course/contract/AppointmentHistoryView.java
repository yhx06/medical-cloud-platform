package com.medcloud.course.contract;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;

public final class AppointmentHistoryView {
    private final String id;
    private final String status;
    private final Date bookedOn;
    private final Timestamp bookedAt;
    private final String tenantId;
    private final String hospitalName;
    private final String departmentId;
    private final String departmentName;
    private final String doctorId;
    private final String doctorName;
    private final String slotId;
    private final Date slotDate;
    private final Time startTime;
    private final Time endTime;
    private final String tenantStatus;
    private final boolean businessWritable;

    public AppointmentHistoryView(String id, String status, Date bookedOn, Timestamp bookedAt, String tenantId,
            String hospitalName, String departmentId, String departmentName, String doctorId,
            String doctorName, String slotId, Date slotDate, Time startTime, Time endTime) {
        this(id, status, bookedOn, bookedAt, tenantId, hospitalName, departmentId, departmentName, doctorId,
                doctorName, slotId, slotDate, startTime, endTime, null, false);
    }

    public AppointmentHistoryView(String id, String status, Date bookedOn, Timestamp bookedAt, String tenantId,
            String hospitalName, String departmentId, String departmentName, String doctorId,
            String doctorName, String slotId, Date slotDate, Time startTime, Time endTime,
            String tenantStatus, boolean businessWritable) {
        this.id = id; this.status = status; this.bookedOn = bookedOn; this.bookedAt = bookedAt; this.tenantId = tenantId;
        this.hospitalName = hospitalName; this.departmentId = departmentId;
        this.departmentName = departmentName; this.doctorId = doctorId; this.doctorName = doctorName;
        this.slotId = slotId; this.slotDate = slotDate; this.startTime = startTime; this.endTime = endTime;
        this.tenantStatus = tenantStatus; this.businessWritable = businessWritable;
    }
    public String getId() { return id; }
    public String getStatus() { return status; }
    public Date getBookedOn() { return bookedOn; }
    public Timestamp getBookedAt() { return bookedAt; }
    public String getTenantId() { return tenantId; }
    public String getHospitalName() { return hospitalName; }
    public String getDepartmentId() { return departmentId; }
    public String getDepartmentName() { return departmentName; }
    public String getDoctorId() { return doctorId; }
    public String getDoctorName() { return doctorName; }
    public String getSlotId() { return slotId; }
    public Date getSlotDate() { return slotDate; }
    public Time getStartTime() { return startTime; }
    public Time getEndTime() { return endTime; }
    public String getTenantStatus() { return tenantStatus; }
    public boolean isBusinessWritable() { return businessWritable; }
}
