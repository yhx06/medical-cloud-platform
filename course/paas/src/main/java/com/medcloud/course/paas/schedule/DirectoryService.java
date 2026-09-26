package com.medcloud.course.paas.schedule;

import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.Tenant;

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
        return manager.createQuery(
                "select slot from ScheduleSlot slot "
                        + "where slot.tenantId = :tenantId and slot.date >= :minimumDate "
                        + "and slot.status = 'PUBLISHED' "
                        + "and exists (select tenant.id from Tenant tenant "
                        + "where tenant.id = slot.tenantId and tenant.status = 'ACTIVE') "
                        + "and exists (select department.id from Department department "
                        + "where department.id = slot.departmentId and department.tenantId = slot.tenantId "
                        + "and department.status = 'ACTIVE') "
                        + "order by slot.date, slot.startTime, slot.id", ScheduleSlot.class)
                .setParameter("tenantId", tenantId)
                .setParameter("minimumDate", minimumDate)
                .getResultList();
    }
}
