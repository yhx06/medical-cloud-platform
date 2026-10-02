package com.medcloud.course.paas;

import com.medcloud.course.contract.Role;
import com.medcloud.course.contract.SlotRequest;
import com.medcloud.course.paas.appointment.BookingService;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.schedule.SlotAdminService;
import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.DoctorProfile;
import com.medcloud.course.paas.tenant.HospitalDepartmentService;
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
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MySqlSlotSnapshotTest {
    @Test
    void bookingCommittedAfterAdminSnapshotPreventsSlotWithdrawal() {
        String url = System.getenv("COURSE_MYSQL_TEST_URL");
        assumeTrue(url != null, "Run deploy Pester tests to provision a disposable MySQL fixture");
        assertTrue(url.matches("^jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/course_slot_test_[a-f0-9]{32}\\?.*$"),
                "Only a dedicated local fixture database may be recreated");
        Map<String, String> settings = new HashMap<String, String>();
        settings.put("javax.persistence.jdbc.driver", "com.mysql.cj.jdbc.Driver");
        settings.put("javax.persistence.jdbc.url", url);
        settings.put("javax.persistence.jdbc.user", System.getenv("COURSE_MYSQL_TEST_USER"));
        settings.put("javax.persistence.jdbc.password", System.getenv("COURSE_MYSQL_TEST_PASSWORD"));
        settings.put("hibernate.dialect", "org.hibernate.dialect.MySQL5Dialect");
        settings.put("hibernate.connection.isolation", "4");
        settings.put("hibernate.hbm2ddl.auto", "create-drop");
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest", settings);
        EntityManager admin = factory.createEntityManager();
        EntityManager patient = factory.createEntityManager();
        Date day = Date.valueOf(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1));
        CurrentActor hospital = new CurrentActor("admin", Role.HOSPITAL_ADMIN, "tenant");
        try {
            patient.getTransaction().begin();
            patient.persist(new Tenant("tenant", "Hospital"));
            patient.persist(new Department("department", "tenant", "Department"));
            patient.persist(new Account("admin", "admin", "hash", Role.HOSPITAL_ADMIN, "tenant"));
            patient.persist(new Account("doctor", "doctor", "hash", Role.DOCTOR, "tenant"));
            patient.persist(new DoctorProfile("doctor", "tenant", "department", "Doctor"));
            patient.persist(new Account("patient", "patient", "hash", Role.PATIENT, null));
            patient.persist(new ScheduleSlot("slot", "tenant", "department", "doctor", day,
                    Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 1, BigDecimal.TEN));
            patient.getTransaction().commit();

            admin.getTransaction().begin();
            // Establish a repeatable-read snapshot before the competing booking commits.
            assertEquals("tenant", new HospitalDepartmentService(admin).tenant(hospital));
            patient.getTransaction().begin();
            new BookingService(patient).book(new CurrentActor("patient", Role.PATIENT, null), "slot",
                    Date.valueOf(day.toLocalDate().minusDays(1)));
            patient.getTransaction().commit();

            SlotRequest withdrawal = new SlotRequest("department", "doctor", day, Time.valueOf("09:00:00"),
                    Time.valueOf("10:00:00"), 1, BigDecimal.TEN, "WITHDRAWN");
            assertThrows(IllegalStateException.class, () -> new SlotAdminService(admin).update(hospital, "slot", withdrawal));
            admin.getTransaction().rollback();
            admin.clear();
            assertEquals("PUBLISHED", admin.find(ScheduleSlot.class, "slot").getStatus());
            assertEquals(1L, admin.createQuery("select count(a) from Appointment a where a.status = 'BOOKED'", Long.class).getSingleResult());
        } finally {
            if (admin.getTransaction().isActive()) admin.getTransaction().rollback();
            if (patient.getTransaction().isActive()) patient.getTransaction().rollback();
            admin.close(); patient.close(); factory.close();
        }
    }
}
