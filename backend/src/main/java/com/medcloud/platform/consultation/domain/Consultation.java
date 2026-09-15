package com.medcloud.platform.consultation.domain;

import com.medcloud.platform.appointment.domain.Appointment;
import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "consultation")
public class Consultation {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "appointment_id", nullable = false, unique = true, updatable = false)
    private UUID appointmentId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "doctor_id", nullable = false, updatable = false)
    private UUID doctorId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String complaint;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String diagnosis;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String advice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ConsultationStatus status;

    @Column(name = "draft_saved_at")
    private Instant draftSavedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "consultation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequenceNumber ASC")
    private List<PrescriptionItem> prescriptionItems = new ArrayList<>();

    @OneToMany(mappedBy = "consultation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequenceNumber ASC")
    private List<RecordCorrection> corrections = new ArrayList<>();

    protected Consultation() {
    }

    private Consultation(Appointment appointment) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.appointmentId = appointment.getId();
        this.tenantId = appointment.getTenantId();
        this.patientId = appointment.getPatientId();
        this.doctorId = appointment.getDoctorId();
        this.complaint = "";
        this.diagnosis = "";
        this.advice = "";
        this.status = ConsultationStatus.IN_PROGRESS;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Consultation start(Appointment appointment) {
        if (appointment == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return new Consultation(appointment);
    }

    public void saveDraft(
            String complaint,
            String diagnosis,
            String advice,
        List<PrescriptionItem.Draft> items
    ) {
        requireEditable();
        List<PrescriptionItem.Draft> safeItems = items == null ? List.of() : items;
        safeItems.forEach(PrescriptionItem::validate);
        this.complaint = textOrEmpty(complaint);
        this.diagnosis = textOrEmpty(diagnosis);
        this.advice = textOrEmpty(advice);
        int sharedSize = Math.min(prescriptionItems.size(), safeItems.size());
        for (int index = 0; index < sharedSize; index++) {
            prescriptionItems.get(index).replaceWith(safeItems.get(index));
        }
        while (prescriptionItems.size() > safeItems.size()) {
            prescriptionItems.removeLast();
        }
        for (int index = sharedSize; index < safeItems.size(); index++) {
            prescriptionItems.add(PrescriptionItem.create(this, index + 1, safeItems.get(index)));
        }
        Instant now = Instant.now();
        this.draftSavedAt = now;
        this.updatedAt = now;
    }

    public void complete() {
        requireEditable();
        if (complaint.isBlank() || diagnosis.isBlank() || advice.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Instant now = Instant.now();
        this.status = ConsultationStatus.COMPLETED;
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void appendCorrection(UUID authorDoctorId, String reason) {
        if (status != ConsultationStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.APPOINTMENT_STATE_INVALID);
        }
        if (authorDoctorId == null || !doctorId.equals(authorDoctorId)) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        corrections.add(RecordCorrection.create(this, corrections.size() + 1, authorDoctorId, reason));
        updatedAt = Instant.now();
    }

    private void requireEditable() {
        if (status == ConsultationStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.MEDICAL_RECORD_COMPLETED);
        }
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }

    public UUID getId() {
        return id;
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

    public UUID getDoctorId() {
        return doctorId;
    }

    public String getComplaint() {
        return complaint;
    }

    public String getDiagnosis() {
        return diagnosis;
    }

    public String getAdvice() {
        return advice;
    }

    public ConsultationStatus getStatus() {
        return status;
    }

    public Instant getDraftSavedAt() {
        return draftSavedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<PrescriptionItem> getPrescriptionItems() {
        return List.copyOf(prescriptionItems);
    }

    public List<RecordCorrection> getCorrections() {
        return List.copyOf(corrections);
    }
}
