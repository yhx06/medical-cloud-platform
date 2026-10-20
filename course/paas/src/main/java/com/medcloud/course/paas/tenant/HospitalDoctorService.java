package com.medcloud.course.paas.tenant;

import com.medcloud.course.paas.audit.*;
import com.medcloud.course.contract.AuditEventType;
import com.medcloud.course.contract.AuditResult;
import javax.ejb.EJB;
import java.time.Instant;

import com.medcloud.course.contract.DoctorCreateResponse;
import com.medcloud.course.contract.DoctorView;
import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.auth.AccessDeniedException;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.appointment.Appointment;
import org.mindrot.jbcrypt.BCrypt;

import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceException;
import javax.persistence.LockModeType;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Stateless
public class HospitalDoctorService {
    private static final SecureRandom RANDOM = new SecureRandom();
    @PersistenceContext(unitName = "medicalCloudCourse")
    private EntityManager manager;
    public HospitalDoctorService() { }
    @EJB private AuditWritePort journal;
    public HospitalDoctorService(EntityManager manager) { this.manager = manager; this.journal = new AuditJournal(manager); }

    public List<DoctorView> list(CurrentActor actor) {
        String tenantId = tenant(actor);
        List<DoctorView> result = new ArrayList<DoctorView>();
        List<Object[]> rows = manager.createQuery("select a, p from Account a, DoctorProfile p "
                + "where a.id = p.userId and a.tenantId = :tenantId and p.tenantId = :tenantId "
                + "and a.role = :role order by p.displayName, a.id", Object[].class)
                .setParameter("tenantId", tenantId).setParameter("role", Role.DOCTOR)
                .setMaxResults(200).getResultList();
        for (Object[] row : rows) result.add(view((Account) row[0], (DoctorProfile) row[1]));
        return result;
    }

    public DoctorCreateResponse create(CurrentActor actor, String username, String displayName, String departmentId) {
        return create(actor, username, displayName, departmentId, AuditContext.create(AuditEventType.DOCTOR_CREATE, UUID.randomUUID().toString()));
    }

    public DoctorCreateResponse create(CurrentActor actor, String username, String displayName, String departmentId, AuditContext context) {
        String tenantId = tenant(actor);
        context.attachActor(new CurrentActor(actor.getUserId(), Role.HOSPITAL_ADMIN, tenantId));
        department(tenantId, departmentId);
        new TenantAccessPolicy(manager).lockWritableTenant(tenantId);
        new TenantAccessPolicy(manager).recheckHospitalWriter(actor, tenantId);
        lockDepartment(tenantId, departmentId);
        if (username == null || !username.matches("[A-Za-z0-9_]{3,64}"))
            throw new IllegalArgumentException("invalid username");
        String name = HospitalDepartmentService.name(displayName);
        department(tenantId, departmentId);
        Long exists = manager.createQuery("select count(a) from Account a where lower(a.username) = :username", Long.class)
                .setParameter("username", username.toLowerCase(Locale.ROOT)).getSingleResult();
        if (exists != 0) throw new IllegalStateException("username already exists");
        byte[] bytes = new byte[24]; RANDOM.nextBytes(bytes);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Account account = new Account(UUID.randomUUID().toString(), username,
                BCrypt.hashpw(password, BCrypt.gensalt()), Role.DOCTOR, tenantId);
        DoctorProfile profile = new DoctorProfile(account.getId(), tenantId, departmentId, name);
        try {
            manager.persist(account);
            manager.persist(profile);
            manager.flush();
        } catch (PersistenceException failure) {
            if (UsernameConstraint.violatedBy(failure)) throw new IllegalStateException("username already exists", failure);
            throw failure;
        }
        journal.append(context, account.getId(), AuditResult.SUCCESS, Instant.now());
        return new DoctorCreateResponse(account.getId(), username, name, departmentId, account.getStatus(), password);
    }

    public DoctorView update(CurrentActor actor, String userId, String displayName, String departmentId, String status) {
        return update(actor, userId, displayName, departmentId, status, AuditContext.create(AuditEventType.DOCTOR_UPDATE, UUID.randomUUID().toString()));
    }

    public DoctorView update(CurrentActor actor, String userId, String displayName, String departmentId, String status, AuditContext context) {
        String tenantId = tenant(actor);
        context.attachActor(new CurrentActor(actor.getUserId(), Role.HOSPITAL_ADMIN, tenantId));
        Account candidate = manager.find(Account.class, userId);
        DoctorProfile candidateProfile = manager.find(DoctorProfile.class, userId);
        if (candidate == null || candidateProfile == null || candidate.getRole() != Role.DOCTOR
                || !tenantId.equals(candidate.getTenantId()) || !tenantId.equals(candidateProfile.getTenantId()))
            throw new AccessDeniedException();
        department(tenantId, departmentId);
        new TenantAccessPolicy(manager).lockWritableTenant(tenantId);
        new TenantAccessPolicy(manager).recheckHospitalWriter(actor, tenantId);
        lockDepartment(tenantId, departmentId);
        Account account = manager.find(Account.class, userId, LockModeType.PESSIMISTIC_WRITE);
        if (account != null) manager.refresh(account, LockModeType.PESSIMISTIC_WRITE);
        DoctorProfile profile = manager.find(DoctorProfile.class, userId);
        if (profile != null) manager.refresh(profile);
        if (account == null || profile == null || account.getRole() != Role.DOCTOR
                || !tenantId.equals(account.getTenantId()) || !tenantId.equals(profile.getTenantId()))
            throw new AccessDeniedException();
        String name = HospitalDepartmentService.name(displayName);
        department(tenantId, departmentId);
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) throw new IllegalArgumentException("invalid status");
        if ("DISABLED".equals(status) && !"DISABLED".equals(account.getStatus())) {
            List<Appointment> pending = manager.createQuery("select a from Appointment a where a.doctorId = :doctorId "
                    + "and a.tenantId = :tenantId and a.status in ('BOOKED', 'IN_PROGRESS')", Appointment.class)
                    .setParameter("doctorId", userId).setParameter("tenantId", tenantId)
                    .setMaxResults(1).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
            if (!pending.isEmpty()) throw new IllegalStateException("doctor has pending appointments");
        }
        account.setStatus(status);
        profile.update(name, departmentId);
        journal.append(context, account.getId(), AuditResult.SUCCESS, Instant.now());
        return view(account, profile);
    }

    private Department department(String tenantId, String departmentId) {
        Department department = manager.find(Department.class, departmentId);
        if (department == null || !tenantId.equals(department.getTenantId())) throw new AccessDeniedException();
        if (!"ACTIVE".equals(department.getStatus())) throw new IllegalStateException("department disabled");
        return department;
    }

    private Department lockDepartment(String tenantId, String departmentId) {
        // A preceding department create in the same transaction must reach the database before locking.
        manager.flush();
        Department department = manager.find(Department.class, departmentId, LockModeType.PESSIMISTIC_READ);
        if (department != null) manager.refresh(department, LockModeType.PESSIMISTIC_READ);
        return department(tenantId, departmentId);
    }

    private String tenant(CurrentActor actor) { return new HospitalDepartmentService(manager).tenant(actor); }

    private static DoctorView view(Account account, DoctorProfile profile) {
        return new DoctorView(account.getId(), account.getUsername(), profile.getDisplayName(),
                profile.getDepartmentId(), account.getStatus(), profile.getTitle(), profile.getBio());
    }

}
