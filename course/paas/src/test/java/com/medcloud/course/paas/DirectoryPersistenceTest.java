package com.medcloud.course.paas;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.schedule.DirectoryService;
import com.medcloud.course.paas.schedule.ScheduleSlot;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirectoryPersistenceTest {
    @Test
    void onlyFuturePublishedSlotsFromRequestedTenantAreReturned() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "仁和云诊所"));
            manager.persist(new Tenant("tenant-b", "安康云门诊"));
            manager.persist(new Department("department-a", "tenant-a", "全科"));
            manager.persist(new Department("department-b", "tenant-b", "儿科"));
            manager.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
            manager.persist(new Account("doctor-b", "doctor_b", "hash", Role.DOCTOR, "tenant-b"));
            manager.persist(new DoctorProfile("doctor-a", "tenant-a", "department-a", "甲医生"));
            manager.persist(new DoctorProfile("doctor-b", "tenant-b", "department-b", "乙医生"));
            DirectoryService directory = new DirectoryService(manager);
            directory.publish(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                    Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                    8, new BigDecimal("39.00")));
            directory.publish(new ScheduleSlot("slot-b", "tenant-b", "department-b", "doctor-b",
                    Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                    8, new BigDecimal("29.00")));
            directory.publish(new ScheduleSlot("slot-past", "tenant-a", "department-a", "doctor-a",
                    Date.valueOf("2030-05-19"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                    8, new BigDecimal("39.00")));
            manager.getTransaction().commit();
            manager.clear();

            List<ScheduleSlot> results = directory.availableSlots("tenant-a", Date.valueOf("2030-05-20"));

            assertEquals(1, results.size());
            assertEquals("slot-a", results.get(0).getId());
        } finally {
            manager.close();
            factory.close();
        }
    }

    @Test
    void publishingSlotWithAnotherTenantsDepartmentIsRejected() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "仁和云诊所"));
            manager.persist(new Tenant("tenant-b", "安康云门诊"));
            manager.persist(new Department("department-b", "tenant-b", "儿科"));
            DirectoryService directory = new DirectoryService(manager);

            assertThrows(IllegalArgumentException.class, () -> directory.publish(
                    new ScheduleSlot("slot-cross", "tenant-a", "department-b", "doctor-a",
                            Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"),
                            Time.valueOf("10:00:00"), 8, new BigDecimal("39.00"))));
            manager.getTransaction().rollback();
        } finally {
            manager.close();
            factory.close();
        }
    }
}
