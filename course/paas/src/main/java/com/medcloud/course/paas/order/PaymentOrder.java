package com.medcloud.course.paas.order;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

@Entity
@Table(name = "payment_order", uniqueConstraints = @UniqueConstraint(columnNames = "appointment_id"))
public class PaymentOrder {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "appointment_id", nullable = false, length = 36)
    private String appointmentId;

    @Column(name = "patient_id", nullable = false, length = 36)
    private String patientId;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "paid_at")
    private Timestamp paidAt;

    protected PaymentOrder() { }

    public PaymentOrder(String id, String appointmentId, String patientId, String tenantId, BigDecimal amount) {
        this.id = id;
        this.appointmentId = appointmentId;
        this.patientId = patientId;
        this.tenantId = tenantId;
        this.amount = amount;
        this.status = "UNPAID";
    }

    public void pay() {
        if (!"PAID".equals(status)) {
            status = "PAID";
        }
    }

    public void pay(Instant now) {
        if (now == null) throw new IllegalArgumentException("payment time required");
        if (!"PAID".equals(status)) {
            status = "PAID";
            paidAt = Timestamp.from(now);
        }
    }

    public String getId() { return id; }
    public String getAppointmentId() { return appointmentId; }
    public String getPatientId() { return patientId; }
    public String getTenantId() { return tenantId; }
    public BigDecimal getAmount() { return amount; }
    public String getStatus() { return status; }
    public Timestamp getPaidAt() { return paidAt; }
}
