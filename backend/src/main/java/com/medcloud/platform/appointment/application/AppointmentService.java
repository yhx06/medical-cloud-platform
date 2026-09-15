package com.medcloud.platform.appointment.application;

import com.medcloud.platform.appointment.domain.Appointment;
import com.medcloud.platform.appointment.domain.AppointmentStatus;
import com.medcloud.platform.appointment.infrastructure.AppointmentRepository;
import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.schedule.domain.ScheduleSlot;
import com.medcloud.platform.schedule.domain.ScheduleSlotStatus;
import com.medcloud.platform.schedule.infrastructure.ScheduleSlotRepository;
import com.medcloud.platform.security.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AppointmentService {
    private static final String ACTIVE_BOOKING_CONSTRAINT = "uk_appointment_active_booking";

    private final AppointmentRepository appointments;
    private final ScheduleSlotRepository slots;

    public AppointmentService(AppointmentRepository appointments, ScheduleSlotRepository slots) {
        this.appointments = appointments;
        this.slots = slots;
    }

    @Transactional
    public AppointmentResponse book(CurrentUser patient, UUID slotId) {
        requirePatient(patient);
        ScheduleSlot slot = slots.findByIdForUpdate(slotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_UNAVAILABLE));
        requireBookable(slot);
        if (appointments.existsByPatientIdAndSlotIdAndStatusNot(
                patient.userId(), slot.getId(), AppointmentStatus.CANCELLED)) {
            throw new BusinessException(ErrorCode.APPOINTMENT_DUPLICATE);
        }
        if (appointments.countBySlotIdAndStatusNot(slot.getId(), AppointmentStatus.CANCELLED)
                >= slot.getCapacity()) {
            throw new BusinessException(ErrorCode.SCHEDULE_FULL);
        }

        try {
            return response(appointments.saveAndFlush(Appointment.book(patient.userId(), slot)));
        } catch (DataIntegrityViolationException exception) {
            if (causedByConstraint(exception, ACTIVE_BOOKING_CONSTRAINT)) {
                throw new BusinessException(ErrorCode.APPOINTMENT_DUPLICATE);
            }
            throw exception;
        }
    }

    @Transactional
    public AppointmentResponse cancel(CurrentUser patient, UUID appointmentId) {
        requirePatient(patient);
        Appointment initial = appointments.findByIdAndPatientId(appointmentId, patient.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        slots.findByIdForUpdate(initial.getSlotId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_UNAVAILABLE));
        Appointment appointment = appointments.findByIdAndPatientId(appointmentId, patient.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        appointment.cancel();
        appointments.flush();
        return response(appointment);
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> patientAppointments(CurrentUser patient) {
        requirePatient(patient);
        return appointments.findAllByPatientIdOrderByAppointmentDateDescStartTimeDescCreatedAtDesc(patient.userId())
                .stream().map(AppointmentService::response).toList();
    }

    @Transactional(readOnly = true)
    public AppointmentResponse patientAppointment(CurrentUser patient, UUID appointmentId) {
        requirePatient(patient);
        return appointments.findByIdAndPatientId(appointmentId, patient.userId())
                .map(AppointmentService::response)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> doctorAppointments(CurrentUser doctor) {
        requireDoctor(doctor);
        return appointments
                .findAllByDoctorIdAndTenantIdOrderByAppointmentDateAscStartTimeAscCreatedAtAsc(
                        doctor.userId(), doctor.tenantId())
                .stream().map(AppointmentService::response).toList();
    }

    private static void requireBookable(ScheduleSlot slot) {
        if (slot.getStatus() != ScheduleSlotStatus.PUBLISHED
                || slot.getScheduleDate().isBefore(LocalDate.now())) {
            throw new BusinessException(ErrorCode.SCHEDULE_UNAVAILABLE);
        }
    }

    private static void requirePatient(CurrentUser actor) {
        if (actor == null || actor.userId() == null || actor.role() != UserRole.PATIENT) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static void requireDoctor(CurrentUser actor) {
        if (actor == null || actor.userId() == null || actor.role() != UserRole.DOCTOR
                || actor.tenantId() == null) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static boolean causedByConstraint(Throwable exception, String constraintName) {
        String expected = constraintName.toLowerCase(Locale.ROOT);
        for (Throwable current = exception; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private static AppointmentResponse response(Appointment appointment) {
        return new AppointmentResponse(
                appointment.getId(),
                appointment.getTenantId(),
                appointment.getPatientId(),
                appointment.getDoctorId(),
                appointment.getDepartmentId(),
                appointment.getSlotId(),
                appointment.getAppointmentDate(),
                appointment.getStartTime(),
                appointment.getEndTime(),
                appointment.getFee(),
                appointment.getStatus(),
                appointment.getCreatedAt(),
                appointment.getUpdatedAt());
    }

    public record AppointmentResponse(
            UUID id,
            UUID tenantId,
            UUID patientId,
            UUID doctorId,
            UUID departmentId,
            UUID slotId,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            BigDecimal fee,
            AppointmentStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
