package com.medcloud.course.paas;

import com.medcloud.course.contract.AppointmentHistoryPage;
import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.appointment.BookingService;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.consultation.ClinicalRecord;
import com.medcloud.course.paas.consultation.ClinicalService;
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
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pagination boundaries for the appointment history and doctor record lists. */
class PaginationTest {

    private static final CurrentActor PATIENT = new CurrentActor("patient-a", Role.PATIENT, null);
    private static final CurrentActor OTHER = new CurrentActor("patient-b", Role.PATIENT, null);
    private static final CurrentActor DOCTOR = new CurrentActor("doctor-a", Role.DOCTOR, "tenant-a");

    private void seedAppointments(EntityManager manager) {
        manager.persist(new Tenant("tenant-a", "仁和云诊所"));
        manager.persist(new Tenant("tenant-b", "安康云门诊"));
        manager.persist(new Department("dept-a", "tenant-a", "全科"));
        manager.persist(new Account("patient-a", "patient_a", "x", Role.PATIENT, null));
        manager.persist(new Account("patient-b", "patient_b", "x", Role.PATIENT, null));
        manager.persist(new Account("doctor-a", "doctor_a", "x", Role.DOCTOR, "tenant-a"));
        manager.persist(new DoctorProfile("doctor-a", "tenant-a", "dept-a", "DoctorA"));
        for (int i = 0; i < 45; i++) {
            manager.persist(new ScheduleSlot("slot-" + i, "tenant-a", "dept-a", "doctor-a",
                    Date.valueOf("2030-05-21"), java.sql.Time.valueOf("09:00:00"),
                    java.sql.Time.valueOf("10:00:00"), 10, BigDecimal.TEN));
            manager.persist(new Appointment("appt-" + i, "patient-a", "slot-" + i, "tenant-a", "doctor-a",
                    BigDecimal.TEN, Date.valueOf("2030-05-20"), Instant.parse("2030-05-20T01:00:00Z").plusSeconds(i)));
        }
        // A foreign appointment that must never leak into the patient's total.
        manager.persist(new ScheduleSlot("slot-other", "tenant-b", "dept-a", "doctor-a",
                Date.valueOf("2030-05-21"), java.sql.Time.valueOf("09:00:00"),
                java.sql.Time.valueOf("10:00:00"), 10, BigDecimal.TEN));
        manager.persist(new Appointment("appt-other", "patient-b", "slot-other", "tenant-b", "doctor-a",
                BigDecimal.TEN, Date.valueOf("2030-05-20"), Instant.parse("2030-05-20T02:00:00Z")));
    }

    @Test
    void appointmentHistoryPagesWithoutLeakingOtherPatients() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            seedAppointments(manager);
            manager.getTransaction().commit();
            BookingService service = new BookingService(manager);
            AppointmentHistoryPage first = service.forPatient(PATIENT, 1, 20);
            assertEquals(45, first.getTotal());
            assertEquals(20, first.getItems().size());
            assertEquals("appt-44", first.getItems().get(0).getId());
            AppointmentHistoryPage last = service.forPatient(PATIENT, 3, 20);
            assertEquals(45, last.getTotal());
            assertEquals(5, last.getItems().size());
            assertEquals("appt-4", last.getItems().get(0).getId());
            AppointmentHistoryPage empty = service.forPatient(PATIENT, 4, 20);
            assertEquals(0, empty.getItems().size());
            AppointmentHistoryPage other = service.forPatient(OTHER, 1, 20);
            assertEquals(1, other.getTotal());
            assertEquals("appt-other", other.getItems().get(0).getId());
            assertThrows(IllegalArgumentException.class, () -> service.forPatient(PATIENT, 1, 101));
            assertThrows(IllegalArgumentException.class, () -> service.forPatient(PATIENT, 0, 20));
        } finally {
            manager.close();
            factory.close();
        }
    }

    @Test
    void doctorAppointmentsPageByBookedOn() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            seedAppointments(manager);
            manager.getTransaction().commit();
            PageResult<Appointment> page = new BookingService(manager).forDoctor(DOCTOR, 1, 20);
            assertEquals(45, page.getTotal()); // only tenant-a appointments of doctor-a
            assertEquals(20, page.getItems().size());
        } finally {
            manager.close();
            factory.close();
        }
    }

    private void seedRecords(EntityManager manager) {
        manager.persist(new Tenant("tenant-a", "仁和云诊所"));
        manager.persist(new Department("dept-a", "tenant-a", "全科"));
        manager.persist(new Account("doctor-a", "doctor_a", "x", Role.DOCTOR, "tenant-a"));
        manager.persist(new Account("patient-a", "patient_a", "x", Role.PATIENT, null));
        manager.persist(new DoctorProfile("doctor-a", "tenant-a", "dept-a", "DoctorA"));
        for (int i = 0; i < 25; i++) {
            manager.persist(new ScheduleSlot("slot-" + i, "tenant-a", "dept-a", "doctor-a",
                    Date.valueOf("2030-05-21"), java.sql.Time.valueOf("09:00:00"),
                    java.sql.Time.valueOf("10:00:00"), 10, BigDecimal.TEN));
            manager.persist(new Appointment("appt-" + i, "patient-a", "slot-" + i, "tenant-a", "doctor-a",
                    BigDecimal.TEN, Date.valueOf("2030-05-20"), Instant.parse("2030-05-20T01:00:00Z")));
            manager.persist(new ClinicalRecord("rec-" + i, "appt-" + i, "tenant-a", "doctor-a", "patient-a",
                    "complaint", "diagnosis", "advice", "notes",
                    Instant.parse("2030-05-20T03:00:00Z").plusSeconds(i)));
        }
    }

    @Test
    void doctorRecordsPageNewestFirstWithDateFilter() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            seedRecords(manager);
            manager.getTransaction().commit();
            ClinicalService service = new ClinicalService(manager);
            PageResult<ClinicalRecord> first = service.forDoctor(DOCTOR, null, null, 1, 20);
            assertEquals(25, first.getTotal());
            assertEquals(20, first.getItems().size());
            assertEquals("rec-24", first.getItems().get(0).getId());
            PageResult<ClinicalRecord> last = service.forDoctor(DOCTOR, null, null, 2, 20);
            assertEquals(5, last.getItems().size());
            assertEquals("rec-4", last.getItems().get(0).getId());
            assertThrows(IllegalArgumentException.class, () -> service.forDoctor(DOCTOR, null, null, 1, 101));
        } finally {
            manager.close();
            factory.close();
        }
    }
}
