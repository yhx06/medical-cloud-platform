package com.medcloud.course.contract;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;

public class SlotRequest {
    private String departmentId;
    private String doctorId;
    private Date date;
    private Time startTime;
    private Time endTime;
    private int capacity;
    private BigDecimal fee;
    private String status;

    public SlotRequest() { }
    public SlotRequest(String departmentId, String doctorId, Date date, Time startTime, Time endTime,
                       int capacity, BigDecimal fee, String status) {
        this.departmentId = departmentId; this.doctorId = doctorId; this.date = date;
        this.startTime = startTime; this.endTime = endTime; this.capacity = capacity; this.fee = fee; this.status = status;
    }
    public String getDepartmentId() { return departmentId; }
    public String getDoctorId() { return doctorId; }
    public Date getDate() { return date; }
    public Time getStartTime() { return startTime; }
    public Time getEndTime() { return endTime; }
    public int getCapacity() { return capacity; }
    public BigDecimal getFee() { return fee; }
    public String getStatus() { return status; }
    public void setDepartmentId(String value) { departmentId = value; }
    public void setDoctorId(String value) { doctorId = value; }
    public void setDate(Date value) { date = value; }
    public void setStartTime(Time value) { startTime = value; }
    public void setEndTime(Time value) { endTime = value; }
    public void setCapacity(int value) { capacity = value; }
    public void setFee(BigDecimal value) { fee = value; }
    public void setStatus(String value) { status = value; }
}
