package com.medcloud.course.paas;

import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.contract.LoginResponse;
import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.appointment.BookingService;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.Tenant;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingIsolationTest {
    private static final String SECRET = "course-test-signing-secret-at-least-32-characters";

    @Test
    void loginRejectsDisabledAccountAndTokenReflectsActiveDatabaseState() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            Account patient = new Account("patient-a", "patient_demo",
                    BCrypt.hashpw("Demo@123456", BCrypt.gensalt(4)), Role.PATIENT, null);
            manager.persist(patient);
            manager.getTransaction().commit();
            AuthService auth = new AuthService(manager, SECRET);

            LoginResponse login = auth.login(new LoginRequest("patient_demo", "Demo@123456"));
            assertTrue(login.getAccessToken().length() > 30);
            assertEquals("patient-a", auth.authenticate(login.getAccessToken()).getUserId());
            assertThrows(SecurityException.class, () -> auth.authenticate(login.getAccessToken() + "x"));

            manager.getTransaction().begin();
            patient.disable();
            manager.getTransaction().commit();
            assertThrows(SecurityException.class, () -> auth.authenticate(login.getAccessToken()));
            assertThrows(SecurityException.class,
                    () -> auth.login(new LoginRequest("patient_demo", "Demo@123456")));
        } finally {
            manager.close();
            factory.close();
        }
    }

    @Test
    void bookingDerivesTenantAndDoctorFromSlotAndRejectsDuplicateOrFullSlot() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "仁和云诊所"));
            manager.persist(new Department("department-a", "tenant-a", "全科"));
            manager.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
            manager.persist(new Account("patient-a", "patient_a", "hash", Role.PATIENT, null));
            manager.persist(new Account("patient-b", "patient_b", "hash", Role.PATIENT, null));
            manager.persist(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                    Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                    1, new BigDecimal("39.00")));
            manager.getTransaction().commit();
            BookingService booking = new BookingService(manager);
            CurrentActor patientA = new CurrentActor("patient-a", Role.PATIENT, null);
            CurrentActor patientB = new CurrentActor("patient-b", Role.PATIENT, null);

            manager.getTransaction().begin();
            Appointment created = booking.book(patientA, "slot-a", Date.valueOf("2030-05-20"));
            manager.getTransaction().commit();
            assertEquals("tenant-a", created.getTenantId());
            assertEquals("doctor-a", created.getDoctorId());
            assertEquals(new BigDecimal("39.00"), created.getFee());

            manager.getTransaction().begin();
            assertThrows(IllegalStateException.class,
                    () -> booking.book(patientA, "slot-a", Date.valueOf("2030-05-20")));
            assertThrows(IllegalStateException.class,
                    () -> booking.book(patientB, "slot-a", Date.valueOf("2030-05-20")));
            manager.getTransaction().rollback();
        } finally {
            manager.close();
            factory.close();
        }
    }

    @Test
    void doctorCannotReadAnotherDoctorsOrTenantsAppointments() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "仁和云诊所"));
            manager.persist(new Tenant("tenant-b", "安康云门诊"));
            manager.persist(new Department("department-a", "tenant-a", "全科"));
            manager.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
            manager.persist(new Account("doctor-b", "doctor_b", "hash", Role.DOCTOR, "tenant-b"));
            manager.persist(new Account("patient-a", "patient_a", "hash", Role.PATIENT, null));
            manager.persist(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                    Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                    1, new BigDecimal("39.00")));
            manager.getTransaction().commit();
            BookingService booking = new BookingService(manager);
            manager.getTransaction().begin();
            booking.book(new CurrentActor("patient-a", Role.PATIENT, null), "slot-a", Date.valueOf("2030-05-20"));
            manager.getTransaction().commit();

            assertEquals(1, booking.forDoctor(new CurrentActor("doctor-a", Role.DOCTOR, "tenant-b")).size());
            assertEquals(0, booking.forDoctor(new CurrentActor("doctor-b", Role.DOCTOR, "tenant-a")).size());
            assertThrows(SecurityException.class,
                    () -> booking.forDoctor(new CurrentActor("patient-a", Role.DOCTOR, "tenant-a")));
        } finally {
            manager.close();
            factory.close();
        }
    }

    @Test
    void concurrentPatientsCannotBothTakeLastPlace() throws Exception {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            EntityManager setup = factory.createEntityManager();
            try {
                setup.getTransaction().begin();
                setup.persist(new Tenant("tenant-a", "仁和云诊所"));
                setup.persist(new Department("department-a", "tenant-a", "全科"));
                setup.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
                setup.persist(new Account("patient-a", "patient_a", "hash", Role.PATIENT, null));
                setup.persist(new Account("patient-b", "patient_b", "hash", Role.PATIENT, null));
                setup.persist(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                        Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                        1, new BigDecimal("39.00")));
                setup.getTransaction().commit();
            } finally {
                setup.close();
            }
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> first = workers.submit(() -> attemptBooking(factory, start, "patient-a"));
            Future<Boolean> second = workers.submit(() -> attemptBooking(factory, start, "patient-b"));
            start.countDown();
            int successes = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            EntityManager verify = factory.createEntityManager();
            try {
                assertEquals(1, successes);
                assertEquals(1L, verify.createQuery("select count(a) from Appointment a", Long.class)
                        .getSingleResult());
            } finally {
                verify.close();
            }
        } finally {
            workers.shutdownNow();
            factory.close();
        }
    }

    private static boolean attemptBooking(EntityManagerFactory factory, CountDownLatch start, String patientId)
            throws InterruptedException {
        EntityManager manager = factory.createEntityManager();
        try {
            start.await();
            manager.getTransaction().begin();
            new BookingService(manager).book(new CurrentActor(patientId, Role.PATIENT, null),
                    "slot-a", Date.valueOf("2030-05-20"));
            manager.getTransaction().commit();
            return true;
        } catch (IllegalStateException | javax.persistence.PersistenceException rejected) {
            if (manager.getTransaction().isActive()) {
                manager.getTransaction().rollback();
            }
            return false;
        } finally {
            manager.close();
        }
    }
}
