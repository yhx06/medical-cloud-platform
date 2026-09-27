package com.medcloud.course.paas.schedule;

import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.Tenant;
import java.util.ArrayList;

import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.sql.Date;
import java.util.List;

@Stateless
public class DirectoryService {
    @PersistenceContext(unitName = "medicalCloudCourse")
    private EntityManager manager;

    public DirectoryService() {
    }

    public DirectoryService(EntityManager manager) {
        this.manager = manager;
    }

    public void publish(ScheduleSlot slot) {
        Tenant tenant = manager.find(Tenant.class, slot.getTenantId());
        Department department = manager.find(Department.class, slot.getDepartmentId());
        if (tenant == null || !"ACTIVE".equals(tenant.getStatus())
                || department == null || !"ACTIVE".equals(department.getStatus())
                || !slot.getTenantId().equals(department.getTenantId())) {
            throw new IllegalArgumentException("slot directory mismatch");
        }
        manager.persist(slot);
    }

    public List<ScheduleSlot> availableSlots(String tenantId, Date minimumDate) {
        List<ScheduleSlot> result = new ArrayList<ScheduleSlot>();
        for (SlotAvailability availability : availableSlots(tenantId, null, null, null, minimumDate)) {
            result.add(availability.getSlot());
        }
        return result;
    }

    public List<SlotAvailability> availableSlots(String tenantId, String departmentId, String doctorId,
                                                  Date date, Date minimumDate) {
        if (tenantId == null || tenantId.trim().isEmpty() || minimumDate == null)
            throw new IllegalArgumentException("invalid slot search");
        List<ScheduleSlot> candidates = manager.createQuery(
                "select slot from ScheduleSlot slot "
                        + "where slot.tenantId = :tenantId and slot.date >= :minimumDate "
                        + "and (:departmentId is null or slot.departmentId = :departmentId) "
                        + "and (:doctorId is null or slot.doctorId = :doctorId) "
                        + "and (:date is null or slot.date = :date) "
                        + "and slot.status = 'PUBLISHED' "
                        + "and exists (select tenant.id from Tenant tenant "
                        + "where tenant.id = slot.tenantId and tenant.status = 'ACTIVE') "
                        + "and exists (select department.id from Department department "
                        + "where department.id = slot.departmentId and department.tenantId = slot.tenantId "
                        + "and department.status = 'ACTIVE') "
                        + "and exists (select profile.userId from DoctorProfile profile, Account doctor "
                        + "where profile.userId = doctor.id and profile.userId = slot.doctorId "
                        + "and profile.tenantId = slot.tenantId and profile.departmentId = slot.departmentId "
                        + "and doctor.tenantId = slot.tenantId and doctor.status = 'ACTIVE' "
                        + "and doctor.role = :doctorRole) "
                        + "order by slot.date, slot.startTime, slot.id", ScheduleSlot.class)
                .setParameter("tenantId", tenantId)
                .setParameter("minimumDate", minimumDate)
                .setParameter("departmentId", emptyToNull(departmentId))
                .setParameter("doctorId", emptyToNull(doctorId))
                .setParameter("date", date)
                .setParameter("doctorRole", com.medcloud.course.contract.Role.DOCTOR)
                .setMaxResults(200)
                .getResultList();
        List<SlotAvailability> result = new ArrayList<SlotAvailability>();
        for (ScheduleSlot slot : candidates) {
            Long occupied = manager.createQuery("select count(a) from Appointment a where a.slotId = :slotId "
                    + "and a.status <> 'CANCELED'", Long.class)
                    .setParameter("slotId", slot.getId()).getSingleResult();
            int remaining = slot.getCapacity() - occupied.intValue();
            if (remaining > 0) result.add(new SlotAvailability(slot, remaining));
        }
        return result;
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
