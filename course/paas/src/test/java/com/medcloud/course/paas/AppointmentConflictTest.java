package com.medcloud.course.paas;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.appointment.AppointmentConflictException;
import com.medcloud.course.paas.appointment.BookingService;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.CurrentActor;
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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A patient must not hold two active appointments whose half-open [start, end) intervals overlap,
 * regardless of hospital or doctor. Endpoint adjacency and canceled history stay bookable.
 */
class AppointmentConflictTest {
    private static final Date DAY = Date.valueOf("2030-05-21");
    private static final Date TODAY = Date.valueOf("2030-05-20");

    @Test
    void crossHospitalOverlappingIntervalIsRejected() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            seed(manager);
            manager.getTransaction().commit();
            manager.getTransaction().begin();
            BookingService booking = new BookingService(manager);
            CurrentActor patient = new CurrentActor("patient-a", Role.PATIENT, null);
            booking.book(patient, "slot-a", TODAY);
            manager.getTransaction().commit();

            manager.getTransaction().begin();
            assertThrows(AppointmentConflictException.class,
                    () -> booking.book(patient, "slot-b", TODAY));
            manager.getTransaction().rollback();
            assertEquals(1L, count(manager, "BOOKED"));
        } finally {
            if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
            manager.close();
            factory.close();
        }
    }

    @Test
    void adjacentEndpointsDoNotConflict() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            seed(manager);
            manager.getTransaction().commit();
            manager.getTransaction().begin();
            BookingService booking = new BookingService(manager);
            CurrentActor patient = new CurrentActor("patient-a", Role.PATIENT, null);
            booking.book(patient, "slot-a", TODAY);
            manager.getTransaction().commit();

            manager.getTransaction().begin();
            Appointment second = booking.book(patient, "slot-c", TODAY);
            manager.getTransaction().commit();
            assertEquals("slot-c", second.getSlotId());
            assertEquals(2L, count(manager, "BOOKED"));
        } finally {
            if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
            manager.close();
            factory.close();
        }
    }

    @Test
    void canceledAppointmentFreesItsInterval() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            seed(manager);
            manager.getTransaction().commit();
            manager.getTransaction().begin();
            BookingService booking = new BookingService(manager);
            CurrentActor patient = new CurrentActor("patient-a", Role.PATIENT, null);
            Appointment first = booking.book(patient, "slot-a", TODAY);
            manager.getTransaction().commit();

            manager.getTransaction().begin();
            booking.cancel(patient, first.getId(), Instant.parse("2030-05-20T12:00:00Z"));
            manager.getTransaction().commit();

            manager.getTransaction().begin();
            Appointment overlapping = booking.book(patient, "slot-b", TODAY);
            manager.getTransaction().commit();
            assertEquals("slot-b", overlapping.getSlotId());
        } finally {
            if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
            manager.close();
            factory.close();
        }
    }

    @Test
    void sameSlotDuplicateKeepsTheExistingRule() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            seed(manager);
            manager.getTransaction().commit();
            manager.getTransaction().begin();
            BookingService booking = new BookingService(manager);
            CurrentActor patient = new CurrentActor("patient-a", Role.PATIENT, null);
            booking.book(patient, "slot-a", TODAY);
            manager.getTransaction().commit();

            manager.getTransaction().begin();
            assertThrows(IllegalStateException.class, () -> booking.book(patient, "slot-a", TODAY));
            manager.getTransaction().rollback();
            assertTrue(count(manager, "BOOKED") == 1L);
        } finally {
            if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
            manager.close();
            factory.close();
        }
    }

    private static void seed(EntityManager manager) {
        manager.persist(new Tenant("tenant-a", "仁和云诊所"));
        manager.persist(new Tenant("tenant-b", "安康云门诊"));
        manager.persist(new Department("department-a", "tenant-a", "全科"));
        manager.persist(new Department("department-b", "tenant-b", "内科"));
        manager.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
        manager.persist(new Account("doctor-b", "doctor_b", "hash", Role.DOCTOR, "tenant-b"));
        manager.persist(new DoctorProfile("doctor-a", "tenant-a", "department-a", "王医生"));
        manager.persist(new DoctorProfile("doctor-b", "tenant-b", "department-b", "李医生"));
        manager.persist(new Account("patient-a", "patient_a", "hash", Role.PATIENT, null));
        manager.persist(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                DAY, Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 1, new BigDecimal("39.00")));
        manager.persist(new ScheduleSlot("slot-b", "tenant-b", "department-b", "doctor-b",
                DAY, Time.valueOf("09:30:00"), Time.valueOf("10:30:00"), 1, new BigDecimal("49.00")));
        manager.persist(new ScheduleSlot("slot-c", "tenant-b", "department-b", "doctor-b",
                DAY, Time.valueOf("10:00:00"), Time.valueOf("11:00:00"), 1, new BigDecimal("49.00")));
    }

    private static long count(EntityManager manager, String status) {
        return manager.createQuery("select count(a) from Appointment a where a.status = :status", Long.class)
                .setParameter("status", status).getSingleResult();
    }
}
