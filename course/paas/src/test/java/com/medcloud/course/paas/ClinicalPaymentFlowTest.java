package com.medcloud.course.paas;

import com.medcloud.course.contract.Role;
import com.medcloud.course.contract.CompleteConsultationRequest;
import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.paas.api.ClinicalResource;
import com.medcloud.course.paas.api.PaymentResource;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.appointment.BookingService;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.consultation.ClinicalRecord;
import com.medcloud.course.paas.consultation.ClinicalService;
import com.medcloud.course.paas.order.PaymentOrder;
import com.medcloud.course.paas.order.PaymentService;
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
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ClinicalPaymentFlowTest {
    @Test
    void bookingThroughDiagnosisAndIdempotentPaymentIsPersistedWithAccessChecks() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "仁和云诊所"));
            manager.persist(new Tenant("tenant-b", "安康云门诊"));
            manager.persist(new Department("department-a", "tenant-a", "全科"));
            manager.persist(new Account("doctor-a", "doctor_a", BCrypt.hashpw("Doctor@123456", BCrypt.gensalt(4)),
                    Role.DOCTOR, "tenant-a"));
            manager.persist(new Account("doctor-b", "doctor_b", "hash", Role.DOCTOR, "tenant-b"));
            manager.persist(new Account("patient-a", "patient_a", BCrypt.hashpw("Demo@123456", BCrypt.gensalt(4)),
                    Role.PATIENT, null));
            manager.persist(new Account("patient-b", "patient_b", "hash", Role.PATIENT, null));
            manager.persist(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                    Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                    1, new BigDecimal("39.00")));
            manager.getTransaction().commit();
            CurrentActor patientA = new CurrentActor("patient-a", Role.PATIENT, null);
            CurrentActor patientB = new CurrentActor("patient-b", Role.PATIENT, null);
            CurrentActor doctorA = new CurrentActor("doctor-a", Role.DOCTOR, "tenant-a");
            CurrentActor doctorB = new CurrentActor("doctor-b", Role.DOCTOR, "tenant-b");
            manager.getTransaction().begin();
            Appointment appointment = new BookingService(manager).book(patientA, "slot-a", Date.valueOf("2030-05-20"));
            manager.getTransaction().commit();
            ClinicalService clinical = new ClinicalService(manager);
            PaymentService payments = new PaymentService(manager);
            AuthService auth = new AuthService(manager, "course-test-signing-secret-at-least-32-characters");
            String doctorBearer = "Bearer " + auth.login(new LoginRequest("doctor_a", "Doctor@123456"))
                    .getAccessToken();
            String patientBearer = "Bearer " + auth.login(new LoginRequest("patient_a", "Demo@123456"))
                    .getAccessToken();
            ClinicalResource clinicalApi = new ClinicalResource(auth, clinical);
            PaymentResource paymentApi = new PaymentResource(auth, payments);

            manager.getTransaction().begin();
            assertThrows(SecurityException.class, () -> clinical.start(doctorB, appointment.getId()));
            clinicalApi.start(doctorBearer, appointment.getId());
            manager.getTransaction().commit();
            assertEquals("IN_PROGRESS", appointment.getStatus());
            org.junit.jupiter.api.Assertions.assertNotNull(appointment.getStartedAt());
            assertThrows(IllegalArgumentException.class, () -> clinical.complete(doctorA, appointment.getId(),
                    null, " ", "建议休息", null, java.time.Instant.parse("2030-05-21T01:00:00Z")));
            assertThrows(IllegalArgumentException.class, () -> clinical.complete(doctorA, appointment.getId(),
                    null, "诊断", " ", null, java.time.Instant.parse("2030-05-21T01:00:00Z")));
            manager.getTransaction().begin();
            String recordId = clinicalApi.complete(doctorBearer, appointment.getId(),
                    new CompleteConsultationRequest("急性上呼吸道感染", "建议休息和补水")).getData().getId();
            manager.getTransaction().commit();
            ClinicalRecord record = clinical.forPatient(patientA, appointment.getId());
            assertEquals(recordId, record.getId());
            assertEquals("急性上呼吸道感染", record.getDiagnosis());
            org.junit.jupiter.api.Assertions.assertNotNull(record.getCompletedAt());
            assertEquals("COMPLETED", manager.find(Appointment.class, appointment.getId()).getStatus());
            assertEquals(record.getId(), clinical.forPatient(patientA, appointment.getId()).getId());
            assertEquals(record.getId(), clinicalApi.record(patientBearer, appointment.getId()).getData().getId());
            assertEquals(record.getId(), clinicalApi.records(doctorBearer, null, null).getData().get(0).getId());
            assertThrows(SecurityException.class, () -> clinical.forPatient(patientB, appointment.getId()));
            manager.getTransaction().begin();
            assertThrows(SecurityException.class, () -> clinical.complete(doctorB, appointment.getId(), "伪造", "伪造"));
            assertThrows(IllegalStateException.class, () -> clinical.complete(doctorA, appointment.getId(), "覆盖", "覆盖"));
            manager.getTransaction().rollback();
            assertEquals(1L, manager.createQuery("select count(r) from ClinicalRecord r", Long.class).getSingleResult());

            PaymentOrder order = payments.forPatient(patientA, appointment.getId());
            assertEquals(order.getId(), paymentApi.order(patientBearer, appointment.getId()).getData().getId());
            assertEquals(new BigDecimal("39.00"), order.getAmount());
            assertThrows(SecurityException.class, () -> payments.forPatient(patientB, appointment.getId()));
            manager.getTransaction().begin();
            assertThrows(SecurityException.class, () -> payments.pay(patientB, order.getId()));
            assertThrows(SecurityException.class, () -> payments.pay(
                    new CurrentActor("doctor-a", Role.HOSPITAL_ADMIN, "tenant-a"), order.getId()));
            PaymentOrder paid = payments.pay(patientA, order.getId());
            Timestamp firstPaidAt = paid.getPaidAt();
            assertNotNull(firstPaidAt);
            PaymentOrder paidAgain = payments.pay(patientA, order.getId());
            assertEquals("PAID", paymentApi.pay(patientBearer, order.getId()).getData().getStatus());
            assertEquals(firstPaidAt, paidAgain.getPaidAt());
            assertNotNull(paymentApi.order(patientBearer, appointment.getId()).getData().getPaidAt());
            manager.getTransaction().commit();
            manager.clear();
            assertEquals(firstPaidAt, manager.find(PaymentOrder.class, order.getId()).getPaidAt());
            assertEquals("PAID", paid.getStatus());
            assertEquals(order.getId(), paidAgain.getId());
            assertEquals(1L, manager.createQuery("select count(o) from PaymentOrder o", Long.class).getSingleResult());
        } finally {
            manager.close();
            factory.close();
        }
    }
}
