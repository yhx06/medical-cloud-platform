package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.tenant.*;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import javax.persistence.PersistenceException;
import javax.persistence.EntityManager;
import java.lang.reflect.*;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HospitalAdministrationServiceTest extends HospitalAdministrationFixture {
    @Test void concreteMysqlUsernameDuplicateDiagnosticMapsToSafeConflict() {
        for (String key : Arrays.asList("uk_course_username", "user_account.uk_course_username")) {
            error("USERNAME_EXISTS",409,() -> openingWithDriverFailure("Duplicate entry 'synthetic' for key '" + key + "'",1062));
        }
        assertEquals(2,count(Tenant.class)); assertEquals(0,count(HospitalAdminOperation.class));
    }
    @Test void mysqlIntegrityMetadataCannotTreatConstraintMarkerInDataAsKeyIdentity() {
        assertThrows(PersistenceException.class,() -> openingWithDriverFailure(
                "Duplicate entry 'uk_course_username' for key 'uk_another_constraint'",1062));
        assertThrows(PersistenceException.class,() -> openingWithDriverFailure(
                "Check constraint failed with data uk_course_username",3819));
        assertEquals(2,count(Tenant.class)); assertEquals(0,count(HospitalAdminOperation.class));
    }
    private void openingWithDriverFailure(String diagnostic,int vendorCode) {
        inTx(em -> {
            // Supplement real H2 constraint tests with the supported JDBC driver's diagnostic shape.
            // Actual JPA inserts/flush and transaction rollback still run; no live MySQL is claimed.
            EntityManager driver = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),new Class<?>[]{EntityManager.class},(proxy,method,args) -> {
                try {
                    Object result=method.invoke(em,args);
                    if (method.getName().equals("flush")) throw new PersistenceException(new SQLException(diagnostic,"23000",vendorCode));
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            return new PlatformAdministrationService(driver).openHospital(PLATFORM,opening(),audit(AuditEventType.TENANT_OPEN));
        });
    }
    @Test void unrelatedAccountIntegrityFailureContainingUsernameConstraintMarkerIsNotUsernameConflict() {
        inTx(em -> { em.createNativeQuery("alter table user_account add constraint ck_test_username check (username <> 'uk_course_username')").executeUpdate(); return null; });
        long accounts = count(Account.class);
        assertThrows(PersistenceException.class, () -> open(opening("虚构检查医院", "uk_course_username", PASSWORD, UUID.randomUUID().toString())));
        assertEquals(2, count(Tenant.class)); assertEquals(accounts, count(Account.class)); assertEquals(0, count(HospitalAdminOperation.class));
    }
    @Test void unrelatedDoctorIntegrityFailureIsNotReportedAsUsernameConflict() {
        inTx(em -> { em.createNativeQuery("alter table doctor_profile add constraint ck_test_doctor_name check (display_name <> 'uk_course_username')").executeUpdate(); return null; });
        long accounts = count(Account.class);
        assertThrows(PersistenceException.class, () -> inTx(em -> new HospitalDoctorService(em).create(ADMIN,
                "check_doctor", "uk_course_username", "department-a")));
        assertEquals(accounts, count(Account.class)); assertEquals(2, count(DoctorProfile.class)); assertEquals(0, count(AuditEvent.class));
    }
    @Test void openingPersistsOneActiveHospitalAdministratorOperationAndSuccessAudit() {
        long accounts = count(Account.class);
        HospitalAdminCreationView view = open(opening());
        assertEquals("虚构新医院", view.getHospitalName());
        assertEquals(3, count(Tenant.class)); assertEquals(accounts + 1, count(Account.class));
        assertEquals(1, count(HospitalAdminOperation.class)); assertEquals(1, count(AuditEvent.class));
        inTx(em -> {
            Account account = em.find(Account.class, view.getAdministratorId());
            assertEquals(Role.HOSPITAL_ADMIN, account.getRole()); assertEquals("ACTIVE", account.getStatus());
            assertEquals(view.getTenantId(), account.getTenantId());
            assertEquals("ACTIVE", em.find(Tenant.class, view.getTenantId()).getStatus());
            assertNotEquals(PASSWORD, account.getPasswordHash()); assertTrue(BCrypt.checkpw(PASSWORD, account.getPasswordHash()));
            AuditEvent event = em.createQuery("select e from AuditEvent e", AuditEvent.class).getSingleResult();
            assertEquals(AuditResult.SUCCESS, event.getResult()); assertEquals(view.getTenantId(), event.getObjectId());
            return null;
        });
    }
    @Test void retryUsesNormalizedNameAndCaseEquivalentUsernameWithoutNewRows() {
        HospitalOpenRequest request = opening(); HospitalAdminCreationView first = open(request);
        HospitalAdminCreationView retry = open(opening("虚构新医院", "new_admin", PASSWORD, request.getOperationKey()));
        assertEquals(first.getAdministratorId(), retry.getAdministratorId()); assertEquals(first.getTenantId(), retry.getTenantId());
        assertEquals("New_Admin", retry.getUsername()); assertEquals(3, count(Tenant.class)); assertEquals(1, count(HospitalAdminOperation.class));
        assertEquals(AuditResult.NO_CHANGE, inTx(em -> em.createQuery("select e.result from AuditEvent e where e.result = :r", AuditResult.class)
                .setParameter("r", AuditResult.NO_CHANGE).getSingleResult()));
    }
    @Test void sameKeyRejectsChangedNameUsernamePasswordTypeAndTarget() {
        HospitalOpenRequest request = opening(); open(request);
        for (HospitalOpenRequest changed : Arrays.asList(
                opening("另一个医院", "New_Admin", PASSWORD, request.getOperationKey()),
                opening("虚构新医院", "different", PASSWORD, request.getOperationKey()),
                opening("虚构新医院", "New_Admin", "Other@12345", request.getOperationKey())))
            error("OPERATION_KEY_CONFLICT", 409, () -> open(changed));
        error("OPERATION_KEY_CONFLICT", 409, () -> add("tenant-a", "New_Admin", request.getOperationKey()));
        String key = UUID.randomUUID().toString(); add("tenant-a", "extra_admin", key);
        error("OPERATION_KEY_CONFLICT", 409, () -> add("tenant-b", "extra_admin", key));
        error("OPERATION_KEY_CONFLICT", 409, () -> open(opening("虚构医院a", "extra_admin", PASSWORD, key)));
    }
    @Test void usernameIsGlobalCaseInsensitiveButHospitalNamesCanRepeat() {
        error("USERNAME_EXISTS", 409, () -> open(opening("重复名称", "DOCTOR_A", PASSWORD, UUID.randomUUID().toString())));
        HospitalAdminCreationView first = open(opening("重复名称", "unique_1", PASSWORD, UUID.randomUUID().toString()));
        HospitalAdminCreationView second = open(opening("重复名称", "unique_2", PASSWORD, UUID.randomUUID().toString()));
        assertNotEquals(first.getTenantId(), second.getTenantId());
        error("USERNAME_EXISTS", 409, () -> open(opening("其他名称", "UNIQUE_1", PASSWORD, UUID.randomUUID().toString())));
    }
    @Test void retryDoesNotEnableDisabledAdministratorOrFrozenHospital() {
        HospitalOpenRequest request = opening(); HospitalAdminCreationView first = open(request);
        inTx(em -> { em.find(Tenant.class, first.getTenantId()).setStatus("DISABLED"); em.find(Account.class, first.getAdministratorId()).disable(); return null; });
        assertEquals(first.getAdministratorId(), open(request).getAdministratorId());
        inTx(em -> { assertEquals("DISABLED", em.find(Tenant.class, first.getTenantId()).getStatus());
            assertEquals("DISABLED", em.find(Account.class, first.getAdministratorId()).getStatus()); return null; });
    }
    @Test void creationAuditFailureRollsBackAllThreeBusinessRows() {
        long accounts = count(Account.class);
        assertThrows(IllegalStateException.class, () -> inTx(em -> new PlatformAdministrationService(em,
                (context, id, result, at) -> { throw new IllegalStateException("injected audit failure"); })
                .openHospital(PLATFORM, opening(), audit(AuditEventType.TENANT_OPEN))));
        assertEquals(2, count(Tenant.class)); assertEquals(accounts, count(Account.class));
        assertEquals(0, count(HospitalAdminOperation.class)); assertEquals(0, count(AuditEvent.class));
    }
    @Test void addAuditFailureRollsBackAccountAndOperation() {
        long accounts = count(Account.class);
        assertThrows(IllegalStateException.class, () -> inTx(em -> new PlatformAdministrationService(em,
                (context, id, result, at) -> { throw new IllegalStateException("injected audit failure"); })
                .addAdministrator(PLATFORM, "tenant-a", new HospitalAdministratorCreateRequest("failed_admin", PASSWORD,
                        UUID.randomUUID().toString()), audit(AuditEventType.HOSPITAL_ADMIN_CREATE))));
        assertEquals(2, count(Tenant.class)); assertEquals(accounts, count(Account.class)); assertEquals(0, count(HospitalAdminOperation.class));
    }
    @Test void frozenHospitalAllowsAddAndStatusChangesButProtectsLastActive() {
        inTx(em -> { em.find(Tenant.class,"tenant-a").setStatus("DISABLED"); return null; });
        error("LAST_ACTIVE_HOSPITAL_ADMIN",409,() -> status("tenant-a","admin-a","DISABLED","ACTIVE"));
        HospitalAdminCreationView extra = add("tenant-a","second_admin",UUID.randomUUID().toString());
        assertEquals("DISABLED",status("tenant-a","admin-a","DISABLED","ACTIVE").getStatus());
        error("LAST_ACTIVE_HOSPITAL_ADMIN",409,() -> status("tenant-a",extra.getAdministratorId(),"DISABLED","ACTIVE"));
        assertEquals("DISABLED",status("tenant-a","admin-a","DISABLED","ACTIVE").getStatus());
        error("ADMIN_STATUS_CONFLICT",409,() -> status("tenant-a","admin-a","ACTIVE","ACTIVE"));
        assertEquals("ACTIVE",status("tenant-a","admin-a","ACTIVE","DISABLED").getStatus());
        assertEquals("DISABLED",inTx(em -> em.find(Tenant.class,"tenant-a").getStatus()));
    }
    @Test void legacyZeroActiveHospitalCanBeRepairedAndListHasStablePaginationAndGlobalActiveCount() {
        inTx(em -> { em.find(Account.class,"admin-a").disable(); return null; });
        add("tenant-a","aaa_admin",UUID.randomUUID().toString());
        HospitalAdministratorPage page = inTx(em -> new PlatformAdministrationService(em).administrators(PLATFORM,"tenant-a",1,1));
        assertEquals(2,page.getTotal()); assertEquals(1,page.getActiveAdministratorCount()); assertEquals("aaa_admin",page.getItems().get(0).getUsername());
        assertEquals("tenant-a",page.getTenant().getId());
        assertEquals("admin-a",inTx(em -> new PlatformAdministrationService(em).administrators(PLATFORM,"tenant-a",2,1)).getItems().get(0).getId());
        assertTrue(inTx(em -> new PlatformAdministrationService(em).administrators(PLATFORM,"tenant-a",3,1)).getItems().isEmpty());
    }
    @Test void invalidInputUsesStableSafeErrorAndPasswordIsNotTrimmed() {
        HospitalOpenRequest request = opening("合法医院","space_admin"," pass 12 ",UUID.randomUUID().toString());
        HospitalAdminCreationView created = open(request);
        assertTrue(this.<Boolean>inTx(em -> BCrypt.checkpw(" pass 12 ",em.find(Account.class,created.getAdministratorId()).getPasswordHash())).booleanValue());
        error("OPERATION_KEY_CONFLICT",409,() -> open(opening("合法医院","space_admin","pass 12 ",request.getOperationKey())));
        for (HospitalOpenRequest invalid : Arrays.asList(null, opening("\u0001bad","valid","12345678",UUID.randomUUID().toString()),
                opening("valid"," bad","12345678",UUID.randomUUID().toString()), opening("valid","valid","short",UUID.randomUUID().toString()),
                opening("valid","valid","12345678","00000000-0000-0000-0000-000000000000")))
            error("HOSPITAL_ONBOARDING_INVALID",400,() -> open(invalid));
        for (int[] values : new int[][] {{0,20},{1,101},{Integer.MAX_VALUE,100}})
            error("HOSPITAL_ONBOARDING_INVALID",400,() -> inTx(em -> new PlatformAdministrationService(em).administrators(PLATFORM,"tenant-a",values[0],values[1])));
    }
}
