package com.medcloud.course.contract;

import java.math.BigDecimal;

public final class AppointmentView {
    private final String id;
    private final String patientId;
    private final String slotId;
    private final String tenantId;
    private final String doctorId;
    private final BigDecimal fee;
    private final String status;

    public AppointmentView(String id, String patientId, String slotId, String tenantId,
                           String doctorId, BigDecimal fee, String status) {
        this.id = id;
        this.patientId = patientId;
        this.slotId = slotId;
        this.tenantId = tenantId;
        this.doctorId = doctorId;
        this.fee = fee;
        this.status = status;
    }

    public String getId() { return id; }
    public String getPatientId() { return patientId; }
    public String getSlotId() { return slotId; }
    public String getTenantId() { return tenantId; }
    public String getDoctorId() { return doctorId; }
    public BigDecimal getFee() { return fee; }
    public String getStatus() { return status; }
}
