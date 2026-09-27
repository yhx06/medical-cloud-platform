package com.medcloud.course.paas.consultation;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.AccessDeniedException;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.order.PaymentOrder;
import com.medcloud.course.paas.schedule.ScheduleSlot;

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
    public ClinicalService(EntityManager manager) { this.manager = manager; }

    public Appointment start(CurrentActor actor, String appointmentId) {
        return start(actor, appointmentId, Instant.now());
    }

    public Appointment start(CurrentActor actor, String appointmentId, Instant now) {
        if (now == null) throw new IllegalArgumentException("start time required");
        Appointment candidate = manager.find(Appointment.class, appointmentId);
        requireDoctor(actor, candidate);
        Account doctor = manager.find(Account.class, actor.getUserId(), LockModeType.PESSIMISTIC_WRITE);
        manager.refresh(doctor, LockModeType.PESSIMISTIC_WRITE);
        if (!"ACTIVE".equals(doctor.getStatus())) throw new AccessDeniedException();
        ScheduleSlot slot = manager.find(ScheduleSlot.class, candidate.getSlotId(), LockModeType.PESSIMISTIC_WRITE);
        if (slot == null) throw new AccessDeniedException();
        manager.refresh(slot, LockModeType.PESSIMISTIC_WRITE);
        Appointment appointment = manager.find(Appointment.class, appointmentId, LockModeType.PESSIMISTIC_WRITE);
        manager.refresh(appointment, LockModeType.PESSIMISTIC_WRITE);
        requireDoctor(actor, appointment);
        if (!slot.getId().equals(appointment.getSlotId())) throw new AccessDeniedException();
        appointment.start(now);
        return appointment;
    }

    public ClinicalRecord complete(CurrentActor actor, String appointmentId, String diagnosis, String advice) {
        return complete(actor, appointmentId, null, diagnosis, advice, null, Instant.now());
    }

    public ClinicalRecord complete(CurrentActor actor, String appointmentId, String complaint,
                                   String diagnosis, String advice, String notes, Instant now) {
        if (diagnosis == null || diagnosis.trim().isEmpty() || advice == null || advice.trim().isEmpty()
                || now == null) throw new IllegalArgumentException("diagnosis, advice and time required");
        Appointment candidate = manager.find(Appointment.class, appointmentId);
        requireDoctor(actor, candidate);
        Account doctor = manager.find(Account.class, actor.getUserId(), LockModeType.PESSIMISTIC_WRITE);
        manager.refresh(doctor, LockModeType.PESSIMISTIC_WRITE);
        if (!"ACTIVE".equals(doctor.getStatus())) throw new AccessDeniedException();
        ScheduleSlot slot = manager.find(ScheduleSlot.class, candidate.getSlotId(), LockModeType.PESSIMISTIC_WRITE);
        if (slot == null) throw new AccessDeniedException();
        manager.refresh(slot, LockModeType.PESSIMISTIC_WRITE);
        Appointment appointment = manager.find(Appointment.class, appointmentId, LockModeType.PESSIMISTIC_WRITE);
        manager.refresh(appointment, LockModeType.PESSIMISTIC_WRITE);
        requireDoctor(actor, appointment);
        if (!slot.getId().equals(appointment.getSlotId())) throw new AccessDeniedException();
        appointment.complete();
        ClinicalRecord record = new ClinicalRecord(UUID.randomUUID().toString(), appointmentId,
                appointment.getTenantId(), appointment.getDoctorId(), appointment.getPatientId(),
                complaint, diagnosis, advice, notes, now);
        PaymentOrder order = new PaymentOrder(UUID.randomUUID().toString(), appointmentId,
                appointment.getPatientId(), appointment.getTenantId(), appointment.getFee());
        manager.persist(record);
        manager.persist(order);
        return record;
    }

    public List<ClinicalRecord> forDoctor(CurrentActor actor, Date from, Date to) {
        if (actor == null || actor.getRole() != Role.DOCTOR) throw new AccessDeniedException();
        Account doctor = manager.find(Account.class, actor.getUserId());
        if (doctor == null || doctor.getRole() != Role.DOCTOR || !"ACTIVE".equals(doctor.getStatus())
                || doctor.getTenantId() == null) throw new AccessDeniedException();
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
        List<ClinicalRecord> records = manager.createQuery(
                "select r from ClinicalRecord r where r.appointmentId = :appointmentId", ClinicalRecord.class)
                .setParameter("appointmentId", appointmentId).setMaxResults(1).getResultList();
        if (records.isEmpty()) {
            throw new IllegalStateException("record not ready");
        }
        return records.get(0);
    }

    private void requireDoctor(CurrentActor actor, Appointment appointment) {
        if (appointment == null || actor == null || actor.getRole() != Role.DOCTOR) {
            throw new AccessDeniedException();
        }
        Account doctor = manager.find(Account.class, actor.getUserId());
        if (doctor == null || !"ACTIVE".equals(doctor.getStatus()) || doctor.getRole() != Role.DOCTOR
                || !appointment.getDoctorId().equals(doctor.getId())
                || !appointment.getTenantId().equals(doctor.getTenantId())) {
            throw new AccessDeniedException();
        }
    }

    private void requirePatient(CurrentActor actor, Appointment appointment) {
        if (appointment == null || actor == null || actor.getRole() != Role.PATIENT) {
            throw new AccessDeniedException();
        }
        Account patient = manager.find(Account.class, actor.getUserId());
        if (patient == null || !"ACTIVE".equals(patient.getStatus()) || patient.getRole() != Role.PATIENT
                || !appointment.getPatientId().equals(patient.getId())) {
            throw new AccessDeniedException();
        }
    }
}
