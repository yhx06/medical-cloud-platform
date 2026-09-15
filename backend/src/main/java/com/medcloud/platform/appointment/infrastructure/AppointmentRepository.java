package com.medcloud.platform.appointment.infrastructure;

import com.medcloud.platform.appointment.domain.Appointment;
import com.medcloud.platform.appointment.domain.AppointmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select appointment from Appointment appointment where appointment.id = :id")
    Optional<Appointment> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByPatientIdAndSlotIdAndStatusNot(
            UUID patientId,
            UUID slotId,
            AppointmentStatus excludedStatus
    );

    long countBySlotIdAndStatusNot(UUID slotId, AppointmentStatus excludedStatus);

    Optional<Appointment> findByIdAndPatientId(UUID id, UUID patientId);

    List<Appointment> findAllByPatientIdOrderByAppointmentDateDescStartTimeDescCreatedAtDesc(UUID patientId);

    List<Appointment> findAllByDoctorIdAndTenantIdOrderByAppointmentDateAscStartTimeAscCreatedAtAsc(
            UUID doctorId,
            UUID tenantId
    );

}
