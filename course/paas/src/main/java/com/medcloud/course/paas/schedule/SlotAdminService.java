package com.medcloud.course.paas.schedule;

import com.medcloud.course.paas.audit.*;
import com.medcloud.course.contract.AuditEventType;
import com.medcloud.course.contract.AuditResult;
import javax.ejb.EJB;
import java.time.Instant;

import com.medcloud.course.contract.Role;
import com.medcloud.course.contract.SlotRequest;
import com.medcloud.course.paas.PageResult;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.auth.AccessDeniedException;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.DoctorProfile;
import com.medcloud.course.paas.tenant.HospitalDepartmentService;
import com.medcloud.course.paas.tenant.TenantAccessPolicy;

import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceContext;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Stateless
public class SlotAdminService {
    @PersistenceContext(unitName = "medicalCloudCourse") private EntityManager manager;
    public SlotAdminService() { }
    @EJB private AuditWritePort journal;
    public SlotAdminService(EntityManager manager) { this.manager = manager; this.journal = new AuditJournal(manager); }

    public PageResult<ScheduleSlot> list(CurrentActor actor, int page, int size) {
        String tenantId = tenant(actor);
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("invalid pagination");
        long offset = ((long) page - 1) * size;
        if (offset > Integer.MAX_VALUE) throw new IllegalArgumentException("pagination offset too large");
        long total = manager.createQuery("select count(s) from ScheduleSlot s where s.tenantId = :tenantId", Long.class)
                .setParameter("tenantId", tenantId).getSingleResult();
        List<ScheduleSlot> rows = manager.createQuery("select s from ScheduleSlot s where s.tenantId = :tenantId "
                + "order by s.date desc, s.startTime, s.id", ScheduleSlot.class)
                .setParameter("tenantId", tenantId).setFirstResult((int) offset).setMaxResults(size).getResultList();
        return new PageResult<ScheduleSlot>(rows, total, page, size);
    }

    public int remaining(ScheduleSlot slot) {
        Long occupied = manager.createQuery("select count(a) from Appointment a where a.slotId = :slotId "
                + "and a.status <> 'CANCELED'", Long.class)
                .setParameter("slotId", slot.getId()).getSingleResult();
        return Math.max(0, slot.getCapacity() - occupied.intValue());
    }

    public ScheduleSlot create(CurrentActor actor, SlotRequest request) {
        return create(actor, request, AuditContext.create(AuditEventType.SLOT_CREATE, UUID.randomUUID().toString()));
    }

    public ScheduleSlot create(CurrentActor actor, SlotRequest request, AuditContext context) {
        String tenantId = tenant(actor);
        context.attachActor(new CurrentActor(actor.getUserId(), Role.HOSPITAL_ADMIN, tenantId));
        validate(request, false);
        requireDirectoryOwnership(tenantId, request);
        new TenantAccessPolicy(manager).lockWritableTenant(tenantId);
        new TenantAccessPolicy(manager).recheckHospitalWriter(actor, tenantId);
        // Department disable locks the department; publication must acquire it first.
        lockDepartment(tenantId, request.getDepartmentId());
        // Booking locks doctor then slot. Keep that relative order.
        activeDoctor(tenantId, request.getDepartmentId(), request.getDoctorId());
        unique(tenantId, request, null);
        ScheduleSlot slot = new ScheduleSlot(UUID.randomUUID().toString(), tenantId, request.getDepartmentId(),
                request.getDoctorId(), request.getDate(), request.getStartTime(), request.getEndTime(),
                request.getCapacity(), request.getFee());
        manager.persist(slot);
        journal.append(context, slot.getId(), AuditResult.SUCCESS, Instant.now());
        return slot;
    }

    public ScheduleSlot update(CurrentActor actor, String slotId, SlotRequest request) {
        return update(actor, slotId, request, AuditContext.create(AuditEventType.SLOT_UPDATE, UUID.randomUUID().toString()));
    }

