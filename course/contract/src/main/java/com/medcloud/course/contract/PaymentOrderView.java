package com.medcloud.course.contract;

import java.math.BigDecimal;

public final class PaymentOrderView {
    private final String id;
    private final String appointmentId;
    private final BigDecimal amount;
    private final String status;

    public PaymentOrderView(String id, String appointmentId, BigDecimal amount, String status) {
        this.id = id;
        this.appointmentId = appointmentId;
        this.amount = amount;
        this.status = status;
    }
    public String getId() { return id; }
    public String getAppointmentId() { return appointmentId; }
    public BigDecimal getAmount() { return amount; }
    public String getStatus() { return status; }
}
