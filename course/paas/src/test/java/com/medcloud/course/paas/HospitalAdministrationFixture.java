package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.tenant.*;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import javax.persistence.*;
import org.junit.jupiter.api.*;
import org.mindrot.jbcrypt.BCrypt;
import java.util.*;
import java.util.function.Function;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;

public abstract class HospitalAdministrationFixture {
    public static final CurrentActor PLATFORM = new CurrentActor("platform-1", Role.PLATFORM_ADMIN, null);
    public static final CurrentActor PLATFORM_OTHER = new CurrentActor("platform-2", Role.PLATFORM_ADMIN, null);
    protected static final CurrentActor ADMIN = new CurrentActor("admin-a", Role.HOSPITAL_ADMIN, "tenant-a");
    protected static final String PASSWORD = "Fiction@12345";
    protected EntityManagerFactory factory;

    protected Map<String,Object> databaseProperties() {
        Map<String,Object> properties = new HashMap<String,Object>();
        properties.put("javax.persistence.jdbc.url", "jdbc:h2:mem:onboarding_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        return properties;
    }
    @BeforeEach
    void setUpAdministration() {
        factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest", databaseProperties());
        inTx(em -> {
            String hash = BCrypt.hashpw(PASSWORD, BCrypt.gensalt(4));
            em.persist(new Account("platform-1", "platform_1", hash, Role.PLATFORM_ADMIN, null));
            em.persist(new Account("platform-2", "platform_2", hash, Role.PLATFORM_ADMIN, null));
            for (String suffix : Arrays.asList("a", "b")) {
                String tenant = "tenant-" + suffix;
                em.persist(new Tenant(tenant, "虚构医院" + suffix));
                em.persist(new Account("admin-" + suffix, "admin_" + suffix, hash, Role.HOSPITAL_ADMIN, tenant));
                em.persist(new Account("doctor-" + suffix, "doctor_" + suffix, hash, Role.DOCTOR, tenant));
                em.persist(new Account("patient-" + suffix, "patient_" + suffix, hash, Role.PATIENT, null));
                em.persist(new Department("department-" + suffix, tenant, "演示科室"));
                em.persist(new DoctorProfile("doctor-" + suffix, tenant, "department-" + suffix, "虚构医生"));
                em.persist(new ScheduleSlot("slot-" + suffix, tenant, "department-" + suffix, "doctor-" + suffix,
                        Date.valueOf("2099-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 20, new BigDecimal("39.25")));
            }
            return null;
        });
    }
    public <T> T inTx(Function<EntityManager,T> action) {
        EntityManager em = factory.createEntityManager();
        try {
            em.getTransaction().begin();
            T result = action.apply(em);
            em.getTransaction().commit();
            return result;
        } finally {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            em.close();
        }
    }
    public long count(Class<?> type) {
        return inTx(em -> em.createQuery("select count(e) from " + type.getSimpleName() + " e", Long.class).getSingleResult());
    }
    public AuditContext audit(AuditEventType event) { return AuditContext.create(event, UUID.randomUUID().toString()); }
    protected HospitalOpenRequest opening(String name, String username, String password, String key) {
        return new HospitalOpenRequest(name, username, password, key);
    }
    protected HospitalOpenRequest opening() { return opening("  虚构新医院  ", "New_Admin", PASSWORD, UUID.randomUUID().toString()); }
    protected HospitalAdminCreationView open(HospitalOpenRequest request) {
        return inTx(em -> new PlatformAdministrationService(em).openHospital(PLATFORM, request, audit(AuditEventType.TENANT_OPEN)));
    }
    protected HospitalAdminCreationView add(String tenant, String username, String key) {
        return inTx(em -> new PlatformAdministrationService(em).addAdministrator(PLATFORM, tenant,
                new HospitalAdministratorCreateRequest(username, PASSWORD, key), audit(AuditEventType.HOSPITAL_ADMIN_CREATE)));
    }
    protected HospitalAdministratorView status(String tenant, String id, String desired, String expected) {
        return inTx(em -> new PlatformAdministrationService(em).changeAdministratorStatus(PLATFORM, tenant, id,
                new TenantStatusRequest(desired, expected), audit(AuditEventType.HOSPITAL_ADMIN_STATUS_CHANGE)));
    }
    protected static void error(String code, int status, Runnable action) {
        HospitalAdministrationException failure = Assertions.assertThrows(HospitalAdministrationException.class, action::run);
        Assertions.assertEquals(code, failure.getCode());
        Assertions.assertEquals(status, failure.getStatus());
    }
    @AfterEach void closeAdministration() { if (factory != null) factory.close(); }
}
