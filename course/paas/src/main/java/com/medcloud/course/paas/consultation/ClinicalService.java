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

@Stateless
public class ClinicalService {
    @PersistenceContext(unitName = "medicalCloudCourse")
    private EntityManager manager;

    public ClinicalService() { }
    public ClinicalService(EntityManager manager) { this.manager = manager; }

    public Appointment start(CurrentActor actor, String appointmentId) {
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
        appointment.start();
        return appointment;
    }

    public ClinicalRecord complete(CurrentActor actor, String appointmentId, String diagnosis, String advice) {
        Appointment appointment = manager.find(Appointment.class, appointmentId, LockModeType.PESSIMISTIC_WRITE);
        requireDoctor(actor, appointment);
        appointment.complete();
        ClinicalRecord record = new ClinicalRecord(UUID.randomUUID().toString(), appointmentId,
                appointment.getTenantId(), appointment.getDoctorId(), appointment.getPatientId(), diagnosis, advice);
        PaymentOrder order = new PaymentOrder(UUID.randomUUID().toString(), appointmentId,
                appointment.getPatientId(), appointment.getTenantId(), appointment.getFee());
        manager.persist(record);
        manager.persist(order);
        return record;
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
