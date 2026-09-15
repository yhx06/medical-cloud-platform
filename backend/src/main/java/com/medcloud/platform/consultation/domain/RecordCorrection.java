package com.medcloud.platform.consultation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "record_correction")
public class RecordCorrection {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consultation_id", nullable = false, updatable = false)
    private Consultation consultation;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private int sequenceNumber;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "doctor_id", nullable = false, updatable = false)
    private UUID doctorId;

    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RecordCorrection() {
    }

    private RecordCorrection(Consultation consultation, int sequenceNumber, UUID doctorId, String reason) {
        this.id = UUID.randomUUID();
        this.consultation = consultation;
        this.sequenceNumber = sequenceNumber;
        this.doctorId = doctorId;
        this.reason = reason.trim();
        this.createdAt = Instant.now();
    }

    static RecordCorrection create(
            Consultation consultation,
            int sequenceNumber,
            UUID doctorId,
            String reason
    ) {
        return new RecordCorrection(consultation, sequenceNumber, doctorId, reason);
    }

    public UUID getId() {
        return id;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public UUID getDoctorId() {
        return doctorId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