    public ScheduleSlot update(CurrentActor actor, String slotId, SlotRequest request, AuditContext context) {
        String tenantId = tenant(actor);
        context.attachActor(new CurrentActor(actor.getUserId(), Role.HOSPITAL_ADMIN, tenantId));
        validate(request, true);
        manager.flush();
        ScheduleSlot candidate = manager.find(ScheduleSlot.class, slotId);
        if (candidate == null || !tenantId.equals(candidate.getTenantId())) throw new AccessDeniedException();
        requireDirectoryOwnership(tenantId, request);
        new TenantAccessPolicy(manager).lockWritableTenant(tenantId);
        new TenantAccessPolicy(manager).recheckHospitalWriter(actor, tenantId);
        String candidateDepartmentId = candidate.getDepartmentId();
        String candidateDoctorId = candidate.getDoctorId();
        // Lock both departments, then both doctors, then the slot to serialize admin edits.
        String firstDepartment = candidateDepartmentId.compareTo(request.getDepartmentId()) <= 0
                ? candidateDepartmentId : request.getDepartmentId();
        String secondDepartment = firstDepartment.equals(candidateDepartmentId)
                ? request.getDepartmentId() : candidateDepartmentId;
        lockDepartment(tenantId, firstDepartment);
        if (!secondDepartment.equals(firstDepartment)) lockDepartment(tenantId, secondDepartment);
        String first = candidateDoctorId.compareTo(request.getDoctorId()) <= 0
                ? candidateDoctorId : request.getDoctorId();
        String second = first.equals(candidateDoctorId) ? request.getDoctorId() : candidateDoctorId;
        lockDoctor(first);
        if (!second.equals(first)) lockDoctor(second);
        activeDoctor(tenantId, request.getDepartmentId(), request.getDoctorId());
        ScheduleSlot slot = manager.find(ScheduleSlot.class, slotId, LockModeType.PESSIMISTIC_WRITE);
        manager.refresh(slot, LockModeType.PESSIMISTIC_WRITE);
        if (!tenantId.equals(slot.getTenantId())) throw new AccessDeniedException();
        if (!candidateDepartmentId.equals(slot.getDepartmentId())
                || !candidateDoctorId.equals(slot.getDoctorId())) throw new IllegalStateException("slot changed");
        // A locking current read must see bookings committed after the tenant snapshot.
        List<Appointment> booked = manager.createQuery("select a from Appointment a where a.slotId = :slotId "
                + "and a.status <> 'CANCELED'", Appointment.class).setParameter("slotId", slotId)
                .setMaxResults(1).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if (!booked.isEmpty() && (!slot.getDepartmentId().equals(request.getDepartmentId())
                || !slot.getDoctorId().equals(request.getDoctorId()) || !slot.getDate().equals(request.getDate())
                || !slot.getStartTime().equals(request.getStartTime()) || !slot.getEndTime().equals(request.getEndTime())
                || slot.getFee().compareTo(request.getFee()) != 0 || request.getCapacity() < slot.getCapacity()
                || "WITHDRAWN".equals(request.getStatus()))) {
            throw new IllegalStateException("slot has bookings");
        }
        unique(tenantId, request, slotId);
        slot.update(request.getDepartmentId(), request.getDoctorId(), request.getDate(), request.getStartTime(),
                request.getEndTime(), request.getCapacity(), request.getFee(), request.getStatus());
        journal.append(context, slot.getId(), AuditResult.SUCCESS, Instant.now());
        return slot;
    }

