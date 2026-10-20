package com.medcloud.course.paas;

import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.contract.PaymentOrderView;
import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.api.PaymentResource;
import com.medcloud.course.paas.api.HospitalResource;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.order.PaymentOrder;
import com.medcloud.course.paas.order.PaymentService;
import com.medcloud.course.paas.tenant.Tenant;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HospitalOrderTest {
    @Test
    void hospitalListUsesAccountTenantAndFiltersPaymentDateInShanghai() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "仁和云诊所"));
            manager.persist(new Tenant("tenant-b", "安康云门诊"));
            manager.persist(new Account("admin-a", "admin_a", BCrypt.hashpw("Admin@123456", BCrypt.gensalt(4)),
                    Role.HOSPITAL_ADMIN, "tenant-a"));
            manager.persist(new Account("admin-b", "admin_b", "hash", Role.HOSPITAL_ADMIN, "tenant-b"));
            PaymentOrder early = order("early", "tenant-a");
            early.pay(Instant.parse("2030-05-20T15:59:59Z"));
            PaymentOrder inRange = order("in-range", "tenant-a");
            inRange.pay(Instant.parse("2030-05-20T16:00:00Z"));
            PaymentOrder late = order("late", "tenant-a");
            late.pay(Instant.parse("2030-05-21T16:00:00Z"));
            PaymentOrder unpaid = order("unpaid", "tenant-a");
            PaymentOrder foreign = order("foreign", "tenant-b");
            foreign.pay(Instant.parse("2030-05-21T01:00:00Z"));
            manager.persist(early);
            manager.persist(inRange);
            manager.persist(late);
            manager.persist(unpaid);
            manager.persist(foreign);
            manager.getTransaction().commit();

            PaymentService service = new PaymentService(manager);
            CurrentActor claimedOtherTenant = new CurrentActor("admin-a", Role.HOSPITAL_ADMIN, "tenant-b");
            List<PaymentOrder> own = service.forHospital(claimedOtherTenant, null, null, null, 1, 100).getItems();
            assertEquals(4, own.size());
            assertEquals("late", own.get(0).getId());
            assertEquals("tenant-a", own.get(0).getTenantId());
            List<PaymentOrder> paidOnMay21 = service.forHospital(claimedOtherTenant, "PAID",
                    Date.valueOf("2030-05-21"), Date.valueOf("2030-05-21"), 1, 100).getItems();
            assertEquals(1, paidOnMay21.size());
            assertEquals("in-range", paidOnMay21.get(0).getId());
            assertEquals("unpaid", service.forHospital(claimedOtherTenant, "UNPAID", null, null, 1, 100).getItems().get(0).getId());

            AuthService auth = new AuthService(manager, "course-test-signing-secret-at-least-32-characters");
            String bearer = "Bearer " + AuditLocalTransactions.login(manager, auth, new LoginRequest("admin_a", "Admin@123456")).getAccessToken();
            // An existing bearer and cached tenant must retain the same historical scope after disable.
            EntityManager disabling = factory.createEntityManager();
            try {
                disabling.getTransaction().begin();
                disabling.createNativeQuery("update tenant set status = 'DISABLED' where id = 'tenant-a'").executeUpdate();
                disabling.getTransaction().commit();
            } finally {
                if (disabling.getTransaction().isActive()) disabling.getTransaction().rollback();
                disabling.close();
            }
            assertEquals(4, service.forHospital(claimedOtherTenant, null, null, null, 1, 100).getItems().size());
            HospitalResource api = new HospitalResource(auth, service);
            assertEquals(4, api.orders(bearer, null, null, null, "1", "100").getData().getItems().size());
            assertEquals("in-range", api.orders(bearer, "PAID", "2030-05-21", "2030-05-21", "1", "100")
                    .getData().getItems().get(0).getId());
            assertEquals("2030-05-21 00:00:00", api.orders(bearer, "PAID", "2030-05-21", "2030-05-21", "1", "100")
                    .getData().getItems().get(0).getPaidAt());
            assertThrows(SecurityException.class, () -> new PaymentResource(auth, service, AuditClinicalFixture.legacyRunner(manager)).pay(bearer, "in-range"));
        } finally {
            manager.close();
            factory.close();
        }
    }

    @Test
    void invalidHospitalFiltersAndInactiveAccountAreRejected() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "仁和云诊所"));
            Account admin = new Account("admin-a", "admin_a",
                    BCrypt.hashpw("Admin@123456", BCrypt.gensalt(4)), Role.HOSPITAL_ADMIN, "tenant-a");
            manager.persist(admin);
            manager.getTransaction().commit();
            PaymentService service = new PaymentService(manager);
            CurrentActor actor = new CurrentActor("admin-a", Role.HOSPITAL_ADMIN, "tenant-a");
            assertThrows(IllegalArgumentException.class, () -> service.forHospital(actor, "REFUNDED", null, null, 1, 100).getItems());
            assertThrows(IllegalArgumentException.class, () -> service.forHospital(actor, null,
                    Date.valueOf("2030-05-22"), Date.valueOf("2030-05-21"), 1, 100));
            assertThrows(SecurityException.class, () -> service.forHospital(
                    new CurrentActor("admin-a", Role.PATIENT, "tenant-a"), null, null, null, 1, 100));
            HospitalResource api = new HospitalResource(new AuthService(manager,
                    "course-test-signing-secret-at-least-32-characters"), service);
            String bearer = "Bearer " + AuditLocalTransactions.login(manager, new AuthService(manager,
                    "course-test-signing-secret-at-least-32-characters"),
                    new LoginRequest("admin_a", "Admin@123456")).getAccessToken();
            assertThrows(IllegalArgumentException.class, () -> api.orders(bearer, null, "not-a-date", null, "1", "100"));
            manager.getTransaction().begin();
            admin.disable();
            manager.getTransaction().commit();
            assertThrows(SecurityException.class, () -> service.forHospital(actor, null, null, null, 1, 100).getItems());
        } finally {
            manager.close();
            factory.close();
        }
    }

    @Test
    void legacyPaidOrderKeepsUnknownTimestamp() {
        PaymentOrder legacy = order("legacy", "tenant-a");
        legacy.pay();
        assertEquals("PAID", legacy.getStatus());
        assertNull(legacy.getPaidAt());
        legacy.pay(Instant.parse("2030-05-21T01:00:00Z"));
        assertNull(legacy.getPaidAt());
        assertNull(new PaymentOrderView(legacy.getId(), legacy.getAppointmentId(), legacy.getAmount(),
                legacy.getStatus(), legacy.getPaidAt()).getPaidAt());
    }

    @Test
    void hospitalOrdersAreFullyPaginatedNewestFirst() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "仁和云诊所"));
            manager.persist(new Account("admin-a", "admin_a", "hash", Role.HOSPITAL_ADMIN, "tenant-a"));
            for (int i = 0; i < 101; i++) {
                PaymentOrder order = order(String.format("order-%03d", i), "tenant-a");
                order.pay(Instant.parse("2030-05-21T01:00:00Z").plusSeconds(i));
                manager.persist(order);
            }
            manager.getTransaction().commit();
            PaymentService service = new PaymentService(manager);
            CurrentActor actor = new CurrentActor("admin-a", Role.HOSPITAL_ADMIN, "tenant-a");
            PageResult<PaymentOrder> first = service.forHospital(actor, null, null, null, 1, 20);
            assertEquals(101, first.getTotal());
            assertEquals(20, first.getItems().size());
            assertEquals("order-100", first.getItems().get(0).getId());
            assertEquals("order-081", first.getItems().get(19).getId());
            PageResult<PaymentOrder> last = service.forHospital(actor, null, null, null, 6, 20);
            assertEquals(101, last.getTotal());
            assertEquals(1, last.getItems().size());
            assertEquals("order-000", last.getItems().get(0).getId());
            assertThrows(IllegalArgumentException.class, () -> service.forHospital(actor, null, null, null, 1, 101));
            assertThrows(IllegalArgumentException.class, () -> service.forHospital(actor, null, null, null, 0, 20));
        } finally {
            manager.close();
            factory.close();
        }
    }

    private static PaymentOrder order(String id, String tenantId) {
        return new PaymentOrder(id, "appointment-" + id, "patient-a", tenantId, new BigDecimal("39.00"));
    }
}
