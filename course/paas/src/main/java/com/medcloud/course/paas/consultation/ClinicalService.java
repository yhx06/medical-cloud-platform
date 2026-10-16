package com.medcloud.course.paas.consultation;

import com.medcloud.course.paas.audit.*;
import com.medcloud.course.contract.AuditEventType;
import com.medcloud.course.contract.AuditResult;
import com.medcloud.course.contract.ClinicalContentView;
import javax.ejb.EJB;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.AccessDeniedException;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.order.PaymentOrder;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.tenant.TenantAccessPolicy;

import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Stateless
public class ClinicalService {
    @PersistenceContext(unitName = "medicalCloudCourse")
    private EntityManager manager;

    public ClinicalService() { }
    @EJB private AuditWritePort journal;
    public ClinicalService(EntityManager manager) { this.manager = manager; this.journal = new AuditJournal(manager); }

    public Appointment start(CurrentActor actor, String appointmentId) {
        return start(actor, appointmentId, Instant.now());
    }

    public Appointment start(CurrentActor actor, String appointmentId, AuditContext context) {
        return start(actor,appointmentId,Instant.now(),context);
    }

    public Appointment start(CurrentActor actor, String appointmentId, Instant now) {
        return start(actor,appointmentId,now,AuditContext.create(AuditEventType.CONSULTATION_START,UUID.randomUUID().toString()));
    }

    public Appointment start(CurrentActor actor, String appointmentId, Instant now, AuditContext context) {
        if (now == null) throw new IllegalArgumentException("start time required");
        Appointment appointment = lockedDoctorAppointment(actor, appointmentId, context);
        appointment.start(now);
        journal.append(context,appointment.getId(),AuditResult.SUCCESS,Instant.now());
        return appointment;
    }

    public ClinicalRecord complete(CurrentActor actor, String appointmentId, String diagnosis, String advice) {
        return complete(actor, appointmentId, null, diagnosis, advice, null, Instant.now());
    }

    public ClinicalRecord complete(CurrentActor actor, String appointmentId, String diagnosis, String advice, AuditContext context) {
        return complete(actor,appointmentId,null,diagnosis,advice,null,Instant.now(),context);
    }

    public ClinicalRecord complete(CurrentActor actor, String appointmentId, String complaint,
                                   String diagnosis, String advice, String notes, Instant now) {
        return complete(actor,appointmentId,complaint,diagnosis,advice,notes,now,
                AuditContext.create(AuditEventType.CONSULTATION_COMPLETE,UUID.randomUUID().toString()));
    }

    public ClinicalRecord complete(CurrentActor actor, String appointmentId, String complaint,
                                   String diagnosis, String advice, String notes, Instant now, AuditContext context) {
        if (diagnosis == null || diagnosis.trim().isEmpty() || advice == null || advice.trim().isEmpty()
                || now == null) throw new IllegalArgumentException("diagnosis, advice and time required");
        return complete(actor,appointmentId,complaint,diagnosis,advice,notes,now,context,null);
    }

