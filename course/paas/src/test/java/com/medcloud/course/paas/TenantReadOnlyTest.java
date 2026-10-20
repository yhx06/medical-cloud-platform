package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.appointment.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.consultation.*;
import com.medcloud.course.paas.order.*;
import com.medcloud.course.paas.schedule.*;
import com.medcloud.course.paas.tenant.*;
import org.junit.jupiter.api.*;
import org.mindrot.jbcrypt.BCrypt;
import javax.persistence.*;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TenantReadOnlyTest extends TenantFixture {
    @Test
    void disabledHospitalRetainsAuthenticationAndAllAuthorizedHistoryWithOldTokens() {
        AuthService auth = new AuthService(manager, SECRET);
        String doctorToken = AuditLocalTransactions.login(manager, auth, new LoginRequest("doctor_a", PASSWORD)).getAccessToken();
        String adminToken = AuditLocalTransactions.login(manager, auth, new LoginRequest("admin_a", PASSWORD)).getAccessToken();
        assertEquals("ACTIVE", manager.find(Tenant.class, "tenant-a").getStatus());
        setTenantStatus("DISABLED");
        assertEquals(Role.DOCTOR, assertDoesNotThrow(() -> auth.authenticate(doctorToken)).getRole());
        assertEquals(Role.HOSPITAL_ADMIN, assertDoesNotThrow(() -> auth.authenticate(adminToken)).getRole());
        assertDoesNotThrow(() -> AuditLocalTransactions.login(manager, auth, new LoginRequest("doctor_a", PASSWORD)));
        assertDoesNotThrow(() -> AuditLocalTransactions.login(manager, auth, new LoginRequest("admin_a", PASSWORD)));
        assertEquals(1, new HospitalDepartmentService(manager).list(ADMIN, 1, 100).getItems().size());
        assertEquals(1, new HospitalDoctorService(manager).list(ADMIN, 1, 100).getItems().size());
        assertEquals(1, new SlotAdminService(manager).list(ADMIN, 1, 100).getItems().size());
        assertEquals(2, new PaymentService(manager).forHospital(ADMIN, null, null, null, 1, 100).getItems().size());
        assertEquals(4, new BookingService(manager).forDoctor(DOCTOR, 1, 100).getItems().size());
        assertEquals(2, new ClinicalService(manager).forDoctor(DOCTOR, null, null, 1, 100).getItems().size());
        assertEquals(4, new BookingService(manager).forPatient(PATIENT, 1, 100).getItems().size());
        assertEquals("record-unpaid", new ClinicalService(manager).forPatient(PATIENT, "unpaid").getId());
        assertEquals("order-paid", new PaymentService(manager).forPatient(PATIENT, "paid").getId());
        assertThrows(AccessDeniedException.class, () -> new ClinicalService(manager).forPatient(OTHER, "unpaid"));
        assertThrows(AccessDeniedException.class, () -> new PaymentService(manager).forPatient(OTHER, "unpaid"));
        assertThrows(AccessDeniedException.class, () -> new ClinicalService(manager).forPatient(ADMIN, "unpaid"));
        assertEquals(0, new BookingService(manager).forDoctor(new CurrentActor("doctor-b", Role.DOCTOR, "tenant-a"), 1, 100).getItems().size());
        assertThrows(AccessDeniedException.class, () -> new HospitalDepartmentService(manager).list(PLATFORM, 1, 100).getItems());
        assertThrows(AccessDeniedException.class, () -> new HospitalDoctorService(manager).list(DOCTOR, 1, 100).getItems());
        assertThrows(AccessDeniedException.class, () -> new SlotAdminService(manager).list(PATIENT, 1, 100).getItems());
        assertThrows(AccessDeniedException.class, () -> new PaymentService(manager).forHospital(PLATFORM, null, null, null, 1, 100).getItems());
        assertThrows(AccessDeniedException.class, () -> new BookingService(manager).forPatient(ADMIN, 1, 100).getItems());
        assertThrows(AccessDeniedException.class, () -> new BookingService(manager).forDoctor(PLATFORM, 1, 100).getItems());
        assertThrows(AccessDeniedException.class, () -> new ClinicalService(manager).forDoctor(ADMIN, null, null, 1, 100).getItems());
        assertEquals(0, new ClinicalService(manager).forDoctor(new CurrentActor("doctor-b", Role.DOCTOR, "tenant-a"), null, null, 1, 100).getItems().size());
    }

    @Test
    void accountDisableInSeparateTransactionInvalidatesCachedIdentityAndOldToken() {
        AuthService auth = new AuthService(manager, SECRET);
        String token = AuditLocalTransactions.login(manager, auth, new LoginRequest("doctor_a", PASSWORD)).getAccessToken();
        assertEquals("doctor-a", auth.authenticate(token).getUserId());
        setTenantStatus("DISABLED");
        externalUpdate("update Account a set a.status = 'DISABLED' where a.id = 'doctor-a'");
        assertThrows(SecurityException.class, () -> auth.authenticate(token));
        assertThrows(SecurityException.class, () -> AuditLocalTransactions.login(manager, auth, new LoginRequest("doctor_a", PASSWORD)));
        assertThrows(SecurityException.class, () -> new BookingService(manager).forDoctor(DOCTOR, 1, 100).getItems());
        setTenantStatus("ACTIVE");
        assertThrows(SecurityException.class, () -> auth.authenticate(token));
        assertThrows(SecurityException.class, () -> new ClinicalService(manager).forDoctor(DOCTOR, null, null, 1, 100).getItems());
    }

    @Test
    void missingOrUnknownHospitalCannotServePatientOrHospitalHistory() {
        setTenantStatus("UNKNOWN");
        assertThrows(SecurityException.class, () -> new BookingService(manager).forPatient(PATIENT, 1, 100).getItems());
        assertThrows(SecurityException.class, () -> new ClinicalService(manager).forPatient(PATIENT, "unpaid"));
        assertThrows(SecurityException.class, () -> new PaymentService(manager).forPatient(PATIENT, "unpaid"));
        assertThrows(SecurityException.class, () -> new HospitalDepartmentService(manager).list(ADMIN, 1, 100).getItems());
        assertThrows(SecurityException.class, () -> new HospitalDoctorService(manager).list(ADMIN, 1, 100).getItems());
        assertThrows(SecurityException.class, () -> new SlotAdminService(manager).list(ADMIN, 1, 100).getItems());
        assertThrows(SecurityException.class, () -> new PaymentService(manager).forHospital(ADMIN, null, null, null, 1, 100).getItems());
        externalUpdate("delete from Tenant t where t.id = 'tenant-a'");
        assertThrows(SecurityException.class, () -> new BookingService(manager).forPatient(PATIENT, 1, 100).getItems());
        assertThrows(SecurityException.class, () -> new ClinicalService(manager).forPatient(PATIENT, "unpaid"));
        assertThrows(SecurityException.class, () -> new PaymentService(manager).forPatient(PATIENT, "unpaid"));
    }
}

