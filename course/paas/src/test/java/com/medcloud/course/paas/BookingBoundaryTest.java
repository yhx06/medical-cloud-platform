package com.medcloud.course.paas;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.appointment.BookingService;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.schedule.DirectoryService;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.DoctorProfile;
import com.medcloud.course.paas.tenant.HospitalDoctorService;
import com.medcloud.course.paas.tenant.Tenant;
import org.junit.jupiter.api.Test;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class BookingBoundaryTest {
    @Test
    void startedSlotTodayIsExcludedFromCatalogAndCannotBeBooked() {
        try (Fixture f = new Fixture()) {
            f.manager.getTransaction().begin();
            f.manager.persist(f.slot("started", f.today, "00:00:00"));
            f.manager.persist(f.slot("tomorrow", Date.valueOf(f.today.toLocalDate().plusDays(1)), "09:00:00"));
            f.manager.getTransaction().commit();
            assertEquals("tomorrow", new DirectoryService(f.manager).availableSlots("tenant", f.today).get(0).getId());
            assertEquals(1, new DirectoryService(f.manager).availableSlots("tenant", f.today).size());
            f.manager.getTransaction().begin();
            assertThrows(IllegalStateException.class, () -> new BookingService(f.manager).book(f.patient, "started", f.today));
        }
    }

    @Test
    void transferredDoctorCannotAcceptOldDepartmentSlot() {
        try (Fixture f = new Fixture()) {
            f.manager.getTransaction().begin();
            f.manager.persist(f.slot("old-department", Date.valueOf(f.today.toLocalDate().plusDays(1)), "09:00:00"));
            f.manager.getTransaction().commit();
            f.manager.getTransaction().begin();
            new HospitalDoctorService(f.manager).update(new CurrentActor("admin", Role.HOSPITAL_ADMIN, "tenant"),
                    "doctor", "Doctor", "department-b", "ACTIVE");
            f.manager.getTransaction().commit();
            f.manager.clear();
            assertTrue(new DirectoryService(f.manager).availableSlots("tenant", f.today).isEmpty());
            f.manager.getTransaction().begin();
            assertThrows(IllegalStateException.class, () -> new BookingService(f.manager).book(f.patient, "old-department", f.today));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        final EntityManager manager = factory.createEntityManager();
        final Date today = Date.valueOf(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        final CurrentActor patient = new CurrentActor("patient", Role.PATIENT, null);

        Fixture() {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant", "Hospital"));
            manager.persist(new Department("department-a", "tenant", "A"));
            manager.persist(new Department("department-b", "tenant", "B"));
            manager.persist(new Account("admin", "admin", "hash", Role.HOSPITAL_ADMIN, "tenant"));
            manager.persist(new Account("doctor", "doctor", "hash", Role.DOCTOR, "tenant"));
            manager.persist(new DoctorProfile("doctor", "tenant", "department-a", "Doctor"));
            manager.persist(new Account("patient", "patient", "hash", Role.PATIENT, null));
            manager.getTransaction().commit();
        }

        ScheduleSlot slot(String id, Date date, String start) {
            return new ScheduleSlot(id, "tenant", "department-a", "doctor", date,
                    Time.valueOf(start), Time.valueOf("10:00:00"), 1, BigDecimal.TEN);
        }

        public void close() {
            if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
            manager.close(); factory.close();
        }
    }
}
