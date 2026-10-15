package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.consultation.ClinicalRecord;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.tenant.*;
import javax.persistence.*;
import java.util.Objects;

/** The caller owns the short transaction. Every decision uses refreshed database state. */
public final class AttachmentAccessPolicy {
    private final EntityManager manager;
    public AttachmentAccessPolicy(EntityManager manager) { this.manager=manager; }
    Account account(CurrentActor actor) {
        if(actor==null || actor.getUserId()==null) throw new AttachmentException(401,"UNAUTHORIZED");
        if(actor.getRole()!=Role.PATIENT && actor.getRole()!=Role.DOCTOR) throw forbidden();
        try { return new TenantAccessPolicy(manager).requireAccount(actor,actor.getRole()); }
        catch(AccessDeniedException ex) { throw forbidden(); }
        catch(SecurityException ex) { throw new AttachmentException(401,"UNAUTHORIZED"); }
    }
    public Appointment readable(CurrentActor actor,String appointmentId) {
        Account user=account(actor);
        Appointment appointment=fresh(Appointment.class,appointmentId,null);
        owns(user,appointment);
        Tenant tenant=fresh(Tenant.class,appointment.getTenantId(),null);
        if(!"ACTIVE".equals(tenant.getStatus()) && !"DISABLED".equals(tenant.getStatus())) throw forbidden();
        relation(appointment,fresh(ScheduleSlot.class,appointment.getSlotId(),null),fresh(Account.class,appointment.getDoctorId(),null));
        return appointment;
    }
    Appointment locked(CurrentActor actor,String appointmentId,String uploaderId,LockModeType mode,boolean writable) {
        Appointment candidate=readable(actor,appointmentId);
        String tenantId=candidate.getTenantId(), doctorId=candidate.getDoctorId(), slotId=candidate.getSlotId();
        Tenant tenant=fresh(Tenant.class,tenantId,mode==LockModeType.PESSIMISTIC_WRITE?LockModeType.PESSIMISTIC_READ:mode);
        Account doctor=fresh(Account.class,doctorId,mode);
        if(!doctorId.equals(uploaderId)) fresh(Account.class,uploaderId,mode);
        // Download of a doctor's append also protects the requesting patient's enabled status.
        if(!actor.getUserId().equals(doctorId) && !actor.getUserId().equals(uploaderId)) fresh(Account.class,actor.getUserId(),mode);
        ScheduleSlot slot=fresh(ScheduleSlot.class,slotId,mode);
        Appointment appointment=fresh(Appointment.class,appointmentId,mode);
        owns(account(actor),appointment);
        if(!tenantId.equals(appointment.getTenantId()) || !doctorId.equals(appointment.getDoctorId()) || !slotId.equals(appointment.getSlotId())) throw forbidden();
        relation(appointment,slot,doctor);
        if(writable && !"ACTIVE".equals(tenant.getStatus())) throw conflict();
        return appointment;
    }
    void writableSource(Appointment appointment,AttachmentSourceType source,String recordId,CurrentActor actor) {
        if(source==AttachmentSourceType.PATIENT_APPOINTMENT) {
            if(actor.getRole()!=Role.PATIENT || recordId!=null) throw forbidden();
            if(!"BOOKED".equals(appointment.getStatus()) && !"IN_PROGRESS".equals(appointment.getStatus())) throw conflict();
        } else {
            if(actor.getRole()!=Role.DOCTOR) throw forbidden();
            record(appointment,recordId);
            if(!"COMPLETED".equals(appointment.getStatus())) throw conflict();
        }
    }
    void file(Appointment a,FileMetadata f) {
        if(f==null || !a.getId().equals(f.appointmentId) || !a.getTenantId().equals(f.tenantId) || !a.getDoctorId().equals(f.doctorId) || !a.getPatientId().equals(f.patientId)) throw forbidden();
        if(f.sourceType==AttachmentSourceType.PATIENT_APPOINTMENT) {
            if(f.recordId!=null || !f.patientId.equals(f.uploaderId)) throw forbidden();
        } else if(f.sourceType==AttachmentSourceType.DOCTOR_RECORD) {
            if(!f.doctorId.equals(f.uploaderId)) throw forbidden();
            record(a,f.recordId);
        } else throw forbidden();
    }
    ClinicalRecord record(Appointment a,String recordId) {
        ClinicalRecord r=fresh(ClinicalRecord.class,recordId,null);
        if(!a.getId().equals(r.getAppointmentId()) || !a.getTenantId().equals(r.getTenantId()) || !a.getDoctorId().equals(r.getDoctorId()) || !a.getPatientId().equals(r.getPatientId())) throw forbidden();
        return r;
    }
    private void owns(Account user,Appointment a) {
        if(a==null || (user.getRole()==Role.PATIENT?!user.getId().equals(a.getPatientId()):!user.getId().equals(a.getDoctorId()) || !Objects.equals(user.getTenantId(),a.getTenantId()))) throw forbidden();
    }
    private void relation(Appointment a,ScheduleSlot s,Account d) {
        if(!a.getTenantId().equals(s.getTenantId()) || !a.getDoctorId().equals(s.getDoctorId()) || d.getRole()!=Role.DOCTOR || !a.getTenantId().equals(d.getTenantId())) throw forbidden();
    }
    <T>T fresh(Class<T> type,String id,LockModeType mode) {
        if(id==null) throw forbidden();
        T entity=mode==null?manager.find(type,id):manager.find(type,id,mode);
        if(entity==null) throw forbidden();
        try { if(mode==null) manager.refresh(entity); else manager.refresh(entity,mode); }
        catch(EntityNotFoundException ex) { throw forbidden(); }
        return entity;
    }
    static AttachmentException forbidden() { return new AttachmentException(403,"FORBIDDEN"); }
    static AttachmentException conflict() { return new AttachmentException(409,"FILE_STATE_CONFLICT"); }
}
