package com.medcloud.course.paas.tenant;

import com.medcloud.course.contract.AuditEventType;
import com.medcloud.course.contract.AuditResult;
import com.medcloud.course.contract.DoctorProfileView;
import com.medcloud.course.contract.HospitalAdministrationRules;
import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.audit.AuditContext;
import com.medcloud.course.paas.audit.AuditJournal;
import com.medcloud.course.paas.audit.AuditWritePort;
import com.medcloud.course.paas.auth.AccessDeniedException;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.CurrentActor;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;

/** Minimal writable profile: a hospital administrator renames their own hospital; a doctor edits their own title/bio. */
@Stateless
public class HospitalProfileService {
    @PersistenceContext(unitName = "medicalCloudCourse")
    private EntityManager manager;
    public HospitalProfileService() { }
    @EJB private AuditWritePort journal;
    public HospitalProfileService(EntityManager manager) { this.manager = manager; this.journal = new AuditJournal(manager); }

    public String renameHospital(CurrentActor actor, String name) {
        return renameHospital(actor, name, AuditContext.create(AuditEventType.HOSPITAL_RENAME, UUID.randomUUID().toString()));
    }

    public String renameHospital(CurrentActor actor, String name, AuditContext context) {
        Account administrator = new TenantAccessPolicy(manager).requireAccount(actor, Role.HOSPITAL_ADMIN);
        String tenantId = administrator.getTenantId();
        context.attachActor(new CurrentActor(administrator.getId(), Role.HOSPITAL_ADMIN, tenantId));
        String cleaned = HospitalAdministrationRules.requireHospitalName(name);
        new TenantAccessPolicy(manager).lockWritableTenant(tenantId);
        new TenantAccessPolicy(manager).recheckHospitalWriter(actor, tenantId);
        Tenant tenant = manager.find(Tenant.class, tenantId, LockModeType.PESSIMISTIC_WRITE);
        if (tenant != null) manager.refresh(tenant, LockModeType.PESSIMISTIC_WRITE);
        if (tenant == null) throw new AccessDeniedException();
        tenant.rename(cleaned);
        context.attachVerifiedTenant(tenantId);
        journal.append(context, tenantId, AuditResult.SUCCESS, Instant.now());
        return cleaned;
    }

    public DoctorProfileView doctorProfile(CurrentActor actor) {
        Account doctor = new TenantAccessPolicy(manager).requireAccount(actor, Role.DOCTOR);
        DoctorProfile profile = manager.find(DoctorProfile.class, doctor.getId());
        if (profile == null || !doctor.getTenantId().equals(profile.getTenantId())) throw new AccessDeniedException();
        return view(profile);
    }

    public DoctorProfileView updateDoctorProfile(CurrentActor actor, String title, String bio) {
        return updateDoctorProfile(actor, title, bio, AuditContext.create(AuditEventType.DOCTOR_PROFILE_UPDATE, UUID.randomUUID().toString()));
    }

    public DoctorProfileView updateDoctorProfile(CurrentActor actor, String title, String bio, AuditContext context) {
        Account doctor = new TenantAccessPolicy(manager).requireAccount(actor, Role.DOCTOR);
        String tenantId = doctor.getTenantId();
        context.attachActor(new CurrentActor(doctor.getId(), Role.DOCTOR, tenantId));
        new TenantAccessPolicy(manager).readableTenant(tenantId);
        Account locked = manager.find(Account.class, doctor.getId(), LockModeType.PESSIMISTIC_WRITE);
        DoctorProfile profile = manager.find(DoctorProfile.class, doctor.getId());
        if (profile != null) manager.refresh(profile);
        if (locked == null || profile == null || locked.getRole() != Role.DOCTOR || !tenantId.equals(profile.getTenantId()))
            throw new AccessDeniedException();
        profile.updateProfile(HospitalAdministrationRules.requireOptionalText(title, 100),
                HospitalAdministrationRules.requireOptionalText(bio, 1000));
        context.attachVerifiedTenant(tenantId);
        journal.append(context, doctor.getId(), AuditResult.SUCCESS, Instant.now());
        return view(profile);
    }

    private static DoctorProfileView view(DoctorProfile profile) {
        return new DoctorProfileView(profile.getUserId(), profile.getDisplayName(), profile.getDepartmentId(),
                profile.getTitle(), profile.getBio());
    }
}
