package com.medcloud.course.paas;

import com.medcloud.course.contract.BookingRequest;
import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.api.AppointmentResource;
import com.medcloud.course.paas.api.AuthResource;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.appointment.BookingService;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.DoctorProfile;
import com.medcloud.course.paas.tenant.Tenant;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CourseApiTest {
    @Test
    void appointmentApiUsesAuthenticatedPatientNotRequestSuppliedIdentity() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "仁和云诊所"));
            manager.persist(new Department("department-a", "tenant-a", "全科"));
            manager.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
            manager.persist(new DoctorProfile("doctor-a", "tenant-a", "department-a", "Doctor"));
            manager.persist(new Account("patient-a", "patient_a", BCrypt.hashpw("Demo@123456", BCrypt.gensalt(4)),
                    Role.PATIENT, null));
            manager.persist(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                    Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                    1, new BigDecimal("39.00")));
            manager.getTransaction().commit();
            AuthService auth = new AuthService(manager, "course-test-signing-secret-at-least-32-characters");
            AuthResource authResource = new AuthResource(auth, AuditLocalTransactions.runner(manager));
            AppointmentResource resource = new AppointmentResource(auth, new BookingService(manager), AuditClinicalFixture.legacyRunner(manager));
            manager.getTransaction().begin();
            String bearer = "Bearer " + authResource.login(new LoginRequest("patient_a", "Demo@123456"))
                    .getData().getAccessToken();
            manager.getTransaction().commit();

            manager.getTransaction().begin();
            String patientId = resource.book(bearer, new BookingRequest("slot-a")).getData().getPatientId();
            manager.getTransaction().commit();
            assertEquals("patient-a", patientId);
            assertThrows(SecurityException.class,
                    () -> resource.book("Bearer tampered", new BookingRequest("slot-a")));
        } finally {
            manager.close();
            factory.close();
        }
    }
}
