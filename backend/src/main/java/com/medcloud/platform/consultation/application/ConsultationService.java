package com.medcloud.platform.consultation.application;

import com.medcloud.platform.appointment.domain.Appointment;
import com.medcloud.platform.appointment.infrastructure.AppointmentRepository;
import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.consultation.domain.Consultation;
import com.medcloud.platform.consultation.domain.ConsultationStatus;
import com.medcloud.platform.consultation.domain.PrescriptionItem;
import com.medcloud.platform.consultation.domain.RecordCorrection;
import com.medcloud.platform.consultation.infrastructure.ConsultationRepository;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.security.CurrentUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ConsultationService {
    private final AppointmentRepository appointments;
    private final ConsultationRepository consultations;
    private final ApplicationEventPublisher events;

    public ConsultationService(
            AppointmentRepository appointments,
            ConsultationRepository consultations,
            ApplicationEventPublisher events
    ) {
        this.appointments = appointments;
        this.consultations = consultations;
        this.events = events;
    }

    @Transactional
    public MedicalRecordResponse start(CurrentUser doctor, UUID appointmentId) {
        requireDoctor(doctor);
        Appointment appointment = appointments.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        requireAssignedDoctor(doctor, appointment);
        appointment.startConsultation();
        Consultation consultation = consultations.saveAndFlush(Consultation.start(appointment));
        appointments.flush();
        return response(consultation);
    }

    @Transactional
    public MedicalRecordResponse saveDraft(CurrentUser doctor, UUID appointmentId, DraftCommand draft) {
        requireDoctor(doctor);
        if (draft == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Appointment appointment = appointments.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        requireAssignedDoctor(doctor, appointment);
        Consultation consultation = consultations.findByAppointmentIdForUpdate(appointmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        consultation.saveDraft(
                draft.complaint(),
                draft.diagnosis(),
                draft.advice(),
                prescriptionDrafts(draft.prescriptionItems()));
        consultations.flush();
        return response(consultation);
    }

    @Transactional
    public MedicalRecordResponse complete(CurrentUser doctor, UUID appointmentId) {
        requireDoctor(doctor);
        Appointment appointment = appointments.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        requireAssignedDoctor(doctor, appointment);
        Consultation consultation = consultations.findByAppointmentIdForUpdate(appointmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        consultation.complete();
        appointment.completeConsultation();
        consultations.flush();
        appointments.flush();
        events.publishEvent(new ConsultationCompletedEvent(
                consultation.getId(),
                appointment.getId(),
                appointment.getPatientId(),
                appointment.getTenantId(),
                appointment.getFee(),
                appointment.getDoctorId()));
        return response(consultation);
    }

    @Transactional
    public MedicalRecordResponse appendCorrection(
            CurrentUser doctor,
            UUID recordId,
            CorrectionCommand command
    ) {
        requireDoctor(doctor);
        if (command == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Consultation consultation = consultations.findByIdForUpdate(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        requireAssignedDoctor(doctor, consultation);
        consultation.appendCorrection(doctor.userId(), command.reason());
        consultations.flush();
        return response(consultation);
    }

    @Transactional(readOnly = true)
    public List<MedicalRecordResponse> patientRecords(CurrentUser patient) {
        requirePatient(patient);
        return consultations.findAllByPatientIdAndStatusOrderByCompletedAtDesc(
                        patient.userId(), ConsultationStatus.COMPLETED)
                .stream()
                .map(ConsultationService::response)
                .toList();
    }

    @Transactional(readOnly = true)
    public MedicalRecordResponse patientRecord(CurrentUser patient, UUID recordId) {
        requirePatient(patient);
        return consultations.findByIdAndPatientIdAndStatus(
                        recordId, patient.userId(), ConsultationStatus.COMPLETED)
                .map(ConsultationService::response)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static List<PrescriptionItem.Draft> prescriptionDrafts(List<PrescriptionItemCommand> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .map(item -> item == null
                        ? null
                        : new PrescriptionItem.Draft(
                                item.medicationName(),
                                item.dosage(),
                                item.frequency(),
                                item.durationDays()))
                .toList();
    }

    private static void requireDoctor(CurrentUser actor) {
        if (actor == null || actor.userId() == null || actor.role() != UserRole.DOCTOR
                || actor.tenantId() == null) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static void requirePatient(CurrentUser actor) {
        if (actor == null || actor.userId() == null || actor.role() != UserRole.PATIENT) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static void requireAssignedDoctor(CurrentUser doctor, Appointment appointment) {
        if (!appointment.getDoctorId().equals(doctor.userId())
                || !appointment.getTenantId().equals(doctor.tenantId())) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static void requireAssignedDoctor(CurrentUser doctor, Consultation consultation) {
        if (!consultation.getDoctorId().equals(doctor.userId())
                || !consultation.getTenantId().equals(doctor.tenantId())) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static MedicalRecordResponse response(Consultation consultation) {
        return new MedicalRecordResponse(
                consultation.getId(),
                consultation.getAppointmentId(),
                consultation.getTenantId(),
                consultation.getPatientId(),
                consultation.getDoctorId(),
                consultation.getStatus(),
                consultation.getComplaint(),
                consultation.getDiagnosis(),
                consultation.getAdvice(),
                consultation.getPrescriptionItems().stream()
                        .map(ConsultationService::prescriptionResponse)
                        .toList(),
                consultation.getCorrections().stream()
                        .map(ConsultationService::correctionResponse)
                        .toList(),
                consultation.getDraftSavedAt(),
                consultation.getCompletedAt(),
                consultation.getCreatedAt(),
                consultation.getUpdatedAt());
    }

    private static PrescriptionItemResponse prescriptionResponse(PrescriptionItem item) {
        return new PrescriptionItemResponse(
                item.getId(),
                item.getSequenceNumber(),
                item.getMedicationName(),
                item.getDosage(),
                item.getFrequency(),
                item.getDurationDays());
    }

    private static RecordCorrectionResponse correctionResponse(RecordCorrection correction) {
        return new RecordCorrectionResponse(
                correction.getId(),
                correction.getSequenceNumber(),
                correction.getDoctorId(),
                correction.getReason(),
                correction.getCreatedAt());
    }

    public record DraftCommand(
            String complaint,
            String diagnosis,
            String advice,
            List<PrescriptionItemCommand> prescriptionItems
    ) {
    }

    public record PrescriptionItemCommand(
            String medicationName,
            String dosage,
            String frequency,
            int durationDays
    ) {
    }

    public record CorrectionCommand(String reason) {
    }

    public record MedicalRecordResponse(
            UUID id,
            UUID appointmentId,
            UUID tenantId,
            UUID patientId,
            UUID doctorId,
            ConsultationStatus status,
            String complaint,
            String diagnosis,
            String advice,
            List<PrescriptionItemResponse> prescriptionItems,
            List<RecordCorrectionResponse> corrections,
            Instant draftSavedAt,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record PrescriptionItemResponse(
            UUID id,
            int sequenceNumber,
            String medicationName,
            String dosage,
            String frequency,
            int durationDays
    ) {
    }

    public record RecordCorrectionResponse(
            UUID id,
            int sequenceNumber,
            UUID doctorId,
            String reason,
            Instant createdAt
    ) {
    }
}
