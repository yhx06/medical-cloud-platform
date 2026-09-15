package com.medcloud.platform.appointment.infrastructure;

import com.medcloud.platform.appointment.domain.Appointment;
import com.medcloud.platform.appointment.domain.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {
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