// Shared isolated H2 fixture: all writes are local test transactions, never a runtime database.
abstract class TenantFixture {
    static final String PASSWORD = "Demo@123456";
    static final String SECRET = "course-test-signing-secret-at-least-32-characters";
    static final CurrentActor ADMIN = new CurrentActor("admin-a", Role.HOSPITAL_ADMIN, "tenant-b");
    static final CurrentActor DOCTOR = new CurrentActor("doctor-a", Role.DOCTOR, "tenant-b");
    static final CurrentActor PATIENT = new CurrentActor("patient-a", Role.PATIENT, null);
    static final CurrentActor OTHER = new CurrentActor("patient-b", Role.PATIENT, null);
    static final CurrentActor PLATFORM = new CurrentActor("platform", Role.PLATFORM_ADMIN, null);
    static final Date TODAY = Date.valueOf("2030-05-20");
    static final Instant BEFORE = Instant.parse("2030-05-20T00:00:00Z");
    EntityManagerFactory factory;
    EntityManager manager;

    @BeforeEach
    void fixture() {
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("javax.persistence.jdbc.url", "jdbc:h2:mem:tenant_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest", properties);
        manager = factory.createEntityManager();
        manager.getTransaction().begin();
        String hash = BCrypt.hashpw(PASSWORD, BCrypt.gensalt(4));
        for (String suffix : Arrays.asList("a", "b")) {
            manager.persist(new Tenant("tenant-" + suffix, "虚构医院" + suffix));
            manager.persist(new Department("department-" + suffix, "tenant-" + suffix, "全科"));
            manager.persist(new Account("admin-" + suffix, "admin_" + suffix, hash, Role.HOSPITAL_ADMIN, "tenant-" + suffix));
            manager.persist(new Account("doctor-" + suffix, "doctor_" + suffix, hash, Role.DOCTOR, "tenant-" + suffix));
            manager.persist(new DoctorProfile("doctor-" + suffix, "tenant-" + suffix, "department-" + suffix, "虚构医生"));
            manager.persist(new Account("patient-" + suffix, "patient_" + suffix, hash, Role.PATIENT, null));
            manager.persist(new ScheduleSlot("slot-" + suffix, "tenant-" + suffix, "department-" + suffix, "doctor-" + suffix,
                    Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 20, new BigDecimal("39.25")));
        }
        manager.persist(new Account("platform", "platform", hash, Role.PLATFORM_ADMIN, null));
        for (String id : Arrays.asList("booked", "progress", "unpaid", "paid")) {
            Appointment appointment = new Appointment(id, "patient-a", "slot-a", "tenant-a", "doctor-a",
                    new BigDecimal("39.25"), TODAY, BEFORE);
            if (!"booked".equals(id)) appointment.start(BEFORE.plusSeconds(60));
            if ("unpaid".equals(id) || "paid".equals(id)) {
                appointment.complete();
                manager.persist(new ClinicalRecord("record-" + id, id, "tenant-a", "doctor-a", "patient-a",
                        null, "虚构诊断", "演示建议", null, BEFORE.plusSeconds(120)));
                PaymentOrder order = new PaymentOrder("order-" + id, id, "patient-a", "tenant-a", new BigDecimal("39.25"));
                if ("paid".equals(id)) order.pay(BEFORE.plusSeconds(180));
                manager.persist(order);
            }
            manager.persist(appointment);
        }
        manager.getTransaction().commit();
    }

