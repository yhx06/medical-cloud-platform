package com.medcloud.platform.consultation.domain;

import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
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
@Table(name = "prescription_item")
public class PrescriptionItem {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consultation_id", nullable = false, updatable = false)
    private Consultation consultation;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private int sequenceNumber;

    @Column(name = "medication_name", nullable = false, length = 200)
    private String medicationName;

    @Column(nullable = false, length = 100)
    private String dosage;

    @Column(nullable = false, length = 100)
    private String frequency;

    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PrescriptionItem() {
    }

    private PrescriptionItem(Consultation consultation, int sequenceNumber, Draft draft) {
        validate(draft);
        this.id = UUID.randomUUID();
        this.consultation = consultation;
        this.sequenceNumber = sequenceNumber;
        replaceWith(draft);
        this.createdAt = Instant.now();
    }

    static PrescriptionItem create(Consultation consultation, int sequenceNumber, Draft draft) {
        return new PrescriptionItem(consultation, sequenceNumber, draft);
    }

    static void validate(Draft draft) {
        if (draft == null
                || isBlank(draft.medicationName())
                || isBlank(draft.dosage())
                || isBlank(draft.frequency())
                || draft.durationDays() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    void replaceWith(Draft draft) {
        validate(draft);
        this.medicationName = draft.medicationName().trim();
        this.dosage = draft.dosage().trim();
        this.frequency = draft.frequency().trim();
        this.durationDays = draft.durationDays();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public UUID getId() {
        return id;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public String getMedicationName() {
        return medicationName;
    }

    public String getDosage() {
        return dosage;
    }

    public String getFrequency() {
        return frequency;
    }

    public int getDurationDays() {
        return durationDays;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public record Draft(
            String medicationName,
            String dosage,
            String frequency,
            int durationDays
    ) {
    }
}
