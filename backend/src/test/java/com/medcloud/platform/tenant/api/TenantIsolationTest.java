package com.medcloud.platform.tenant.api;

import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.security.CurrentUser;
import com.medcloud.platform.tenant.application.TenantAccessPolicy;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantIsolationTest {
    private final TenantAccessPolicy policy = new TenantAccessPolicy();

    @Test
    void allowsMatchingTenant() {
        CurrentUser doctorA = doctor("11111111-1111-1111-1111-111111111111");
        assertThatCode(() -> policy.requireTenant(doctorA, doctorA.tenantId())).doesNotThrowAnyException();
    }

    @Test
    void rejectsDifferentTenant() {
        CurrentUser doctorA = doctor("11111111-1111-1111-1111-111111111111");
        UUID tenantB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        assertThatThrownBy(() -> policy.requireTenant(doctorA, tenantB))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.TENANT_ACCESS_DENIED);
    }

    @Test
    void rejectsMissingTenantContext() {
        CurrentUser patient = new CurrentUser(UUID.randomUUID(), "patient", UserRole.PATIENT, null);
        assertThatThrownBy(() -> policy.requireTenant(patient, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.TENANT_ACCESS_DENIED);
    }

    @Test
    void platformAdminDoesNotBypassObjectTenantChecks() {
        CurrentUser admin = new CurrentUser(UUID.randomUUID(), "platform_admin", UserRole.PLATFORM_ADMIN, null);
        assertThatThrownBy(() -> policy.requireTenant(admin, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.TENANT_ACCESS_DENIED);
    }

    private CurrentUser doctor(String tenantId) {
        return new CurrentUser(UUID.randomUUID(), "doctor", UserRole.DOCTOR, UUID.fromString(tenantId));
    }
}
