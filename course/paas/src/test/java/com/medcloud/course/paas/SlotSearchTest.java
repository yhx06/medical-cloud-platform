package com.medcloud.course.paas;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.api.CatalogResource;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.schedule.DirectoryService;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.schedule.SlotAvailability;
import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.DoctorProfile;
import com.medcloud.course.paas.tenant.Tenant;
import org.junit.jupiter.api.Test;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SlotSearchTest {
    @Test void filtersCombinedScopeAndExcludesFullOrInactiveSlots() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "甲医院"));
            manager.persist(new Department("dept-a", "tenant-a", "内科"));
            manager.persist(new Department("dept-b", "tenant-a", "外科"));
            manager.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
            manager.persist(new Account("doctor-b", "doctor_b", "hash", Role.DOCTOR, "tenant-a"));
            manager.persist(new DoctorProfile("doctor-a", "tenant-a", "dept-a", "甲"));
            manager.persist(new DoctorProfile("doctor-b", "tenant-a", "dept-b", "乙"));
            slot(manager, "open", "dept-a", "doctor-a", "2035-05-01", 2);
            slot(manager, "full", "dept-a", "doctor-a", "2035-05-02", 1);
            slot(manager, "other", "dept-b", "doctor-b", "2035-05-01", 2);
            manager.persist(new Appointment(UUID.randomUUID().toString(), "patient-a", "open", "tenant-a", "doctor-a", BigDecimal.TEN, Date.valueOf("2035-04-30")));
            manager.persist(new Appointment(UUID.randomUUID().toString(), "patient-b", "full", "tenant-a", "doctor-a", BigDecimal.TEN, Date.valueOf("2035-04-30")));
            manager.getTransaction().commit(); manager.clear();
            DirectoryService directory = new DirectoryService(manager);
            List<SlotAvailability> found = directory.availableSlots("tenant-a", "dept-a", "doctor-a", Date.valueOf("2035-05-01"), Date.valueOf("2035-01-01"));
            assertEquals(1, found.size()); assertEquals("open", found.get(0).getSlot().getId()); assertEquals(1, found.get(0).getRemaining());
            assertTrue(directory.availableSlots("tenant-a", "dept-a", "doctor-b", null, Date.valueOf("2035-01-01")).isEmpty());
            assertEquals(2, directory.availableSlots("tenant-a", Date.valueOf("2035-01-01")).size());
            manager.getTransaction().begin(); manager.find(Account.class, "doctor-a").setStatus("DISABLED"); manager.getTransaction().commit(); manager.clear();
            assertTrue(directory.availableSlots("tenant-a", null, null, null, Date.valueOf("2035-01-01")).stream()
                    .noneMatch(value -> "doctor-a".equals(value.getSlot().getDoctorId())));
        } finally { manager.close(); factory.close(); }
    }

    @Test void malformedDateFailsInsteadOfFallingBackToUnfilteredCatalog() {
        CatalogResource resource = new CatalogResource(new DirectoryService());
        assertThrows(IllegalArgumentException.class, () -> resource.slots("tenant-a", null, null, "not-a-date"));
    }

    private static void slot(EntityManager manager, String id, String department, String doctor, String date, int capacity) {
        manager.persist(new ScheduleSlot(id, "tenant-a", department, doctor, Date.valueOf(date),
                Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), capacity, BigDecimal.TEN));
    }
}