    public ClinicalRecord complete(CurrentActor actor, String appointmentId, String complaint,
                                   String diagnosis, String advice, String notes, Instant now, AuditContext context,
                                   Long expectedDraftRevision) {
        if(now==null)throw new IllegalArgumentException("completion time required");
        ClinicalContentView content=ClinicalContentRules.requireFinal(new ClinicalContentView(complaint,diagnosis,advice,notes));
        Appointment appointment = lockedDoctorAppointment(actor, appointmentId, context);
        List<ClinicalDraft> drafts=manager.createQuery("select d from ClinicalDraft d where d.appointmentId=:id",ClinicalDraft.class)
                .setParameter("id",appointmentId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        ClinicalDraft draft=drafts.isEmpty()?null:drafts.get(0);
        if(draft!=null)manager.refresh(draft,LockModeType.PESSIMISTIC_WRITE);
        new ClinicalAccessPolicy(manager).verifyDraft(draft,appointment);
        if(expectedDraftRevision!=null)ClinicalContentRules.requireRevision(expectedDraftRevision);
        if(draft!=null&&(expectedDraftRevision==null||expectedDraftRevision.longValue()!=draft.getRevision())
                ||draft==null&&expectedDraftRevision!=null&&expectedDraftRevision.longValue()!=0)
            throw new ClinicalSupplementException(409,"CLINICAL_REVISION_CONFLICT");
        appointment.complete();
        ClinicalRecord record = new ClinicalRecord(UUID.randomUUID().toString(), appointmentId,
                appointment.getTenantId(), appointment.getDoctorId(), appointment.getPatientId(),
                content.getComplaint(), content.getDiagnosis(), content.getAdvice(), content.getNotes(), now);
        PaymentOrder order = new PaymentOrder(UUID.randomUUID().toString(), appointmentId,
                appointment.getPatientId(), appointment.getTenantId(), appointment.getFee());
        manager.persist(record);
        manager.persist(order);
        if(draft!=null)manager.remove(draft);
        journal.append(context,record.getId(),AuditResult.SUCCESS,Instant.now());
        return record;
    }

    private Appointment lockedDoctorAppointment(CurrentActor actor, String appointmentId, AuditContext context) {
        return new ClinicalAccessPolicy(manager).lockedDoctorAppointment(actor,appointmentId,context);
    }

    public List<ClinicalRecord> forDoctor(CurrentActor actor, Date from, Date to) {
        TenantAccessPolicy policy = new TenantAccessPolicy(manager);
        Account doctor = policy.requireAccount(actor, Role.DOCTOR);
        policy.readableTenant(doctor.getTenantId());
        if (from != null && to != null && from.toString().compareTo(to.toString()) > 0)
            throw new IllegalArgumentException("invalid date range");
        StringBuilder query = new StringBuilder("select r from ClinicalRecord r where r.doctorId = :doctorId "
                + "and r.tenantId = :tenantId");
        if (from != null) query.append(" and r.completedAt >= :from");
        if (to != null) query.append(" and r.completedAt < :to");
        query.append(" order by r.completedAt desc, r.id desc");
        javax.persistence.TypedQuery<ClinicalRecord> rows = manager.createQuery(query.toString(), ClinicalRecord.class)
                .setParameter("doctorId", doctor.getId()).setParameter("tenantId", doctor.getTenantId());
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        if (from != null) rows.setParameter("from", Timestamp.from(LocalDate.parse(from.toString()).atStartOfDay(shanghai).toInstant()));
        if (to != null) rows.setParameter("to", Timestamp.from(LocalDate.parse(to.toString()).plusDays(1).atStartOfDay(shanghai).toInstant()));
        return rows.setMaxResults(100).getResultList();
    }

    public ClinicalRecord forPatient(CurrentActor actor, String appointmentId) {
        Appointment appointment = manager.find(Appointment.class, appointmentId);
        requirePatient(actor, appointment);
        new TenantAccessPolicy(manager).readableTenant(appointment.getTenantId());
        List<ClinicalRecord> records = manager.createQuery(
                "select r from ClinicalRecord r where r.appointmentId = :appointmentId and r.tenantId = :tenantId "
                        + "and r.patientId = :patientId", ClinicalRecord.class)
                .setParameter("appointmentId", appointmentId).setParameter("tenantId", appointment.getTenantId())
                .setParameter("patientId", actor.getUserId()).setMaxResults(1).getResultList();
        if (records.isEmpty()) {
            throw new IllegalStateException("record not ready");
        }
        return records.get(0);
    }

    private Account requireDoctor(CurrentActor actor, Appointment appointment) {
        TenantAccessPolicy policy = new TenantAccessPolicy(manager);
        Account doctor = policy.requireAccount(actor, Role.DOCTOR);
        if (appointment == null || !appointment.getDoctorId().equals(doctor.getId())
                || !appointment.getTenantId().equals(doctor.getTenantId())) {
            throw new AccessDeniedException();
        }
        policy.readableTenant(doctor.getTenantId());
        return doctor;
    }

    private void requirePatient(CurrentActor actor, Appointment appointment) {
        Account patient = new TenantAccessPolicy(manager).requireAccount(actor, Role.PATIENT);
        if (appointment == null || !appointment.getPatientId().equals(patient.getId())) {
            throw new AccessDeniedException();
        }
    }
}
