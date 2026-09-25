package com.medcloud.platform.consultation.infrastructure;

import com.medcloud.platform.consultation.domain.Consultation;
import com.medcloud.platform.consultation.domain.ConsultationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsultationRepository extends JpaRepository<Consultation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select consultation from Consultation consultation where consultation.appointmentId = :appointmentId")
    Optional<Consultation> findByAppointmentIdForUpdate(@Param("appointmentId") UUID appointmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select consultation from Consultation consultation where consultation.id = :id")
    Optional<Consultation> findByIdForUpdate(@Param("id") UUID id);

    List<Consultation> findAllByPatientIdAndStatusOrderByCompletedAtDesc(
            UUID patientId,
            ConsultationStatus status
    );

    Optional<Consultation> findByIdAndPatientIdAndStatus(
            UUID id,
            UUID patientId,
            ConsultationStatus status
    );

    List<Consultation> findAllByDoctorIdAndTenantIdOrderByUpdatedAtDesc(UUID doctorId, UUID tenantId);

    Optional<Consultation> findByIdAndDoctorIdAndTenantId(UUID id, UUID doctorId, UUID tenantId);
}
