package com.medcloud.course.paas.appointment;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.tenant.Tenant;

import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceContext;
import java.sql.Date;
import java.util.List;
import java.util.UUID;

@Stateless
public class BookingService {
    @PersistenceContext(unitName = "medicalCloudCourse")
    private EntityManager manager;

    public BookingService() { }

    public BookingService(EntityManager manager) { this.manager = manager; }

    public Appointment book(CurrentActor actor, String slotId, Date today) {
        if (actor == null || actor.getRole() != Role.PATIENT) {
            throw new SecurityException("patient role required");
        }
        Account patient = manager.find(Account.class, actor.getUserId());
        if (patient == null || !"ACTIVE".equals(patient.getStatus()) || patient.getRole() != Role.PATIENT) {
            throw new SecurityException("inactive patient");
        }
        ScheduleSlot slot = manager.find(ScheduleSlot.class, slotId, LockModeType.PESSIMISTIC_WRITE);
        if (slot == null || !"PUBLISHED".equals(slot.getStatus()) || slot.getDate().before(today)) {
            throw new IllegalStateException("slot unavailable");
        }
        Tenant tenant = manager.find(Tenant.class, slot.getTenantId());
        Account doctor = manager.find(Account.class, slot.getDoctorId());
        if (tenant == null || !"ACTIVE".equals(tenant.getStatus()) || doctor == null
                || !"ACTIVE".equals(doctor.getStatus()) || doctor.getRole() != Role.DOCTOR
                || !slot.getTenantId().equals(doctor.getTenantId())) {
            throw new IllegalStateException("slot directory unavailable");
        }
        List<Appointment> existing = manager.createQuery(
                "select a from Appointment a where a.slotId = :slotId", Appointment.class)
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
        if (actor == null || actor.getRole() != Role.DOCTOR) {
            throw new SecurityException("doctor role required");
        }
        Account doctor = manager.find(Account.class, actor.getUserId());
        if (doctor == null || !"ACTIVE".equals(doctor.getStatus()) || doctor.getRole() != Role.DOCTOR) {
            throw new SecurityException("inactive doctor");
        }
        return manager.createQuery("select a from Appointment a where a.doctorId = :doctorId "
                + "and a.tenantId = :tenantId order by a.bookedOn, a.id", Appointment.class)
                .setParameter("doctorId", doctor.getId()).setParameter("tenantId", doctor.getTenantId())
                .getResultList();
    }
}
