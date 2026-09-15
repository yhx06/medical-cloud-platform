package com.medcloud.platform.order.domain;

import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.consultation.application.ConsultationCompletedEvent;
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
import java.util.UUID;

@Entity
@Table(name = "medical_order")
public class MedicalOrder {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "consultation_id", nullable = false, unique = true, updatable = false)
    private UUID consultationId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "appointment_id", nullable = false, updatable = false)
    private UUID appointmentId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "transaction_no", unique = true, length = 64)
    private String transactionNo;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MedicalOrder() {
    }

    private MedicalOrder(ConsultationCompletedEvent event) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.consultationId = event.consultationId();
        this.appointmentId = event.appointmentId();
        this.tenantId = event.tenantId();
        this.patientId = event.patientId();
        this.amount = event.fee();
        this.status = OrderStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static MedicalOrder from(ConsultationCompletedEvent event) {
        return new MedicalOrder(event);
    }

    public void markPaid(String transactionNo, Instant paidAt) {
        if (status != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.ORDER_STATE_INVALID);
        }
        this.status = OrderStatus.PAID;
        this.transactionNo = transactionNo;
        this.paidAt = paidAt;
        this.updatedAt = paidAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getConsultationId() {
        return consultationId;
    }

    public UUID getAppointmentId() {
        return appointmentId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getTransactionNo() {
        return transactionNo;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
