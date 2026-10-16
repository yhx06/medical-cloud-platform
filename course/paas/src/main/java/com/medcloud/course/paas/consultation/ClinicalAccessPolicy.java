package com.medcloud.course.paas.consultation;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.audit.AuditContext;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.tenant.TenantAccessPolicy;
import javax.persistence.*;

/** Shared parent authorization/locking. The invoking EJB owns the transaction. */
public final class ClinicalAccessPolicy {
    private final EntityManager manager;
    private final TenantAccessPolicy tenants;
    public ClinicalAccessPolicy(EntityManager manager){this.manager=manager;tenants=new TenantAccessPolicy(manager);}

    Appointment doctorAppointment(CurrentActor actor,String id){return authorized(actor,id,Role.DOCTOR);}
    Appointment patientAppointment(CurrentActor actor,String id){return authorized(actor,id,Role.PATIENT);}
    private Appointment authorized(CurrentActor actor,String id,Role role) {
        Account account=tenants.requireAccount(actor,role);
        Appointment a=manager.find(Appointment.class,id);
        if(a==null)throw new AccessDeniedException();
        manager.refresh(a);
        verifyOwner(account,a,role);tenants.readableTenant(a.getTenantId());
        ScheduleSlot slot=manager.find(ScheduleSlot.class,a.getSlotId());
        if(slot==null)throw new AccessDeniedException();manager.refresh(slot);verifySlot(a,slot);
        return a;
    }
    public Appointment lockedDoctorAppointment(CurrentActor actor,String id,AuditContext context) {
        return lock(actor,id,Role.DOCTOR,context);
    }
    Appointment lockedPatientAppointment(CurrentActor actor,String id,AuditContext context) {
        return lock(actor,id,Role.PATIENT,context);
    }
    private Appointment lock(CurrentActor actor,String id,Role role,AuditContext context) {
        Appointment candidate=authorized(actor,id,role);
        String tenant=candidate.getTenantId(),doctorId=candidate.getDoctorId(),patientId=candidate.getPatientId(),slotId=candidate.getSlotId();
        Account verified=tenants.requireAccount(actor,role);
        context.attachActor(new CurrentActor(verified.getId(),role,verified.getTenantId()));
        tenants.lockWritableTenant(tenant);
        Account doctor=lockedAccount(doctorId);
        if(doctor.getRole()!=Role.DOCTOR||!tenant.equals(doctor.getTenantId()))throw new AccessDeniedException();
        if(role==Role.DOCTOR)verifyOwner(doctor,candidate,role);
        if(role==Role.PATIENT){Account patient=lockedAccount(patientId);verifyOwner(patient,candidate,role);}
        ScheduleSlot slot=manager.find(ScheduleSlot.class,slotId,LockModeType.PESSIMISTIC_WRITE);
        if(slot==null)throw new AccessDeniedException();manager.refresh(slot,LockModeType.PESSIMISTIC_WRITE);
        Appointment a=manager.find(Appointment.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(a==null)throw new AccessDeniedException();manager.refresh(a,LockModeType.PESSIMISTIC_WRITE);
        if(!tenant.equals(a.getTenantId())||!doctorId.equals(a.getDoctorId())||!patientId.equals(a.getPatientId())||!slotId.equals(a.getSlotId()))throw new AccessDeniedException();
        verifyOwner(verified,a,role);verifySlot(a,slot);context.attachVerifiedTenant(tenant);return a;
    }
    private Account lockedAccount(String id) {
        Account account=manager.find(Account.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(account==null)throw new AccessDeniedException();manager.refresh(account,LockModeType.PESSIMISTIC_WRITE);
        if(!"ACTIVE".equals(account.getStatus()))throw new AccessDeniedException();return account;
    }
    private void verifyOwner(Account account,Appointment a,Role role) {
        if(account.getRole()!=role||!"ACTIVE".equals(account.getStatus()))throw new AccessDeniedException();
        if(role==Role.DOCTOR&&(!a.getDoctorId().equals(account.getId())||!a.getTenantId().equals(account.getTenantId())))throw new AccessDeniedException();
        if(role==Role.PATIENT&&!a.getPatientId().equals(account.getId()))throw new AccessDeniedException();
    }
    private void verifySlot(Appointment a,ScheduleSlot slot) {
        if(!a.getSlotId().equals(slot.getId())||!a.getTenantId().equals(slot.getTenantId())||!a.getDoctorId().equals(slot.getDoctorId()))throw new AccessDeniedException();
    }
    ClinicalRecord doctorRecord(CurrentActor actor,String id) {
        tenants.requireAccount(actor,Role.DOCTOR);ClinicalRecord record=manager.find(ClinicalRecord.class,id);
        if(record==null)throw new AccessDeniedException();verifyRecord(record,doctorAppointment(actor,record.getAppointmentId()));return record;
    }
    ClinicalRecord patientRecord(CurrentActor actor,String appointmentId) {
        Appointment a=patientAppointment(actor,appointmentId);
        java.util.List<ClinicalRecord> records=manager.createQuery("select r from ClinicalRecord r where r.appointmentId=:id",ClinicalRecord.class).setParameter("id",appointmentId).getResultList();
        if(records.isEmpty())throw new ClinicalSupplementException(409,"CLINICAL_STATE_CONFLICT");
        verifyRecord(records.get(0),a);return records.get(0);
    }
    void verifyRecord(ClinicalRecord r,Appointment a) {
        manager.refresh(r);
        if(!r.getAppointmentId().equals(a.getId())||!r.getTenantId().equals(a.getTenantId())||!r.getDoctorId().equals(a.getDoctorId())||!r.getPatientId().equals(a.getPatientId()))throw new AccessDeniedException();
    }
    void verifyDraft(ClinicalDraft d,Appointment a) {
        if(d!=null&&(!d.getAppointmentId().equals(a.getId())||!d.getTenantId().equals(a.getTenantId())||!d.getDoctorId().equals(a.getDoctorId())||!d.getPatientId().equals(a.getPatientId())))throw new AccessDeniedException();
    }
}
