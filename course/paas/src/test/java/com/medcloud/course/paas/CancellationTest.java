package com.medcloud.course.paas;

import com.medcloud.course.contract.AppointmentHistoryView;
import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.appointment.BookingService;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.consultation.ClinicalService;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.Tenant;
import org.junit.jupiter.api.Test;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CancellationTest {
    private static final CurrentActor PATIENT = new CurrentActor("patient-a", Role.PATIENT, null);
    private static final CurrentActor OTHER = new CurrentActor("patient-b", Role.PATIENT, null);
    private static final Instant BEFORE = Instant.parse("2030-05-21T00:59:59Z");
    private static final Instant START = Instant.parse("2030-05-21T01:00:00Z");

    @Test
    void sameDayHistoryUsesPersistedBookingInstantBeforeRandomId() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            seed(manager);
            manager.getTransaction().begin();
            manager.persist(new Appointment("z-older", "patient-a", "slot-a", "tenant-a", "doctor-a",
                    BigDecimal.TEN, Date.valueOf("2030-05-20"), Instant.parse("2030-05-20T01:00:00Z")));
            manager.persist(new Appointment("a-newer", "patient-a", "slot-a", "tenant-a", "doctor-a",
                    BigDecimal.TEN, Date.valueOf("2030-05-20"), Instant.parse("2030-05-20T02:00:00Z")));
            manager.getTransaction().commit();
            List<AppointmentHistoryView> history = new BookingService(manager).forPatient(PATIENT);
            assertEquals("a-newer", history.get(0).getId());
            assertEquals("z-older", history.get(1).getId());
        } finally { close(manager, factory); }
    }

    @Test
    void cancellationPreservesHistoryAndAllowsSamePatientToRebook() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            seed(manager);
            BookingService service = new BookingService(manager);
            manager.getTransaction().begin();
            Instant earliestBooking = Instant.now();
            Appointment first = service.book(PATIENT, "slot-a", Date.valueOf("2030-05-20"));
            manager.getTransaction().commit();
            assertNotNull(first.getBookedAt());
            assertFalse(first.getBookedAt().toInstant().isBefore(earliestBooking));
            assertFalse(first.getBookedAt().toInstant().isAfter(Instant.now()));
            manager.getTransaction().begin();
            assertEquals("CANCELED", service.cancel(PATIENT, first.getId(), BEFORE).getStatus());
            manager.getTransaction().commit();
            manager.getTransaction().begin();
            Appointment second = service.book(PATIENT, "slot-a", Date.valueOf("2030-05-20"));
            manager.getTransaction().commit();
            assertNotEquals(first.getId(), second.getId());
            List<AppointmentHistoryView> history = service.forPatient(PATIENT);
            assertEquals(2, history.size());
            assertTrue(history.stream().anyMatch(row -> "BOOKED".equals(row.getStatus()) && second.getId().equals(row.getId())));
            assertTrue(history.stream().anyMatch(row -> "CANCELED".equals(row.getStatus()) && first.getId().equals(row.getId())));
            assertEquals("仁和云诊所", history.get(0).getHospitalName());
            assertEquals("全科", history.get(0).getDepartmentName());
            assertEquals(Date.valueOf("2030-05-21"), history.get(0).getSlotDate());
            assertEquals(0, service.forPatient(OTHER).size());
        } finally { close(manager, factory); }
    }

    @Test
    void onlyOwnerCanCancelAndCutoffAndRepeatedCancellationConflict() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            seed(manager);
            BookingService service = new BookingService(manager);
            manager.getTransaction().begin();
            Appointment first = service.book(PATIENT, "slot-a", Date.valueOf("2030-05-20"));
            manager.getTransaction().commit();
            assertThrows(SecurityException.class, () -> service.cancel(OTHER, first.getId(), BEFORE));
            manager.getTransaction().begin();
            assertThrows(IllegalStateException.class, () -> service.cancel(PATIENT, first.getId(), START));
            manager.getTransaction().rollback();
            manager.getTransaction().begin();
            service.cancel(PATIENT, first.getId(), BEFORE);
            manager.getTransaction().commit();
            manager.getTransaction().begin();
            assertThrows(IllegalStateException.class, () -> service.cancel(PATIENT, first.getId(), BEFORE));
            manager.getTransaction().rollback();
        } finally { close(manager, factory); }
    }

    @Test
    void startedAppointmentCannotBecomeCanceled() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            seed(manager);
            BookingService service = new BookingService(manager);
            manager.getTransaction().begin();
            Appointment appointment = service.book(PATIENT, "slot-a", Date.valueOf("2030-05-20"));
            manager.getTransaction().commit();
            manager.getTransaction().begin();
            new ClinicalService(manager).start(new CurrentActor("doctor-a", Role.DOCTOR, "tenant-a"), appointment.getId());
            manager.getTransaction().commit();
            manager.getTransaction().begin();
            assertThrows(IllegalStateException.class, () -> service.cancel(PATIENT, appointment.getId(), BEFORE));
            manager.getTransaction().rollback();
            assertEquals("IN_PROGRESS", manager.find(Appointment.class, appointment.getId()).getStatus());
        } finally { close(manager, factory); }
    }

    @Test
    void concurrentCancelAndStartHaveOnlyOneWinningTransition() throws Exception {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        String id;
        EntityManager setup = factory.createEntityManager();
        try {
            seed(setup);
            setup.getTransaction().begin();
            id = new BookingService(setup).book(PATIENT, "slot-a", Date.valueOf("2030-05-20")).getId();
            setup.getTransaction().commit();
        } finally { setup.close(); }
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> cancellation = workers.submit(() -> transition(factory, start,
                    manager -> new BookingService(manager).cancel(PATIENT, id, BEFORE)));
            Future<Boolean> consultation = workers.submit(() -> transition(factory, start,
                    manager -> new ClinicalService(manager).start(
                            new CurrentActor("doctor-a", Role.DOCTOR, "tenant-a"), id)));
            start.countDown();
            int successes = (cancellation.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (consultation.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            EntityManager verify = factory.createEntityManager();
            try {
                String status = verify.find(Appointment.class, id).getStatus();
                assertTrue("CANCELED".equals(status) || "IN_PROGRESS".equals(status));
                assertEquals("CANCELED".equals(status) ? 0L : 1L,
                        verify.createQuery("select count(a) from Appointment a where a.slotId = 'slot-a' "
                                + "and a.status <> 'CANCELED'", Long.class).getSingleResult());
            } finally { verify.close(); }
        } finally { workers.shutdownNow(); factory.close(); }
    }

    @Test
    void concurrentBookingCancellationAndStartNeverOverbookLastPlace() throws Exception {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        ExecutorService workers = Executors.newFixedThreadPool(3);
        String id;
        EntityManager setup = factory.createEntityManager();
        try {
            seed(setup);
            setup.getTransaction().begin();
            id = new BookingService(setup).book(PATIENT, "slot-a", Date.valueOf("2030-05-20")).getId();
            setup.getTransaction().commit();
        } finally { setup.close(); }
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> cancellation = workers.submit(() -> transition(factory, start,
                    manager -> new BookingService(manager).cancel(PATIENT, id, BEFORE)));
            Future<Boolean> consultation = workers.submit(() -> transition(factory, start,
                    manager -> new ClinicalService(manager).start(
                            new CurrentActor("doctor-a", Role.DOCTOR, "tenant-a"), id)));
            Future<Boolean> booking = workers.submit(() -> transition(factory, start,
                    manager -> new BookingService(manager).book(OTHER, "slot-a", Date.valueOf("2030-05-20"))));
            start.countDown();
            boolean canceled = cancellation.get(10, TimeUnit.SECONDS);
            boolean started = consultation.get(10, TimeUnit.SECONDS);
            boolean booked = booking.get(10, TimeUnit.SECONDS);
            assertNotEquals(canceled, started, "exactly one transition of the original booking must win");
            if (started) assertFalse(booked, "an in-progress booking still occupies the last place");
            EntityManager verify = factory.createEntityManager();
            try {
                List<Appointment> rows = verify.createQuery("select a from Appointment a where a.slotId = 'slot-a'",
                        Appointment.class).getResultList();
                assertEquals(canceled ? "CANCELED" : "IN_PROGRESS", verify.find(Appointment.class, id).getStatus());
                assertEquals(started || booked ? 1L : 0L,
                        rows.stream().filter(row -> !"CANCELED".equals(row.getStatus())).count());
                assertEquals(booked ? 1L : 0L,
                        rows.stream().filter(row -> OTHER.getUserId().equals(row.getPatientId())).count());
                assertFalse(rows.stream().anyMatch(row -> "CANCELED".equals(row.getStatus())
                        && row.getStartedAt() != null));
            } finally { verify.close(); }
        } finally { workers.shutdownNow(); factory.close(); }
    }

    private static boolean transition(EntityManagerFactory factory, CountDownLatch start,
            java.util.function.Consumer<EntityManager> action) throws InterruptedException {
        EntityManager manager = factory.createEntityManager();
        try {
            start.await();
            manager.getTransaction().begin();
            action.accept(manager);
            manager.getTransaction().commit();
            return true;
        } catch (IllegalStateException rejected) {
            if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
            if (!"appointment cannot cancel".equals(rejected.getMessage())
                    && !"appointment cannot start".equals(rejected.getMessage())
                    && !"slot full".equals(rejected.getMessage())) throw rejected;
            return false;
        } finally { manager.close(); }
    }

    private static void seed(EntityManager manager) {
        manager.getTransaction().begin();
        manager.persist(new Tenant("tenant-a", "仁和云诊所"));
        manager.persist(new Department("department-a", "tenant-a", "全科"));
        manager.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
        manager.persist(new Account("patient-a", "patient_a", "hash", Role.PATIENT, null));
        manager.persist(new Account("patient-b", "patient_b", "hash", Role.PATIENT, null));
        manager.persist(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                1, BigDecimal.TEN));
        manager.getTransaction().commit();
    }

    private static void close(EntityManager manager, EntityManagerFactory factory) {
        if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
        manager.close(); factory.close();
    }
}
