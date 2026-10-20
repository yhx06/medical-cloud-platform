package com.medcloud.course.paas;

import com.medcloud.course.contract.Role;
import com.medcloud.course.contract.ClinicalRecordView;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.consultation.ClinicalRecord;
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
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

class DoctorRecordTest {
    @Test
    void recordsAreTimedFilteredAndPrivateAcrossDoctorsAndTenants() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "甲院"));
            manager.persist(new Tenant("tenant-b", "乙院"));
            manager.persist(new Department("department-a", "tenant-a", "全科"));
            manager.persist(new Department("department-b", "tenant-b", "全科"));
            manager.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
            manager.persist(new Account("doctor-b", "doctor_b", "hash", Role.DOCTOR, "tenant-b"));
            manager.persist(new Account("patient-a", "patient_a", "hash", Role.PATIENT, null));
            manager.persist(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                    Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 1, BigDecimal.ONE));
            manager.persist(new ScheduleSlot("slot-b", "tenant-b", "department-b", "doctor-b",
                    Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 1, BigDecimal.ONE));
            manager.persist(new Appointment("appointment-a", "patient-a", "slot-a", "tenant-a", "doctor-a",
                    BigDecimal.ONE, Date.valueOf("2030-05-20"), Instant.parse("2030-05-20T00:00:00Z")));
            manager.persist(new Appointment("appointment-b", "patient-a", "slot-b", "tenant-b", "doctor-b",
                    BigDecimal.ONE, Date.valueOf("2030-05-20"), Instant.parse("2030-05-20T00:00:00Z")));
            manager.getTransaction().commit();
            ClinicalService clinical = new ClinicalService(manager);
            CurrentActor doctorA = new CurrentActor("doctor-a", Role.DOCTOR, "tenant-a");
            CurrentActor doctorB = new CurrentActor("doctor-b", Role.DOCTOR, "tenant-b");
            Instant started = Instant.parse("2030-05-21T00:30:00Z");
            Instant completed = Instant.parse("2030-05-21T16:30:00Z"); // 22 May in Shanghai
            manager.getTransaction().begin();
            clinical.start(doctorA, "appointment-a", started);
            clinical.start(doctorB, "appointment-b", started);
            manager.getTransaction().commit();
            manager.getTransaction().begin();
            ClinicalRecord record = clinical.complete(doctorA, "appointment-a", "头痛发热", "感冒", "补水休息", "观察两日", completed);
            clinical.complete(doctorB, "appointment-b", "咳嗽", "感冒", "休息", null, completed);
            manager.getTransaction().commit();
            manager.clear();
            assertEquals(started, manager.find(Appointment.class, "appointment-a").getStartedAt().toInstant());
            ClinicalRecord persisted = clinical.forPatient(new CurrentActor("patient-a", Role.PATIENT, null), "appointment-a");
            assertEquals("头痛发热", persisted.getComplaint());
            assertEquals("补水休息", persisted.getAdvice());
            assertEquals("观察两日", persisted.getNotes());
            assertEquals(completed, persisted.getCompletedAt().toInstant());
            ClinicalRecordView display = new ClinicalRecordView(persisted.getId(), persisted.getAppointmentId(),
                    persisted.getDoctorId(), persisted.getPatientId(), persisted.getComplaint(),
                    persisted.getDiagnosis(), persisted.getAdvice(), persisted.getNotes(), persisted.getCompletedAt());
            assertEquals("2030-05-22 00:30:00", display.getCompletedAt());
            assertEquals(record.getId(), clinical.forDoctor(doctorA, Date.valueOf("2030-05-22"), Date.valueOf("2030-05-22"), 1, 100).getItems().get(0).getId());
            assertTrue(clinical.forDoctor(doctorA, Date.valueOf("2030-05-21"), Date.valueOf("2030-05-21"), 1, 100).getItems().isEmpty());
            assertEquals(1, clinical.forDoctor(doctorA, null, null, 1, 100).getItems().size());
            assertEquals(1, clinical.forDoctor(doctorB, null, null, 1, 100).getItems().size());
            assertThrows(SecurityException.class, () -> clinical.start(doctorB, "appointment-a", started));
            assertEquals(2L, manager.createQuery("select count(r) from ClinicalRecord r", Long.class).getSingleResult());
            assertEquals(2L, manager.createQuery("select count(o) from PaymentOrder o", Long.class).getSingleResult());
        } finally {
            manager.close(); factory.close(); TimeZone.setDefault(original);
        }
    }
}
