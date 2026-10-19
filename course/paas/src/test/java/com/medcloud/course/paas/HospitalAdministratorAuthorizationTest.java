package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.tenant.*;
import com.medcloud.course.paas.schedule.*;
import com.medcloud.course.paas.audit.AuditEvent;
import javax.persistence.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.sql.Date;
import java.sql.Time;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class HospitalAdministratorAuthorizationTest extends HospitalAdministrationFixture {
    @Test void wrongRolesAndStaleOrForgedPlatformActorsCannotWrite() {
        for (CurrentActor actor : Arrays.asList(ADMIN,new CurrentActor("doctor-a",Role.DOCTOR,"tenant-a"),
                new CurrentActor("patient-a",Role.PATIENT,null),new CurrentActor("admin-a",Role.PLATFORM_ADMIN,null)))
            assertThrows(AccessDeniedException.class,() -> inTx(em -> new PlatformAdministrationService(em).openHospital(actor,opening(),audit(AuditEventType.TENANT_OPEN))));
        inTx(em -> { em.find(Account.class,"platform-1").disable(); return null; });
        assertThrows(SecurityException.class,() -> open(opening()));
        assertEquals(2,count(Tenant.class)); assertEquals(0,count(AuditEvent.class));
    }
    @Test void crossHospitalAndWrongRoleTargetsAreForbiddenBeforeStatusMutation() {
        for (String target : Arrays.asList("admin-b","doctor-a","patient-a","platform-1","missing"))
            assertThrows(AccessDeniedException.class,() -> status("tenant-a",target,"DISABLED","ACTIVE"));
        assertThrows(AccessDeniedException.class,() -> inTx(em -> new PlatformAdministrationService(em).administrators(PLATFORM,"missing",1,20)));
        assertEquals(0,count(AuditEvent.class));
    }
    @Test void retryRejectsCorruptedAdministratorAssociationBeforePasswordVerification() {
        HospitalOpenRequest request = opening(); HospitalAdminCreationView first = open(request);
        inTx(em -> { em.createQuery("update Account a set a.tenantId = 'tenant-b' where a.id = :id").setParameter("id",first.getAdministratorId()).executeUpdate(); return null; });
        assertThrows(AccessDeniedException.class,() -> open(request));
        assertEquals(1,count(AuditEvent.class));
    }
    @Test void lockedPlatformRefreshRejectsAccountDisabledSinceFirstLevelCacheRead() {
        inTx(em -> {
            assertEquals("ACTIVE",em.find(Account.class,"platform-1").getStatus());
            inTx(other -> { other.find(Account.class,"platform-1").disable(); return null; });
            assertThrows(SecurityException.class,() -> new PlatformAdministrationService(em).openHospital(PLATFORM,opening(),audit(AuditEventType.TENANT_OPEN)));
            return null;
        });
        assertEquals(0,count(HospitalAdminOperation.class));
    }
    @ParameterizedTest @ValueSource(strings={"department-create","department-update","doctor-create","doctor-update","slot-create","slot-update"})
    void allSixWritersRecheckDisabledActorAtTenantLockBoundary(String writer) {
        long accounts=count(Account.class), departments=count(Department.class), slots=count(ScheduleSlot.class);
        inTx(em -> {
            AtomicBoolean boundary = new AtomicBoolean();
            // Real JPA performs all reads/writes. The wrapper only schedules a committed revocation
            // between initial authorization and the tenant lock; real lock waiting belongs to MySQL gates.
            EntityManager scheduled = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),new Class<?>[]{EntityManager.class},(proxy,method,args) -> {
                if (method.getName().equals("find") && args.length >= 3 && args[0] == Tenant.class
                        && args[2] == LockModeType.PESSIMISTIC_READ && boundary.compareAndSet(false,true)) {
                    assertEquals("ACTIVE",em.find(Account.class,"admin-a").getStatus());
                    inTx(other -> { other.find(Account.class,"admin-a").disable(); return null; });
                }
                try { return method.invoke(em,args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            assertThrows(SecurityException.class,() -> write(scheduled,writer)); assertTrue(boundary.get());
            return null;
        });
        assertEquals(accounts,count(Account.class)); assertEquals(departments,count(Department.class)); assertEquals(slots,count(ScheduleSlot.class));
        assertEquals(0,count(AuditEvent.class));
        inTx(em -> { assertEquals("演示科室",em.find(Department.class,"department-a").getName());
            assertEquals("虚构医生",em.find(DoctorProfile.class,"doctor-a").getDisplayName());
            assertEquals(20,em.find(ScheduleSlot.class,"slot-a").getCapacity()); return null; });
    }
    @Test void hospitalWriterRecheckUsesDatabaseOwnershipDespiteForgedTenantClaims() {
        inTx(em -> { TenantAccessPolicy policy=new TenantAccessPolicy(em); policy.lockWritableTenant("tenant-a");
            assertEquals("admin-a",policy.recheckHospitalWriter(new CurrentActor("admin-a",Role.HOSPITAL_ADMIN,"tenant-b"),"tenant-a").getId());
            assertThrows(AccessDeniedException.class,() -> policy.recheckHospitalWriter(new CurrentActor("admin-b",Role.HOSPITAL_ADMIN,"tenant-a"),"tenant-a")); return null; });
    }
    private void write(EntityManager em,String writer) {
        SlotRequest request=new SlotRequest("department-a","doctor-a",Date.valueOf("2099-05-22"),Time.valueOf("09:00:00"),Time.valueOf("10:00:00"),30,new BigDecimal("39.25"),"PUBLISHED");
        switch(writer) {
            case "department-create": new HospitalDepartmentService(em).create(ADMIN,"新科室"); break;
            case "department-update": new HospitalDepartmentService(em).update(ADMIN,"department-a","更新科室","ACTIVE"); break;
            case "doctor-create": new HospitalDoctorService(em).create(ADMIN,"new_doctor","新医生","department-a"); break;
            case "doctor-update": new HospitalDoctorService(em).update(ADMIN,"doctor-a","更新医生","department-a","ACTIVE"); break;
            case "slot-create": new SlotAdminService(em).create(ADMIN,request); break;
            case "slot-update": new SlotAdminService(em).update(ADMIN,"slot-a",request); break;
            default: throw new AssertionError(writer);
        }
    }
}