    void externalUpdate(String jpql) {
        EntityManager other = factory.createEntityManager();
        try {
            other.getTransaction().begin();
            other.createQuery(jpql).executeUpdate();
            other.getTransaction().commit();
        } finally {
            if (other.getTransaction().isActive()) other.getTransaction().rollback();
            other.close();
        }
    }

    void setTenantStatus(String status) {
        externalUpdate("update Tenant t set t.status = '" + status + "' where t.id = 'tenant-a'");
    }

    SlotRequest request() {
        return new SlotRequest("department-a", "doctor-a", Date.valueOf("2030-05-21"),
                Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 20, new BigDecimal("39.25"), "PUBLISHED");
    }

    List<String> snapshot() {
        List<String> result = new ArrayList<String>();
        for (String table : Arrays.asList("user_account", "department", "doctor_profile", "schedule_slot", "appointment", "clinical_record", "payment_order")) {
            List<?> rows = manager.createNativeQuery("select * from " + table).getResultList();
            List<String> values = new ArrayList<String>();
            for (Object row : rows) values.add(Arrays.deepToString((Object[]) row));
            Collections.sort(values);
            result.add(table + values.toString());
        }
        return result;
    }

    @AfterEach
    void closeFixture() {
        if (manager != null) {
            if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
            manager.close();
        }
        if (factory != null) factory.close();
    }
}
