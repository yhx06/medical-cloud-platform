package com.medcloud.course.paas.appointment;

import com.medcloud.course.contract.Role;
import com.medcloud.course.contract.AppointmentHistoryView;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.AccessDeniedException;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.tenant.Tenant;
import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.DoctorProfile;
import com.medcloud.course.paas.tenant.TenantAccessPolicy;

import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceContext;
import java.sql.Date;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Stateless
public class BookingService {
    @PersistenceContext(unitName = "medicalCloudCourse")
    private EntityManager manager;

    public BookingService() { }

    public BookingService(EntityManager manager) { this.manager = manager; }

    public Appointment book(CurrentActor actor, String slotId, Date today) {
        Account patient = new TenantAccessPolicy(manager).requireAccount(actor, Role.PATIENT);
        manager.flush();
        ScheduleSlot candidate = manager.find(ScheduleSlot.class, slotId);
        if (candidate == null) throw new IllegalStateException("slot unavailable");
        String tenantId = candidate.getTenantId();
        new TenantAccessPolicy(manager).lockWritableTenant(tenantId);
        String candidateDepartmentId = candidate.getDepartmentId();
        String candidateDoctorId = candidate.getDoctorId();
        // Tenant -> department -> doctor -> slot -> appointment.
        Department department = manager.find(Department.class, candidateDepartmentId, LockModeType.PESSIMISTIC_READ);
        if (department == null) throw new IllegalStateException("slot directory unavailable");
        manager.refresh(department, LockModeType.PESSIMISTIC_READ);
        // Lock the doctor before the slot so a concurrent disable cannot pass its pending count.
        Account doctor = manager.find(Account.class, candidateDoctorId, LockModeType.PESSIMISTIC_WRITE);
        if (doctor == null) throw new IllegalStateException("slot directory unavailable");
        manager.refresh(doctor, LockModeType.PESSIMISTIC_WRITE);
        ScheduleSlot slot = manager.find(ScheduleSlot.class, slotId, LockModeType.PESSIMISTIC_WRITE);
        if (slot == null) throw new IllegalStateException("slot unavailable");
        manager.refresh(slot, LockModeType.PESSIMISTIC_WRITE);
        Instant start = LocalDateTime.of(slot.getDate().toLocalDate(), slot.getStartTime().toLocalTime())
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        if (!"PUBLISHED".equals(slot.getStatus()) || slot.getDate().before(today) || !Instant.now().isBefore(start)) {
            throw new IllegalStateException("slot unavailable");
        }
        if (!tenantId.equals(slot.getTenantId()) || !candidateDoctorId.equals(slot.getDoctorId())
                || !candidateDepartmentId.equals(slot.getDepartmentId())) {
            throw new IllegalStateException("slot directory changed");
        }
        DoctorProfile profile = manager.find(DoctorProfile.class, candidateDoctorId);
        if (profile != null) manager.refresh(profile, LockModeType.PESSIMISTIC_READ);
        if (!"ACTIVE".equals(doctor.getStatus()) || doctor.getRole() != Role.DOCTOR
                || !slot.getTenantId().equals(doctor.getTenantId())
                || !"ACTIVE".equals(department.getStatus()) || !slot.getTenantId().equals(department.getTenantId())
                || profile == null || !slot.getTenantId().equals(profile.getTenantId())
                || !slot.getDepartmentId().equals(profile.getDepartmentId())) {
            throw new IllegalStateException("slot directory unavailable");
        }
        List<Appointment> existing = manager.createQuery(
                "select a from Appointment a where a.slotId = :slotId and a.status <> 'CANCELED'", Appointment.class)
                .setParameter("slotId", slotId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        for (Appointment appointment : existing) {
            if (appointment.getPatientId().equals(actor.getUserId())) {
                throw new IllegalStateException("already booked");
            }
        }
        if (existing.size() >= slot.getCapacity()) {
            throw new IllegalStateException("slot full");
        }
        Appointment appointment = new Appointment(UUID.randomUUID().toString(), patient.getId(),
                slot.getId(), slot.getTenantId(), slot.getDoctorId(), slot.getFee(), today);
        manager.persist(appointment);
        return appointment;
    }

    public List<Appointment> forDoctor(CurrentActor actor) {
        TenantAccessPolicy policy = new TenantAccessPolicy(manager);
        Account doctor = policy.requireAccount(actor, Role.DOCTOR);
        policy.readableTenant(doctor.getTenantId());
        return manager.createQuery("select a from Appointment a where a.doctorId = :doctorId "
                + "and a.tenantId = :tenantId order by a.bookedOn, a.id", Appointment.class)
                .setParameter("doctorId", doctor.getId()).setParameter("tenantId", doctor.getTenantId())
                .getResultList();
    }

    public Appointment cancel(CurrentActor actor, String appointmentId, Instant now) {
        requirePatient(actor);
        if (appointmentId == null || now == null) throw new IllegalArgumentException("appointment and time required");
        Appointment candidate = manager.find(Appointment.class, appointmentId);
        if (candidate == null || !actor.getUserId().equals(candidate.getPatientId())) throw new AccessDeniedException();
        String tenantId = candidate.getTenantId();
        new TenantAccessPolicy(manager).lockWritableTenant(tenantId);
        ScheduleSlot slot = manager.find(ScheduleSlot.class, candidate.getSlotId(), LockModeType.PESSIMISTIC_WRITE);
        if (slot == null) throw new IllegalStateException("slot unavailable");
        manager.refresh(slot, LockModeType.PESSIMISTIC_WRITE);
        Appointment appointment = manager.find(Appointment.class, appointmentId, LockModeType.PESSIMISTIC_WRITE);
        if (appointment == null) throw new AccessDeniedException();
        manager.refresh(appointment, LockModeType.PESSIMISTIC_WRITE);
        if (!actor.getUserId().equals(appointment.getPatientId()) || !slot.getId().equals(appointment.getSlotId())
                || !tenantId.equals(appointment.getTenantId()) || !tenantId.equals(slot.getTenantId())
                || !appointment.getDoctorId().equals(slot.getDoctorId()))
            throw new AccessDeniedException();
        LocalDateTime start = LocalDateTime.of(slot.getDate().toLocalDate(), slot.getStartTime().toLocalTime());
        if (!now.isBefore(start.atZone(ZoneId.of("Asia/Shanghai")).toInstant()))
            throw new IllegalStateException("appointment already started");
        appointment.cancel();
        return appointment;
    }

    public List<AppointmentHistoryView> forPatient(CurrentActor actor) {
        requirePatient(actor);
        List<Appointment> rows = manager.createQuery("select a from Appointment a where a.patientId = :patientId "
                + "order by a.bookedOn desc, a.bookedAt desc, a.id desc", Appointment.class)
                .setParameter("patientId", actor.getUserId()).setMaxResults(100).getResultList();
        java.util.ArrayList<AppointmentHistoryView> views = new java.util.ArrayList<AppointmentHistoryView>();
        for (Appointment appointment : rows) {
            ScheduleSlot slot = manager.find(ScheduleSlot.class, appointment.getSlotId());
            Tenant tenant = new TenantAccessPolicy(manager).readableTenant(appointment.getTenantId());
            Department department = slot == null ? null : manager.find(Department.class, slot.getDepartmentId());
            DoctorProfile doctor = manager.find(DoctorProfile.class, appointment.getDoctorId());
            views.add(new AppointmentHistoryView(appointment.getId(), appointment.getStatus(),
                    appointment.getBookedOn(), appointment.getBookedAt(), appointment.getTenantId(), tenant == null ? "" : tenant.getName(),
                    slot == null ? "" : slot.getDepartmentId(), department == null ? "" : department.getName(),
                    appointment.getDoctorId(), doctor == null ? "" : doctor.getDisplayName(),
                    appointment.getSlotId(), slot == null ? null : slot.getDate(),
                    slot == null ? null : slot.getStartTime(), slot == null ? null : slot.getEndTime(),
                    tenant.getStatus(), "ACTIVE".equals(tenant.getStatus())));
        }
        return views;
    }

    private void requirePatient(CurrentActor actor) {
        new TenantAccessPolicy(manager).requireAccount(actor, Role.PATIENT);
    }
}