    private void validate(SlotRequest request, boolean update) {
        if (request == null || request.getDepartmentId() == null || request.getDoctorId() == null
                || request.getDate() == null || request.getStartTime() == null || request.getEndTime() == null
                || request.getCapacity() <= 0 || request.getFee() == null || request.getFee().signum() < 0
                || !request.getEndTime().after(request.getStartTime())
                || (update && !("PUBLISHED".equals(request.getStatus()) || "WITHDRAWN".equals(request.getStatus())))
                || (!update && request.getStatus() != null && !"PUBLISHED".equals(request.getStatus()))) {
            throw new IllegalArgumentException("invalid slot");
        }
        if (request.getDate().before(Date.valueOf(LocalDate.now(ZoneId.of("Asia/Shanghai")))))
            throw new IllegalArgumentException("past slot");
    }

    private void activeDoctor(String tenantId, String departmentId, String doctorId) {
        Department department = manager.find(Department.class, departmentId);
        if (department == null || !tenantId.equals(department.getTenantId())) throw new AccessDeniedException();
        if (!"ACTIVE".equals(department.getStatus())) throw new IllegalStateException("department disabled");
        Account doctor = lockDoctor(doctorId);
        DoctorProfile profile = manager.find(DoctorProfile.class, doctorId);
        if (profile != null) manager.refresh(profile);
        if (doctor == null || profile == null || !tenantId.equals(doctor.getTenantId())
                || !tenantId.equals(profile.getTenantId()) || doctor.getRole() != Role.DOCTOR
                || !departmentId.equals(profile.getDepartmentId())) throw new AccessDeniedException();
        if (!"ACTIVE".equals(doctor.getStatus())) throw new IllegalStateException("doctor disabled");
    }

    private void requireDirectoryOwnership(String tenantId, SlotRequest request) {
        // Nonlocking projections locate ownership; the locked entity reads below remain the mutation boundary.
        List<String> departments = manager.createQuery("select d.tenantId from Department d where d.id = :id", String.class)
                .setParameter("id", request.getDepartmentId()).getResultList();
        List<Object[]> doctors = manager.createQuery("select a.tenantId, a.role, p.tenantId, p.departmentId "
                + "from Account a, DoctorProfile p where a.id = p.userId and a.id = :id", Object[].class)
                .setParameter("id", request.getDoctorId()).getResultList();
        if (departments.size() != 1 || !tenantId.equals(departments.get(0)) || doctors.size() != 1)
            throw new AccessDeniedException();
        Object[] doctor = doctors.get(0);
        if (!tenantId.equals(doctor[0]) || doctor[1] != Role.DOCTOR || !tenantId.equals(doctor[2])
                || !request.getDepartmentId().equals(doctor[3]))
            throw new AccessDeniedException();
    }

    private Account lockDoctor(String doctorId) {
        Account doctor = manager.find(Account.class, doctorId, LockModeType.PESSIMISTIC_WRITE);
        if (doctor != null) manager.refresh(doctor, LockModeType.PESSIMISTIC_WRITE);
        return doctor;
    }

    private Department lockDepartment(String tenantId, String departmentId) {
        Department department = manager.find(Department.class, departmentId, LockModeType.PESSIMISTIC_WRITE);
        if (department != null) manager.refresh(department, LockModeType.PESSIMISTIC_WRITE);
        if (department == null || !tenantId.equals(department.getTenantId())) throw new AccessDeniedException();
        if (!"ACTIVE".equals(department.getStatus())) throw new IllegalStateException("department disabled");
        return department;
    }

    private void unique(String tenantId, SlotRequest request, String exceptId) {
        Long count = manager.createQuery("select count(s) from ScheduleSlot s where s.tenantId = :tenantId "
                + "and s.doctorId = :doctorId and s.date = :date and s.startTime = :startTime and s.id <> :exceptId", Long.class)
                .setParameter("tenantId", tenantId).setParameter("doctorId", request.getDoctorId())
                .setParameter("date", request.getDate()).setParameter("startTime", request.getStartTime())
                .setParameter("exceptId", exceptId == null ? "" : exceptId).getSingleResult();
        if (count != 0) throw new IllegalStateException("duplicate slot");
    }

    private String tenant(CurrentActor actor) { return new HospitalDepartmentService(manager).tenant(actor); }
}
