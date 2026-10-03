package com.medcloud.course.contract;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class PaymentOrderView {
    private final String id;
    private final String appointmentId;
    private final BigDecimal amount;
    private final String status;
    private final String paidAt;
    private final boolean businessWritable;

    public PaymentOrderView(String id, String appointmentId, BigDecimal amount, String status, Timestamp paidAt) {
        this(id, appointmentId, amount, status, paidAt, false);
    }

    public PaymentOrderView(String id, String appointmentId, BigDecimal amount, String status, Timestamp paidAt,
            boolean businessWritable) {
        this.id = id;
        this.appointmentId = appointmentId;
        this.amount = amount;
        this.status = status;
        this.businessWritable = businessWritable;
        this.paidAt = paidAt == null ? null : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("Asia/Shanghai")).format(paidAt.toInstant());
    }
    public String getId() { return id; }
    public String getAppointmentId() { return appointmentId; }
    public BigDecimal getAmount() { return amount; }
    public String getStatus() { return status; }
    public String getPaidAt() { return paidAt; }
    public boolean isBusinessWritable() { return businessWritable; }
}
