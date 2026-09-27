package com.medcloud.course.paas.tenant;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.auth.AccessDeniedException;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.CurrentActor;

import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Stateless
public class HospitalDepartmentService {
    @PersistenceContext(unitName = "medicalCloudCourse")
    private EntityManager manager;
    public HospitalDepartmentService() { }
    public HospitalDepartmentService(EntityManager manager) { this.manager = manager; }

    public List<Department> list(CurrentActor actor) {
        String tenantId = tenant(actor);
        return manager.createQuery("select d from Department d where d.tenantId = :tenantId order by d.name, d.id", Department.class)
                .setParameter("tenantId", tenantId).setMaxResults(200).getResultList();
    }

    public Department create(CurrentActor actor, String name) {
        String tenantId = tenant(actor);
        String validName = name(name);
        if (duplicate(tenantId, validName, null)) throw new IllegalStateException("department name exists");
        Department department = new Department(UUID.randomUUID().toString(), tenantId, validName);
        manager.persist(department);
        return department;
    }

    public Department update(CurrentActor actor, String id, String name, String status) {
        String tenantId = tenant(actor);
        Department department = manager.find(Department.class, id);
        if (department == null || !tenantId.equals(department.getTenantId())) throw new AccessDeniedException();
        String validName = name(name);
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) throw new IllegalArgumentException("invalid status");
        if (duplicate(tenantId, validName, id)) throw new IllegalStateException("department name exists");
        if ("DISABLED".equals(status) && !"DISABLED".equals(department.getStatus())) {
            Long futureSlots = manager.createQuery("select count(s) from ScheduleSlot s where s.departmentId = :id "
                    + "and s.tenantId = :tenantId and s.status = 'PUBLISHED' and s.date >= :today", Long.class)
                    .setParameter("id", id).setParameter("tenantId", tenantId)
                    .setParameter("today", Date.valueOf(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))
                    .getSingleResult();
            Long pending = manager.createQuery("select count(a) from Appointment a, ScheduleSlot s "
                    + "where a.slotId = s.id and s.departmentId = :id and a.tenantId = :tenantId "
                    + "and a.status in ('BOOKED', 'IN_PROGRESS')", Long.class)
                    .setParameter("id", id).setParameter("tenantId", tenantId).getSingleResult();
            if (futureSlots > 0 || pending > 0) throw new IllegalStateException("department has active resources");
        }
        department.update(validName, status);
        return department;
    }

    private boolean duplicate(String tenantId, String name, String exceptId) {
        return manager.createQuery("select count(d) from Department d where d.tenantId = :tenantId "
                + "and d.name = :name and d.id <> :exceptId", Long.class)
                .setParameter("tenantId", tenantId).setParameter("name", name)
                .setParameter("exceptId", exceptId == null ? "" : exceptId).getSingleResult() != 0;
    }

    static String name(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 100)
            throw new IllegalArgumentException("invalid name");
        return value.trim();
    }

    public String tenant(CurrentActor actor) {
        if (actor == null || actor.getRole() != Role.HOSPITAL_ADMIN) throw new AccessDeniedException();
        Account account = manager.find(Account.class, actor.getUserId());
        if (account == null || account.getRole() != Role.HOSPITAL_ADMIN || !"ACTIVE".equals(account.getStatus())
                || account.getTenantId() == null) throw new SecurityException("inactive admin");
        Tenant tenant = manager.find(Tenant.class, account.getTenantId());
        if (tenant == null || !"ACTIVE".equals(tenant.getStatus())) throw new SecurityException("inactive hospital");
        return tenant.getId();
    }
}
