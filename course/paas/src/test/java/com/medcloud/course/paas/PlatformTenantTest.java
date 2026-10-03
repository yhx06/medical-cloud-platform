package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.api.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.tenant.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlatformTenantTest extends TenantFixture {
    @Test
    void disableIsIdempotentAndStaleEnableCannotOverwriteStatus() {
        PlatformTenantService service = new PlatformTenantService(manager);
        List<String> before = snapshot();
        manager.getTransaction().begin();
        assertEquals("DISABLED", service.changeStatus(PLATFORM, "tenant-a",
                new TenantStatusRequest("DISABLED", "ACTIVE")).getStatus());
        assertEquals("DISABLED", service.changeStatus(PLATFORM, "tenant-a",
                new TenantStatusRequest("DISABLED", "ACTIVE")).getStatus());
        assertThrows(IllegalStateException.class, () -> service.changeStatus(PLATFORM, "tenant-a",
                new TenantStatusRequest("ACTIVE", "ACTIVE")));
        manager.getTransaction().commit();
        assertEquals(before, snapshot(), "disabling must not mutate business rows including pending and unpaid records");
        assertEquals("ACTIVE", manager.find(Tenant.class, "tenant-b").getStatus());
        assertEquals("DISABLED", service.list(PLATFORM, 1, 20).getItems().get(0).getStatus());
    }

    @Test
    void reenablingDoesNotRestoreIndependentlyDisabledResources() {
        externalUpdate("update Account a set a.status = 'DISABLED' where a.id = 'doctor-a'");
        externalUpdate("update Department d set d.status = 'DISABLED' where d.id = 'department-a'");
        externalUpdate("update ScheduleSlot s set s.status = 'WITHDRAWN' where s.id = 'slot-a'");
        List<String> before = snapshot();
        PlatformTenantService service = new PlatformTenantService(manager);
        manager.getTransaction().begin();
        service.changeStatus(PLATFORM, "tenant-a", new TenantStatusRequest("DISABLED", "ACTIVE"));
        assertEquals("DISABLED", manager.find(Tenant.class, "tenant-a").getStatus());
        service.changeStatus(PLATFORM, "tenant-a", new TenantStatusRequest("ACTIVE", "DISABLED"));
        manager.getTransaction().commit();
        manager.clear();
        assertEquals("ACTIVE", manager.find(Tenant.class, "tenant-a").getStatus());
        assertEquals("DISABLED", manager.find(Account.class, "doctor-a").getStatus());
        assertEquals("DISABLED", manager.find(Department.class, "department-a").getStatus());
        assertEquals("WITHDRAWN", manager.find(com.medcloud.course.paas.schedule.ScheduleSlot.class, "slot-a").getStatus());
        assertEquals(before, snapshot());
    }

    @Test
    void paginationCountsAllHospitalsAndBreaksEqualNamesById() {
        externalUpdate("update Tenant t set t.name = 'same name'");
        PlatformTenantService service = new PlatformTenantService(manager);
        TenantPage first = service.list(PLATFORM, 1, 1);
        assertEquals(2L, first.getTotal());
        assertEquals(1, first.getPage());
        assertEquals(1, first.getSize());
        assertEquals("tenant-a", first.getItems().get(0).getId());
        assertEquals("tenant-b", service.list(PLATFORM, 2, 1).getItems().get(0).getId());
        assertTrue(service.list(PLATFORM, 3, 1).getItems().isEmpty());
        assertEquals(2, service.list(PLATFORM, 1, 100).getItems().size());
        for (int[] input : new int[][] {{0, 20}, {-1, 20}, {1, 0}, {1, 101}, {Integer.MAX_VALUE, 100}}) {
            assertThrows(IllegalArgumentException.class, () -> service.list(PLATFORM, input[0], input[1]));
        }
    }

    @Test
    void platformPermissionUsesDatabaseRoleAndMissingPrivateTargetIsForbidden() {
        PlatformTenantService service = new PlatformTenantService(manager);
        assertStatus(403, () -> service.list(ADMIN, 1, 20));
        assertStatus(403, () -> service.list(new CurrentActor("admin-a", Role.PLATFORM_ADMIN, null), 1, 20));
        manager.getTransaction().begin();
        assertStatus(403, () -> service.changeStatus(ADMIN, "tenant-a", new TenantStatusRequest("DISABLED", "ACTIVE")));
        assertStatus(403, () -> service.changeStatus(PLATFORM, "missing", new TenantStatusRequest("DISABLED", "ACTIVE")));
        manager.getTransaction().rollback();
        externalUpdate("update Account a set a.status = 'DISABLED' where a.id = 'platform'");
        assertStatus(401, () -> service.list(PLATFORM, 1, 20));
    }

    @Test
    void entityAndStatusRequestsRejectInvalidStateEvenOnIdempotentTarget() {
        Tenant tenant = manager.find(Tenant.class, "tenant-a");
        for (String invalid : new String[] {null, "", "UNKNOWN", "active", " ACTIVE "}) {
            assertThrows(IllegalArgumentException.class, () -> tenant.setStatus(invalid));
        }
        PlatformTenantService service = new PlatformTenantService(manager);
        manager.getTransaction().begin();
        assertStatus(400, () -> service.changeStatus(PLATFORM, "tenant-a", null));
        assertStatus(400, () -> service.changeStatus(PLATFORM, "tenant-a", new TenantStatusRequest("ACTIVE", null)));
        assertStatus(400, () -> service.changeStatus(PLATFORM, "tenant-a", new TenantStatusRequest("ACTIVE", "UNKNOWN")));
        assertStatus(400, () -> service.changeStatus(PLATFORM, "tenant-a", new TenantStatusRequest("UNKNOWN", "ACTIVE")));
        manager.getTransaction().rollback();
        assertEquals("ACTIVE", tenant.getStatus());
    }

    static void assertStatus(int status, Runnable operation) {
        RuntimeException failure = assertThrows(RuntimeException.class, operation::run);
        assertEquals(status, new ApiFailureMapper().toResponse(failure).getStatus());
    }
}
