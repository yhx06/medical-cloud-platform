package com.medcloud.course.paas;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.tenant.*;
import com.medcloud.course.paas.api.ApiFailureMapper;
import org.junit.jupiter.api.Test;
import javax.ejb.EJBException;
import javax.persistence.*;
import static org.junit.jupiter.api.Assertions.*;

class TenantAccessPolicyTest extends TenantFixture {
    @Test
    void policyUsesDatabaseRoleAndTenantInsteadOfActorClaims() {
        TenantAccessPolicy policy = new TenantAccessPolicy(manager);
        assertNotNull(policy.requireAccount(ADMIN, Role.HOSPITAL_ADMIN));
        assertEquals("tenant-a", policy.readableTenant(ADMIN, Role.HOSPITAL_ADMIN).getId());
        assertThrows(AccessDeniedException.class, () -> policy.requireAccount(
                new CurrentActor("patient-a", Role.HOSPITAL_ADMIN, "tenant-a"), Role.HOSPITAL_ADMIN));
        assertThrows(AccessDeniedException.class, () -> policy.requireAccount(PLATFORM, Role.PATIENT));
        assertThrows(SecurityException.class, () -> policy.requireAccount(null, Role.PATIENT));
        assertThrows(SecurityException.class, () -> policy.requireAccount(new CurrentActor("missing", Role.PATIENT, null), Role.PATIENT));
        externalUpdate("update Account a set a.status = 'DISABLED' where a.id = 'admin-a'");
        assertThrows(SecurityException.class, () -> policy.requireAccount(ADMIN, Role.HOSPITAL_ADMIN));
    }

    @Test
    void knownDisabledHospitalIsReadableButCannotBeWrittenUsingCachedTenant() {
        TenantAccessPolicy policy = new TenantAccessPolicy(manager);
        assertNotNull(policy.readableTenant("tenant-a"));
        setTenantStatus("DISABLED");
        assertEquals("DISABLED", policy.readableTenant(ADMIN, Role.HOSPITAL_ADMIN).getStatus());
        manager.getTransaction().begin();
        assertThrows(IllegalStateException.class, () -> policy.lockWritableTenant("tenant-a"));
        manager.getTransaction().rollback();
        setTenantStatus("ACTIVE");
        manager.getTransaction().begin();
        assertEquals("ACTIVE", policy.lockWritableTenant("tenant-a").getStatus());
        manager.getTransaction().commit();
    }

    @Test
    void historicalTenantQualificationDoesNotBlockHospitalStateChange() {
        manager.getTransaction().begin();
        assertEquals("tenant-a", new HospitalDepartmentService(manager).tenant(ADMIN));
        assertDoesNotThrow(() -> setTenantStatus("DISABLED"));
        manager.getTransaction().commit();
        assertEquals("DISABLED", new TenantAccessPolicy(manager).readableTenant("tenant-a").getStatus());
    }

    @Test
    void missingOrUnknownHospitalCannotAuthenticateOrReadHistory() {
        TenantAccessPolicy policy = new TenantAccessPolicy(manager);
        assertThrows(SecurityException.class, () -> policy.readableTenant("missing"));
        assertThrows(SecurityException.class, () -> policy.readableTenant((String) null));
        setTenantStatus("UNKNOWN");
        assertThrows(SecurityException.class, () -> policy.readableTenant(ADMIN, Role.HOSPITAL_ADMIN));
        AuthService auth = new AuthService(manager, SECRET);
        assertThrows(SecurityException.class, () -> auth.login(new com.medcloud.course.contract.LoginRequest("doctor_a", PASSWORD)));
        assertThrows(SecurityException.class, () -> new com.medcloud.course.paas.appointment.BookingService(manager).forDoctor(DOCTOR, 1, 100).getItems());
        externalUpdate("delete from Tenant t where t.id = 'tenant-a'");
        assertThrows(SecurityException.class, () -> policy.readableTenant(ADMIN, Role.HOSPITAL_ADMIN));
        assertThrows(SecurityException.class, () -> auth.login(new com.medcloud.course.contract.LoginRequest("admin_a", PASSWORD)));
    }

    @Test
    void lockFailuresWrappedByEjbRemainRetryableConflictsWithoutSqlDisclosure() {
        ApiFailureMapper mapper = new ApiFailureMapper();
        for (RuntimeException failure : new RuntimeException[]{new LockTimeoutException("secret SQL"), new PessimisticLockException("secret SQL")}) {
            javax.ws.rs.core.Response response = mapper.toResponse(new EJBException(new EJBException(failure)));
            assertEquals(409, response.getStatus());
            com.medcloud.course.contract.ApiError error = (com.medcloud.course.contract.ApiError) response.getEntity();
            assertEquals("CONFLICT", error.getCode());
            assertFalse(error.getMessage().contains("secret"));
        }
        assertEquals(401, mapper.toResponse(new EJBException(new SecurityException())).getStatus());
        assertEquals(403, mapper.toResponse(new EJBException(new AccessDeniedException())).getStatus());
        assertEquals(400, mapper.toResponse(new EJBException(new IllegalArgumentException())).getStatus());
    }
}
