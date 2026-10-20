package com.medcloud.course.paas.appointment;

import com.medcloud.course.paas.audit.*;
import com.medcloud.course.contract.AuditEventType;
import com.medcloud.course.contract.AuditResult;
import javax.ejb.EJB;

import com.medcloud.course.contract.Role;
import com.medcloud.course.contract.AppointmentHistoryView;
import com.medcloud.course.contract.AppointmentHistoryPage;
import com.medcloud.course.paas.PageResult;
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
import java.util.ArrayList;
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

    @EJB private AuditWritePort journal;
    public BookingService(EntityManager manager) { this.manager = manager; this.journal = new AuditJournal(manager); }

    public Appointment book(CurrentActor actor, String slotId, Date today) {
        return book(actor,slotId,today,AuditContext.create(AuditEventType.APPOINTMENT_BOOK,UUID.randomUUID().toString()));
    }

    public Appointment book(CurrentActor actor, String slotId, Date today, AuditContext context) {
        Account patient = new TenantAccessPolicy(manager).requireAccount(actor, Role.PATIENT);
        context.attachActor(new CurrentActor(patient.getId(),Role.PATIENT,null));
        if (slotId == null || slotId.trim().isEmpty() || today == null) throw new IllegalArgumentException("slotId and date required");
        // Serialize every booking by this patient, across hospitals, before checking overlap.
        Account lockedPatient = manager.find(Account.class, patient.getId(), LockModeType.PESSIMISTIC_WRITE);
        if (lockedPatient == null) throw new SecurityException("inactive account");
        manager.refresh(lockedPatient, LockModeType.PESSIMISTIC_WRITE);
        if (!"ACTIVE".equals(lockedPatient.getStatus()) || lockedPatient.getRole() != Role.PATIENT)
            throw new AccessDeniedException();
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
        Instant end = LocalDateTime.of(slot.getDate().toLocalDate(), slot.getEndTime().toLocalTime())
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
        // Cross-hospital, cross-doctor half-open interval conflict for the same patient. The patient row
        // lock above serializes bookings, so a concurrent booking for this patient cannot slip between this
        // read and the persist; a concurrent cancellation only risks a safe, retryable over-rejection.
        List<Appointment> active = manager.createQuery(
                "select a from Appointment a where a.patientId = :patientId and a.status in ('BOOKED','IN_PROGRESS')",
                Appointment.class).setParameter("patientId", patient.getId()).getResultList();
        for (Appointment held : active) {
            if (slotId.equals(held.getSlotId())) continue; // Same-slot duplicates keep the existing rule.
            ScheduleSlot heldSlot = manager.find(ScheduleSlot.class, held.getSlotId());
            if (heldSlot == null) continue;
            Instant heldStart = LocalDateTime.of(heldSlot.getDate().toLocalDate(), heldSlot.getStartTime().toLocalTime())
                    .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
            Instant heldEnd = LocalDateTime.of(heldSlot.getDate().toLocalDate(), heldSlot.getEndTime().toLocalTime())
                    .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
            if (start.isBefore(heldEnd) && heldStart.isBefore(end)) {
                throw new AppointmentConflictException();
            }
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
        context.attachVerifiedTenant(appointment.getTenantId());
        journal.append(context,appointment.getId(),AuditResult.SUCCESS,Instant.now());
        return appointment;
    }

    public PageResult<Appointment> forDoctor(CurrentActor actor, int page, int size) {
        TenantAccessPolicy policy = new TenantAccessPolicy(manager);
        Account doctor = policy.requireAccount(actor, Role.DOCTOR);
        policy.readableTenant(doctor.getTenantId());
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("invalid pagination");
        long offset = ((long) page - 1) * size;
        if (offset > Integer.MAX_VALUE) throw new IllegalArgumentException("pagination offset too large");
        long total = manager.createQuery("select count(a) from Appointment a where a.doctorId = :doctorId "
                + "and a.tenantId = :tenantId", Long.class)
                .setParameter("doctorId", doctor.getId()).setParameter("tenantId", doctor.getTenantId())
                .getSingleResult();
        List<Appointment> rows = manager.createQuery("select a from Appointment a where a.doctorId = :doctorId "
                + "and a.tenantId = :tenantId order by a.bookedOn, a.id", Appointment.class)
                .setParameter("doctorId", doctor.getId()).setParameter("tenantId", doctor.getTenantId())
                .setFirstResult((int) offset).setMaxResults(size).getResultList();
        return new PageResult<Appointment>(rows, total, page, size);
    }

    public Appointment cancel(CurrentActor actor, String appointmentId, Instant now) {
        return cancel(actor,appointmentId,now,AuditContext.create(AuditEventType.APPOINTMENT_CANCEL,UUID.randomUUID().toString()));
    }

    public Appointment cancel(CurrentActor actor, String appointmentId, Instant now, AuditContext context) {
        requirePatient(actor);
        context.attachActor(new CurrentActor(actor.getUserId(),Role.PATIENT,null));
        if (appointmentId == null || now == null) throw new IllegalArgumentException("appointment and time required");
        Appointment candidate = manager.find(Appointment.class, appointmentId);
        if (candidate == null || !actor.getUserId().equals(candidate.getPatientId())) throw new AccessDeniedException();
        String tenantId = candidate.getTenantId();
        // Existing owned-object verification survives a later hospital freeze failure.
        context.attachVerifiedTenant(tenantId);
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
        journal.append(context,appointment.getId(),AuditResult.SUCCESS,Instant.now());
        return appointment;
    }

    public AppointmentHistoryPage forPatient(CurrentActor actor, int page, int size) {
        requirePatient(actor);
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("invalid pagination");
        long offset = ((long) page - 1) * size;
        if (offset > Integer.MAX_VALUE) throw new IllegalArgumentException("pagination offset too large");
        long total = manager.createQuery("select count(a) from Appointment a where a.patientId = :patientId", Long.class)
                .setParameter("patientId", actor.getUserId()).getSingleResult();
        List<Appointment> rows = manager.createQuery("select a from Appointment a where a.patientId = :patientId "
                + "order by a.bookedOn desc, a.bookedAt desc, a.id desc", Appointment.class)
                .setParameter("patientId", actor.getUserId())
                .setFirstResult((int) offset).setMaxResults(size).getResultList();
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
        return new AppointmentHistoryPage(views, total, page, size);
    }

    private void requirePatient(CurrentActor actor) {
        new TenantAccessPolicy(manager).requireAccount(actor, Role.PATIENT);
    }
}
