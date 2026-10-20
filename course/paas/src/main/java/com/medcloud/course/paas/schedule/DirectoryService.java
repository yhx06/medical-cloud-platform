package com.medcloud.course.paas.schedule;

import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.Tenant;
import com.medcloud.course.contract.CatalogOption;
import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.PageResult;
import java.util.ArrayList;

import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.sql.Date;
import java.sql.Time;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    public List<CatalogOption> hospitals() {
        return manager.createQuery("select new com.medcloud.course.contract.CatalogOption(t.id, t.name) "
                + "from Tenant t where t.status = 'ACTIVE' order by t.name, t.id", CatalogOption.class)
                .getResultList();
    }

    public List<CatalogOption> departments(String tenantId) {
        String id = requiredTenant(tenantId);
        return manager.createQuery("select new com.medcloud.course.contract.CatalogOption(d.id, d.name) "
                + "from Department d, Tenant t where d.tenantId = t.id and t.id = :tenantId "
                + "and t.status = 'ACTIVE' and d.status = 'ACTIVE' order by d.name, d.id", CatalogOption.class)
                .setParameter("tenantId", id).getResultList();
    }

    public List<CatalogOption> doctors(String tenantId, String departmentId) {
        String id = requiredTenant(tenantId);
        return manager.createQuery("select new com.medcloud.course.contract.CatalogOption(p.userId, p.displayName) "
                + "from DoctorProfile p, Account a, Department d, Tenant t "
                + "where p.userId = a.id and p.tenantId = t.id and a.tenantId = t.id "
                + "and p.departmentId = d.id and d.tenantId = t.id and t.id = :tenantId "
                + "and t.status = 'ACTIVE' and d.status = 'ACTIVE' and a.status = 'ACTIVE' "
                + "and a.role = :role and (:departmentId is null or d.id = :departmentId) "
                + "order by p.displayName, p.userId", CatalogOption.class)
                .setParameter("tenantId", id)
                .setParameter("departmentId", emptyToNull(departmentId))
                .setParameter("role", Role.DOCTOR).getResultList();
    }

    private static String requiredTenant(String value) {
        String id = emptyToNull(value);
        if (id == null) throw new IllegalArgumentException("tenantId required");
        return id;
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
        for (int page = 1; ; page++) {
            PageResult<SlotAvailability> window = availableSlots(tenantId, null, null, null, minimumDate, page, 100);
            for (SlotAvailability availability : window.getItems()) result.add(availability.getSlot());
            if (window.getTotal() <= (long) page * 100) break;
        }
        return result;
    }

    public PageResult<SlotAvailability> availableSlots(String tenantId, String departmentId, String doctorId,
                                                  Date date, Date minimumDate, int page, int size) {
        if (tenantId == null || tenantId.trim().isEmpty() || minimumDate == null)
            throw new IllegalArgumentException("invalid slot search");
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("invalid pagination");
        long offset = ((long) page - 1) * size;
        if (offset > Integer.MAX_VALUE) throw new IllegalArgumentException("pagination offset too large");
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        String where = " where slot.tenantId = :tenantId and slot.date >= :minimumDate "
                + "and (slot.date > :currentDate or (slot.date = :currentDate and slot.startTime > :currentTime)) "
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
                + "and (select count(a) from Appointment a where a.slotId = slot.id "
                + "and a.status <> 'CANCELED') < slot.capacity ";
        javax.persistence.TypedQuery<Long> count = manager.createQuery(
                "select count(slot) from ScheduleSlot slot" + where, Long.class)
                .setParameter("tenantId", tenantId)
                .setParameter("minimumDate", minimumDate)
                .setParameter("currentDate", Date.valueOf(now.toLocalDate()))
                .setParameter("currentTime", Time.valueOf(now.toLocalTime()))
                .setParameter("departmentId", emptyToNull(departmentId))
                .setParameter("doctorId", emptyToNull(doctorId))
                .setParameter("date", date)
                .setParameter("doctorRole", com.medcloud.course.contract.Role.DOCTOR);
        long total = count.getSingleResult();
        List<ScheduleSlot> candidates = manager.createQuery(
                "select slot from ScheduleSlot slot" + where
                        + "order by slot.date, slot.startTime, slot.id", ScheduleSlot.class)
                .setParameter("tenantId", tenantId)
                .setParameter("minimumDate", minimumDate)
                .setParameter("currentDate", Date.valueOf(now.toLocalDate()))
                .setParameter("currentTime", Time.valueOf(now.toLocalTime()))
                .setParameter("departmentId", emptyToNull(departmentId))
                .setParameter("doctorId", emptyToNull(doctorId))
                .setParameter("date", date)
                .setParameter("doctorRole", com.medcloud.course.contract.Role.DOCTOR)
                .setFirstResult((int) offset).setMaxResults(size)
                .getResultList();
        List<SlotAvailability> result = new ArrayList<SlotAvailability>();
        for (ScheduleSlot slot : candidates) {
            Long occupied = manager.createQuery("select count(a) from Appointment a where a.slotId = :slotId "
                    + "and a.status <> 'CANCELED'", Long.class)
                    .setParameter("slotId", slot.getId()).getSingleResult();
            int remaining = slot.getCapacity() - occupied.intValue();
            if (remaining > 0) result.add(new SlotAvailability(slot, remaining));
        }
        return new PageResult<SlotAvailability>(result, total, page, size);
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
